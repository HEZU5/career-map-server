import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../../../core/io/fullscreen.dart';
import '../../../core/io/orientation_lock.dart';
import '../../../core/io/save_bytes.dart';
import '../../../core/storage/session_store.dart';
import '../../../core/theme/app_colors.dart';
import '../data/career_cards.dart';
import '../data/game_api.dart';
import '../data/game_socket.dart';
import '../data/room_api.dart';
import '../domain/board.dart';
import '../domain/game_room.dart';
import '../domain/player.dart';
import '../domain/riasec.dart';
import 'game_controller.dart';
import 'turn_clock_driver.dart';
import 'widgets/board_center_frame.dart';
import 'widgets/board_geometry.dart';
import 'widgets/dice_control_zone.dart';
import 'widgets/game_board.dart';
import 'widgets/game_dialogs.dart';
import 'widgets/game_rail.dart';
import 'widgets/player_corner.dart';
import 'widgets/player_rail_card.dart';

/// Màn chơi chính — bố cục 3 VÙNG: `railTrái | Bàn cờ | railPhải`.
///
/// Nguyên tắc bất di bất dịch: **không widget nào của UI nằm trên bàn cờ ở
/// vùng có ô** — mọi thẻ người chơi, mọi nút phụ, mã phòng, dải nhóm RIASEC
/// đều nằm trong hai rail; ô điều khiển xúc xắc nằm ở dải mũi tên `<<<<`
/// giữa START và ô 52 (vùng không có ô nào); popup Cơ hội/Thử thách/câu hỏi
/// nằm trong khung giữa 8–92% x 17–83%; pill kết quả tung cũng ở khung giữa.
///
/// - `GameController` sở hữu state ván (qua `ChangeNotifierProvider.value`).
/// - Online: server quyết định lượt; nút TUNG chỉ dựng vào cây khi
///   `isMyTurn && waitingRoll && không đi quân && không popup`.
/// - Bàn cờ luôn ở khung tỉ lệ ảnh gốc (FittedBox contain) nên mọi toạ độ %
///   (ô, quân, ô điều khiển) luôn khớp ảnh ở mọi kích thước màn hình.
class GamePage extends StatefulWidget {
  const GamePage({
    super.key,
    required this.roomCode,
    this.initialPlayers,
    this.myPlayerKey,
    this.isOnline = false,
    this.session,
  });

  final String roomCode;
  final List<Player>? initialPlayers;

  /// Chìa khoá người chơi của thiết bị này (host-XX hoặc guest-XX-n).
  final String? myPlayerKey;
  final bool isOnline;

  /// Dùng để xoá phiên "vào lại trận" khi người chơi RỜI phòng hẳn. Thoát
  /// kiểu giữ chỗ thì KHÔNG đụng tới phiên.
  final SessionStore? session;

  @override
  State<GamePage> createState() => _GamePageState();
}

class _GamePageState extends State<GamePage> with TickerProviderStateMixin, WidgetsBindingObserver {
  late final AnimationController _diceController;
  late final AnimationController _moveController;

  /// Nhịp nhấp nháy nút TUNG (nhắc "Đến lượt").
  late final AnimationController _blinkController;

  /// Độ mờ của pill kết quả (1 → hiện, 0 → đã tắt sau khi quân đi xong).
  late final AnimationController _pillFadeController;

  final RoomApi _roomApi = RoomApi();
  late final GameController _game;
  GameSocket? _socket;

  /// Heartbeat + đồng hồ server + tự tung dự phòng (chỉ khi online).
  TurnClockDriver? _clock;
  Timer? _poll;

  /// Quân cờ đang chạy theo đường ô (playerId + điểm đi).
  String? _movingPlayerId;
  int _moveFrom = 0;
  int _moveSteps = 0;

  /// Đã tung xong lượt hiện tại (offline: hiện "LƯỢT TIẾP" để chuyển lượt).
  bool _hasRolledThisTurn = false;
  int _prevActiveIndex = 0;

  /// Key người đang tới lượt lần trước. Sau giai đoạn xếp thứ tự, server sắp
  /// lại danh sách người chơi nên `activeIndex` có thể VẪN bằng 0 dù người
  /// tới lượt đã đổi — phải so cả danh tính người chơi để reset trạng thái
  /// "đã tung", nếu không sẽ không hiện lại nút TUNG.
  String? _prevActivePlayerId;

  /// Builder thẻ đang mở trong khung giữa (Cơ hội/Thử thách/câu hỏi).
  /// `null` = không có popup → đây cũng là điều kiện để hiện nút TUNG.
  ///
  /// Lưu BUILDER (không lưu Widget instance) là bắt buộc: nếu lưu instance,
  /// mỗi lần `setState` cha truyền lại đúng object cũ nên Flutter bỏ qua
  /// rebuild con → lựa chọn đáp án mới không được vẽ lại và nút xác nhận kẹt
  /// ở trạng thái disabled.
  Widget Function()? _cardBuilder;

  /// Hiện pill "X tung được N" (giữ tới khi quân đi xong).
  bool _showRollPill = false;
  String? _pillRollerName;
  Color? _pillRollerColor;
  String? _pillRollerAsset;

  /// Người vừa tung + mặt xúc xắc (badge trên thẻ rail tới hết lượt).
  String? _lastRollerId;
  int _lastRollerFace = 0;

  /// `rollId` đã hiển thị lần cuối — so MÃ lần tung, không so mặt xúc xắc,
  /// nên hai lần tung trùng mặt vẫn hiện pill + chạy animation bình thường.
  int _lastSeenRollId = 0;

  /// Đang chờ snapshot server xác nhận chính lần tung vừa bấm — để watcher
  /// không hiện pill + lắc xúc xắc lần nữa cho đúng lần tung đó.
  bool _suppressRollEcho = false;

  /// Giai đoạn quyết định thứ tự lượt đầu trận.
  bool _prevOrderPhase = false;
  bool _showOrderSummary = false;
  Timer? _orderSummaryTimer;

  /// Đang ở chế độ toàn màn hình (web) — đồng bộ qua sự kiện `fullscreenchange`.
  bool _fullscreen = false;
  bool _fullscreenSupported = false;
  StreamSubscription<bool>? _fsSub;

  /// Khoá vào `RepaintBoundary` bàn cờ (chức năng debug xuất ảnh PNG).
  final GlobalKey _boardBoundaryKey = GlobalKey();

  /// Kích thước "ảo" của bàn (bàn luôn được FittedBox về đúng tỉ lệ này).
  static const Size _boardSize = Size(BoardGeometry.virtualWidth, BoardGeometry.virtualHeight);

  @override
  void initState() {
    super.initState();
    // WebSocket bị treo khi tab ẩn và heartbeat bị browser throttle → theo dõi
    // vòng đời để đồng bộ lại ngay khi app quay lại tiền cảnh.
    WidgetsBinding.instance.addObserver(this);
    _game = GameController(
      initialPlayers: widget.initialPlayers,
      myPlayerKey: widget.myPlayerKey,
      online: widget.isOnline,
    );
    _diceController = AnimationController(vsync: this, duration: const Duration(milliseconds: 900));
    _moveController = AnimationController(vsync: this);
    _blinkController = AnimationController(vsync: this, duration: const Duration(milliseconds: 850))
      ..repeat(reverse: true);
    _pillFadeController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 450),
      value: 1,
    );

    _game.addListener(_watchGame);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    SystemChrome.setPreferredOrientations([
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
    // Web: thử khoá ngang bằng screen.orientation.lock (thường không thành
    // công trên trình duyệt — vì vậy còn có bố cục riêng cho màn dọc).
    lockLandscape();
    _fullscreenSupported = isFullscreenSupported();
    _fsSub = fullscreenChanges().listen((full) {
      if (mounted && _fullscreen != full) {
        setState(() => _fullscreen = full);
      }
    });
    if (widget.isOnline) {
      _socket = GameSocket(roomCode: widget.roomCode);
      _socket!.events.listen(_onSocketEvent);
      _socket!.connect();
      // Lưới an toàn: poll state ván qua REST mỗi 2s. WebSocket vẫn là
      // kênh chính (tức thời), poll bảo đảm hội tụ cả khi WS rớt/chậm.
      _poll = Timer.periodic(const Duration(seconds: 2), (_) => _pollSnapshot());
      // Nhịp sống: heartbeat + đồng hồ lượt + tự tung dự phòng. Tách riêng
      // khỏi controller để bật/tắt cùng vòng đời trang.
      _clock = TurnClockDriver(
        controller: _game,
        roomCode: widget.roomCode,
        playerKey: widget.myPlayerKey ?? '',
      )..start();
      // Nạp luật thật từ server (giá trị mặc định chỉ để UI khỏi trắng lúc
      // đầu). Driver tự restart heartbeat nếu nhịp server khác mặc định.
      unawaited(
        _game.loadRules().then((_) {
          if (mounted) _clock?.updateRules(_game.rules);
        }),
      );
    }
  }

  @override
  void dispose() {
    _orderSummaryTimer?.cancel();
    _fsSub?.cancel();
    _game.removeListener(_watchGame);
    _poll?.cancel();
    _clock?.stop();
    _game.dispose();
    _roomApi.dispose();
    _socket?.dispose();
    _diceController.dispose();
    _moveController.dispose();
    _blinkController.dispose();
    _pillFadeController.dispose();
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    SystemChrome.setPreferredOrientations([
      DeviceOrientation.portraitUp,
      DeviceOrientation.portraitDown,
    ]);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // `resumed`: gửi heartbeat ngay và cho phép thử tự tung lại, vì lúc ngủ
    // máy có thể bỏ lỡ cả nhịp heartbeat lẫn lúc hết hạn lượt.
    if (state == AppLifecycleState.resumed && mounted) {
      final clock = _clock;
      if (clock != null) unawaited(clock.onResumed());
      unawaited(_pollSnapshot());
      _socket?.connect();
    }
  }

  Future<void> _pollSnapshot() async {
    final result = await _roomApi.fetchGameSnapshot(widget.roomCode);
    if (result is ApiSuccess<GameSnapshot> && mounted) {
      _game.applyGameSnapshot(result.data);
    }
  }

  Future<void> _toggleFullscreen() async {
    if (!_fullscreenSupported) return;
    final ok = _fullscreen ? await exitFullscreen() : await enterFullscreen();
    if (ok && _fullscreen) await lockLandscape();
  }

  /// Bật/tắt toàn màn hình (web). Gọi trực tiếp đồng bộ trong tay handler:
  /// `requestFullscreen()` PHẢI chạy ngay trong user gesture, trước mọi await.
  Future<void> _exportDebugBoard() async {
    final boundaryContext = _boardBoundaryKey.currentContext;
    if (boundaryContext == null) return;
    final boundary = boundaryContext.findRenderObject();
    if (boundary is! RenderRepaintBoundary) return;
    final image = await boundary.toImage(pixelRatio: 2);
    final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
    image.dispose();
    if (byteData == null) return;
    final ok = await saveImageBytes(
      byteData.buffer.asUint8List(),
      'board-debug_${widget.roomCode}.png',
    );
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(ok ? 'Đã xuất ảnh debug.' : 'Không xuất được ảnh (chạy trên web mới được).'),
      ),
    );
  }

  /// Toast presence (mất kết nối / quay lại / chuyển quyền chủ phòng).
  ///
  /// `clearSnackBar` trước khi hiện để thông báo mới không bị dồn chồng, và
  /// `mounted` để không Scaffold sau khi rời trang.
  void _presenceToast(String message) {
    if (!mounted || message.trim().isEmpty) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(message),
          duration: const Duration(seconds: 3),
          behavior: SnackBarBehavior.floating,
        ),
      );
  }

  // ---------------------------------------------------------------------
  // Theo dõi state: đổi lượt, lượt mới của server (rollId), tổng kết thứ tự.
  // ---------------------------------------------------------------------
  void _watchGame() {
    if (!mounted) return;
    var dirty = false;

    // Đổi lượt → lượt mới chưa tung, bỏ nhãn "LƯỢT TIẾP" và badge người tung.
    // So cả danh tính người chơi vì sau khi xếp thứ tự, danh sách được sắp
    // lại nên `activeIndex` có thể không đổi dù đã sang người khác.
    final activeId = _game.activePlayer.id;
    if (_game.activeIndex != _prevActiveIndex || activeId != _prevActivePlayerId) {
      _prevActiveIndex = _game.activeIndex;
      _prevActivePlayerId = activeId;
      _hasRolledThisTurn = false;
      _lastRollerFace = 0;
      dirty = true;
    }

    // Người KHÁC vừa tung (broadcast/poll, rollId tăng) → mọi máy đều thấy
    // cùng kết quả: chạy animation lắc + hiện pill.
    final roller = _game.sharedRoller;
    if (_game.sharedRoll != null && _game.seenRollId > _lastSeenRollId) {
      _lastSeenRollId = _game.seenRollId;
      _lastRollerFace = _game.sharedRoll!;
      if (roller != null) {
        _lastRollerId = roller.id;
        _pillRollerName = roller.name;
        _pillRollerColor = roller.color;
        _pillRollerAsset = characterAssetOf(roller, fallbackIndex: _game.players.indexOf(roller));
      }
      // Chính mình vừa tung: [_pressRoll] đã hiện pill + chạy animation rồi,
      // chỉ cần ghi nhận rollId (server xác nhận) — không hiện lần nữa.
      final myEcho =
          _suppressRollEcho &&
          roller != null &&
          roller.id == (widget.myPlayerKey ?? _game.myPlayer.id);
      _suppressRollEcho = false;
      if (!myEcho) {
        _showRollPill = true;
        _pillFadeController.value = 1;
        if (!_diceController.isAnimating) {
          _diceController.forward(from: 0);
        }
      }
      dirty = true;
    }

    // Giai đoạn quyết định thứ tự khép lại → bảng tổng kết 2.8s rồi tự ẩn.
    // Đồng thời mở khoá "đã tung" để người đi đầu thấy lại nút TUNG (nếu chỉ
    // dựa vào đổi `activeIndex` thì trường hợp index vẫn = 0 sẽ bị kẹt).
    final phase = _game.orderPhase;
    if (!phase && _prevOrderPhase) {
      _hasRolledThisTurn = false;
      _showOrderSummary = true;
      _orderSummaryTimer?.cancel();
      _orderSummaryTimer = Timer(const Duration(milliseconds: 2800), () {
        if (mounted) setState(() => _showOrderSummary = false);
      });
      dirty = true;
    }
    _prevOrderPhase = phase;

    if (dirty) setState(() {});
  }

  void _onSocketEvent(GameSocketEvent event) {
    if (!mounted) return;
    switch (event.type) {
      case GameSocketEventType.game:
        final GameSnapshot? snapshot = event.game;
        if (snapshot == null) return;
        // Áp thẳng vào controller ở đây — KHÔNG dùng context.read: provider
        // của page tạo bên dưới State.context nên read từ đây sẽ luôn fail.
        _game.applyGameSnapshot(snapshot);
      case GameSocketEventType.room:
      case GameSocketEventType.started:
        break;
      case GameSocketEventType.presence:
        for (final message in event.messages) {
          _presenceToast(message);
        }
        // Danh sách người chơi/presence vừa đổi → lấy snapshot đầy đủ.
        unawaited(_pollSnapshot());
      case GameSocketEventType.closed:
        _handleRoomClosed(event.reason);
      case GameSocketEventType.solo:
        _game.onSolo(event.playerKey);
        _showSoloDialog(event.playerKey);
      case GameSocketEventType.finished:
        // `FINISHED` mang kèm snapshot nên winner lấy từ đó; nếu WS rớt
        // snapshot thì poll 2s vẫn đưa `status`/`winnerKey` về sau.
        final GameSnapshot? snapshot = event.game;
        if (snapshot != null) _game.applyGameSnapshot(snapshot);
        _game.onFinished(_game.winnerKey);
    }
  }

  /// Phòng bị xóa giữa ván (chủ phòng đóng, hoặc hết TTL): dừng đồng hồ trước,
  /// rồi báo và đưa về màn chính. Không báo "không tìm thấy phòng".
  Future<void> _handleRoomClosed(String? reason) async {
    _clock?.stop();
    _poll?.cancel();
    if (!mounted) return;
    final message = (reason == null || reason.trim().isEmpty) ? 'Phòng đã đóng' : reason;
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Phòng đã đóng'),
        content: Text('$message.\n\nBạn có thể tạo phòng mới hoặc vào phòng khác.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Đã hiểu'),
          ),
        ],
      ),
    );
    if (!mounted) return;
    Navigator.of(context).popUntil((route) => route.isFirst);
  }

  /// Còn một người trong trận — thông báo một lần rồi cho chơi tiếp (offline
  /// vẫn chơi được với quân của mình).
  void _showSoloDialog(String? remainingKey) {
    final mine = remainingKey == null || remainingKey == widget.myPlayerKey;
    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Chỉ còn một người'),
        content: Text(
          mine
              ? 'Bạn là người cuối cùng trong phòng. Bạn vẫn chơi tiếp với quân của mình.'
              : 'Vòng chơi giờ do một người đảm nhiệm. Bạn vẫn chơi tiếp với quân của mình.',
        ),
        actions: [
          TextButton(
            onPressed: () {
              _game.dismissSolo();
              Navigator.pop(dialogContext);
            },
            child: const Text('Chơi tiếp'),
          ),
        ],
      ),
    );
  }

  // ---------------------------------------------------------------------
  // Hành động lượt
  // ---------------------------------------------------------------------

  /// Bấm nút TUNG trong ô điều khiển.
  ///
  /// Ẩn nút NGAY trong cùng `setState` trước khi gọi server (chống bấm đúp),
  /// rồi mới chạy animation lắc và di chuyển quân.
  Future<void> _pressRoll() async {
    final game = _game;
    // Điều kiện hiển thị nút (lặp lại ở đây để không tin vào UI):
    if (!_canShowRoll(game)) return;
    // Ẩn nút trong chính lần setState này — không nháy nút sau khi bấm.
    setState(() {
      _hasRolledThisTurn = true;
    });
    // Chặn watcher hiện pill lần nữa khi snapshot server xác nhận lần tung
    // này (pill + animation đã tự hiện ngay dưới đây).
    _suppressRollEcho = true;

    final rollerId = game.myPlayer.id;
    final oldPos = game.myPlayer.position;

    _diceController.reset();
    final rollFuture = game.rollDice(playerId: rollerId);
    await _diceController.forward();
    final rollValue = await rollFuture;
    if (!mounted) return;

    // Server TỪ CHỐI (không phải lượt mình / lượt đang bận) → trả về trạng thái
    // chờ, không di chuyển quân, không lệch với server.
    if (rollValue == null) {
      _suppressRollEcho = false;
      setState(() => _hasRolledThisTurn = false);
      return;
    }

    // Pill + badge người tung (watcher cũng lo phần này, nhưng set ngay để
    // máy của người tung không chờ broadcast).
    final self = game.myPlayer;
    setState(() {
      _lastRollerId = self.id;
      _lastRollerFace = rollValue;
      _pillRollerName = self.name;
      _pillRollerColor = self.color;
      _pillRollerAsset = characterAssetOf(self, fallbackIndex: game.players.indexOf(self));
      _showRollPill = true;
      _pillFadeController.value = 1;
    });

    // Đầu trận: xúc xắc chỉ quyết định thứ tự lượt đi — quân KHÔNG di chuyển.
    // Dựa trên `lastRollMoved` (server trả về) chứ không phải cờ `orderPhase`
    // cục bộ: giai đoạn này có thể chưa kịp đồng bộ tới client, nếu đoán sai
    // thì quân chạy rồi bị kéo lại → nhân vật nhảy lung tung.
    if (!game.lastRollMoved) {
      await Future<void>.delayed(const Duration(milliseconds: 1500));
      if (mounted) setState(() => _showRollPill = false);
      return;
    }

    // Giữ kết quả xúc xắc trên màn trong lúc quân chạy từng ô để nước đi và
    // xúc xắc đồng bộ. Đích chạy là ô server xác nhận, KHÔNG dựa vào
    // `activePlayer.id`: online, ô START/⭐ làm server đổi lượt ngay nên
    // `activePlayer` đã là người kế tiếp → quân đứng yên, không thấy nước đi.
    final landing = game.landingPosition;
    if (rollValue > 0 && landing != null) {
      await _animateMove(rollerId, oldPos, landing);
    }
    if (!mounted) return;
    // Quân đã dừng → pill mờ dần rồi ẩn.
    await _pillFadeController.reverse();
    if (!mounted) return;
    setState(() => _showRollPill = false);

    _resolveTile(game);
  }

  /// Bấm "LƯỢT TIẾP" (offline) — chuyển lượt thủ công.
  void _pressNext() {
    setState(() => _hasRolledThisTurn = false);
    _game.nextTurn();
  }

  // Chạy quân từ [from] tới ô [to] (ô server xác nhận). Số bước tính LẠI từ
  // khoảng cách thật theo hướng cố định của bàn để quân LUÔN dừng đúng ô đích,
  // kể cả khi ô cũ client đang giữ đã lệch với server.
  Future<void> _animateMove(String playerId, int from, int to) async {
    final len = BoardDefinition.length;
    var steps = (from - to) % len;
    if (steps < 0) steps += len;
    if (steps == 0) return;
    _moveController.stop();
    _moveFrom = from;
    _moveSteps = steps;
    _movingPlayerId = playerId;
    if (mounted) setState(() {});
    _moveController.duration = Duration(milliseconds: 185 * steps);
    await _moveController.forward(from: 0).orCancel;
    if (mounted) setState(() => _movingPlayerId = null);
  }

  // ---------------------------------------------------------------------
  // Điều kiện + trạng thái ô điều khiển
  // ---------------------------------------------------------------------

  bool get _popupOpen => _cardBuilder != null;
  bool get _moving => _movingPlayerId != null;

  /// Điều kiện hiển thị nút TUNG — đúng theo yêu cầu:
  /// `isMyTurn && phase == waitingRoll && không đang di chuyển && không popup`.
  bool _canShowRoll(GameController game) {
    if (_popupOpen || _moving) return false;
    if (game.isRolling) return false;
    if (game.pendingAnswer) return false; // thẻ/câu hỏi đang mở trên server
    if (!game.isMyRollTurn) return false;
    if (_hasRolledThisTurn && !game.orderPhase) return false;
    return true;
  }

  /// Trạng thái hiển thị của ô điều khiển xúc xắc (5 trạng thái).
  DiceControlState _controlState(GameController game) {
    if (_popupOpen || _moving) return DiceControlState.busy;
    if (game.isRolling || _diceController.isAnimating) {
      return DiceControlState.rolling;
    }
    if (game.pendingAnswer) return DiceControlState.busy;
    if (!game.isMyRollTurn) {
      // Người KHÁC vừa tung → xem xúc xắc + mặt kết quả của họ (mọi người
      // chơi đều thấy, không chỉ người bấm nút). Chưa có kết quả nào thì mới
      // hiện icon "đang chờ".
      return _lastRollerFace != 0 ? DiceControlState.watching : DiceControlState.idle;
    }
    // Offline: đã xử lý xong ô → chờ bấm "LƯỢT TIẾP".
    if (_hasRolledThisTurn && game.canContinueTurn) {
      return DiceControlState.resolved;
    }
    if (_canShowRoll(game)) return DiceControlState.readyToRoll;
    return DiceControlState.idle;
  }

  // ---------------------------------------------------------------------
  // Thẻ trong khung giữa (Cơ hội / Thử thách / câu hỏi RIASEC)
  // ---------------------------------------------------------------------
  void _resolveTile(GameController game) {
    if (!mounted) return;
    // Ưu tiên ô ĐÍCH server xác nhận. `activePlayer.position` chỉ là dự phòng
    // cho lúc chưa có lần tung nào: online, với ô START/⭐ server đã tự đổi
    // lượt nên `activePlayer` trỏ sang người kế tiếp → đọc sai ô này sẽ mở
    // nhầm thẻ của người sau, hoặc không mở thẻ nào (ô ⭐/START không có thẻ).
    final tile = game.landingTile ?? BoardDefinition.tiles[game.activePlayer.position];
    switch (tile) {
      case RiasecTile(:final group):
        _openQuestion(game, group);
      case ChallengeTile():
        _openChallenge(game);
      case OpportunityTile():
        _openOpportunity(game);
      case StartTile():
      case StarTile():
        // START / ⭐ trang trí: không rút thẻ, chỉ dừng lại. Online server đã
        // tự đổi lượt; offline hiện nút "LƯỢT TIẾP" trong ô điều khiển.
        if (!game.online) setState(() {});
        _clearCard();
    }
  }

  void _openCard(Widget Function() builder) {
    setState(() => _cardBuilder = builder);
  }

  void _clearCard() {
    if (!mounted) return;
    if (_cardBuilder == null) return;
    setState(() => _cardBuilder = null);
  }

  void _openQuestion(GameController game, Riasec group) {
    final card = game.prepHollandCard(group);
    // Bắt buộc chọn đáp án: bắt đầu là null, nút "Xác nhận đáp án" ở trạng
    // thái disabled (Material 3, onPressed: null) cho tới khi người chơi chọn.
    String? selected;
    _openCard(
      () => _QuestionCard(
        controller: game,
        card: card,
        onChanged: (value) => setState(() => selected = value),
        selected: () => selected,
        onConfirm: () async {
          // Chốt lại lựa chọn TẠI THỜI ĐIỂM bấm: đổi đáp án sau khi bấm không
          // làm lệch đáp án đã gửi. Chặn cả trường hợp chưa chọn.
          final option = selected;
          if (option == null) return;
          final award = await game.answerCurrentTile(selectedOption: option);
          if (!mounted) return;
          _clearCard();
          _showPointAward(award);
        },
      ),
    );
  }

  void _openChallenge(GameController game) {
    final card = game.prepChallengeCard();
    _openCard(
      () => _BonusCard(
        controller: game,
        title: 'THỬ THÁCH - ${card.title}',
        body:
            '${card.task}\n\n'
            'Hoàn thành: +${card.winPoints} Điểm Chiến thắng.\n'
            'Không hoàn thành: không nhận điểm.',
        acceptLabel: 'Hoàn thành',
        onAccept: () async {
          final award = await game.answerBonusCard();
          if (!mounted) return;
          _clearCard();
          _showPointAward(award);
        },
        onSkip: () async {
          // Bỏ qua vẫn đổi lượt (online) để ván không bị kẹt.
          await game.answerBonusCard(winPoints: 0);
          if (!mounted) return;
          _clearCard();
        },
      ),
    );
  }

  void _openOpportunity(GameController game) {
    final card = game.prepBonusCard();
    _openCard(
      () => _BonusCard(
        controller: game,
        title: 'CƠ HỘI ★ - ${card.title}',
        body: '${card.effect}\n\nNhận thưởng để cộng Điểm Chiến thắng.',
        acceptLabel: 'Nhận thưởng',
        onAccept: () async {
          final award = await game.answerBonusCard();
          if (!mounted) return;
          _clearCard();
          _showPointAward(award);
        },
      ),
    );
  }

  void _showPointAward(Award award) {
    final overlay = Overlay.of(context);
    final entry = OverlayEntry(
      builder: (_) => Positioned.fill(
        child: IgnorePointer(
          child: Center(
            child: TweenAnimationBuilder<double>(
              tween: Tween(begin: 0, end: 1),
              duration: const Duration(milliseconds: 1000),
              builder: (context, value, child) => Transform.translate(
                offset: Offset(0, -70 * value),
                child: Opacity(opacity: 1 - value, child: child),
              ),
              child: Text(
                award.win ? '+${award.points} điểm chiến thắng' : '+${award.points} điểm',
                style: TextStyle(
                  fontSize: math.max(20, 30),
                  fontWeight: FontWeight.w900,
                  color: award.win ? AppColors.violet : (award.category?.color ?? AppColors.violet),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    overlay.insert(entry);
    Future<void>.delayed(const Duration(milliseconds: 1050), entry.remove);
  }

  void _showGroupInfo(GameController game, Riasec group) {
    showGeneralDialog<void>(
      context: context,
      barrierDismissible: true,
      barrierLabel: 'Đóng',
      transitionDuration: const Duration(milliseconds: 250),
      pageBuilder: (context, _, _) => GroupInfoDialog(group: group, players: game.players),
      transitionBuilder: (context, animation, _, child) => FadeTransition(
        opacity: animation,
        child: ScaleTransition(
          scale: CurvedAnimation(parent: animation, curve: Curves.easeOutBack),
          child: child,
        ),
      ),
    );
  }

  void _showPlayerDetails(Player player) {
    showDialog<void>(
      context: context,
      builder: (_) => PlayerDetailsDialog(player: player),
    );
  }

  /// Bottom sheet: luật chơi + ý nghĩa 6 nhóm RIASEC.
  void _showRulesAndLegend(GameController game) {
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (sheetContext) => SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 28),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('LUẬT CHƠI', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900)),
              const SizedBox(height: 8),
              const Text(
                '• Tung xúc xắc, di chuyển theo số ô. Đi qua ô START là hoàn '
                'thành 1 vòng cờ (53 ô).\n'
                '• Dừng ở ô RIASEC: trả lời câu hỏi Holland, nhận điểm theo '
                'nhóm phù hợp (+2/+1).\n'
                '• Ô Cơ hội (CƠ HỘI): nhận thưởng Điểm Chiến thắng.\n'
                '• Ô Thử thách (THỬ THÁCH & 4 góc): hoàn thành thử thách để '
                'nhận Điểm Chiến thắng, bỏ qua vẫn đổi lượt.\n'
                '• Ô trang trí (⭐): chỉ dừng lại, không rút thẻ.\n'
                '• Người chơi có tổng Điểm Chiến thắng & nhóm RIASEC nổi trội '
                'sẽ phản ánh định hướng nghề nghiệp.',
                style: TextStyle(fontSize: 14, height: 1.5),
              ),
              const SizedBox(height: 18),
              const Text(
                'Ý NGHĨA 6 NHÓM RIASEC',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900),
              ),
              const SizedBox(height: 8),
              for (final g in Riasec.values)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: Row(
                    children: [
                      Container(
                        width: 44,
                        height: 44,
                        decoration: BoxDecoration(
                          color: g.color.withValues(alpha: 0.25),
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: Center(
                          child: Text(
                            g.code,
                            style: TextStyle(
                              fontSize: 22,
                              fontWeight: FontWeight.w900,
                              color: g.color,
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              g.displayName,
                              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                            ),
                            Text(g.description, style: const TextStyle(fontSize: 12.5)),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              if (game.players.isNotEmpty) ...[
                const Divider(),
                const Text(
                  'ĐIỂM RIASEC',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w900),
                ),
                const SizedBox(height: 6),
                for (final p in game.players)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    child: _PlayerScoreLine(player: p),
                  ),
              ],
              const SizedBox(height: 8),
              Center(
                child: TextButton.icon(
                  onPressed: () => Navigator.pop(sheetContext),
                  icon: const Icon(Icons.close),
                  label: const Text('Đóng'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Nút thoát: hỏi xác nhận đếm ngược 5 giây, rồi chọn cách thoát.
  ///
  /// Hai kiểu thoát khác nhau về hậu quả:
  /// - **Giữ chỗ** (mặc định): không gọi `leave`, server vẫn giữ người chơi và
  ///   lượt. Quay lại lobby thấy thẻ "Vào lại trận đang chơi" để vào tiếp.
  /// - **Rời hẳn**: gọi `leave` → avatar tối, lượt bị bỏ qua, và server CHẶN
  ///   vào lại phòng đã bắt đầu nên không quay lại được nữa → xoá phiên.
  Future<void> _confirmQuit() async {
    final choice = await showDialog<_QuitChoice>(
      context: context,
      barrierDismissible: false,
      builder: (_) => const _QuitGameDialog(),
    );
    if (choice == null || !mounted) return;

    final key = widget.myPlayerKey;
    if (choice == _QuitChoice.leaveRoom && widget.isOnline && key != null) {
      await _roomApi.leave(widget.roomCode, key);
      widget.session?.clearActiveRoom();
    }
    _socket?.disconnect();
    if (mounted) {
      Navigator.of(context).popUntil((route) => route.isFirst);
    }
  }

  /// Xác nhận kết thúc ván — quyền của người giữ phòng.
  ///
  /// Server chốt kết quả; nếu quyền đã chuyển sang người khác giữa lúc bấm thì
  /// bị từ chối và mình chỉ hiện thông báo, không kéo cả phòng theo.
  Future<void> _confirmFinish() async {
    final choice = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Kết thúc ván?'),
        content: const Text('Ván sẽ dừng lại và không chơi tiếp được nữa.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Tiếp tục'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Kết thúc'),
          ),
        ],
      ),
    );
    if (choice != true || !mounted) return;
    final ok = await _game.finishGame(_game.winnerKey);
    if (!mounted || !ok) {
      if (mounted) {
        ScaffoldMessenger.of(context)
          ..hideCurrentSnackBar()
          ..showSnackBar(
            const SnackBar(
              content: Text('Không kết thúc được — quyền quản lý phòng đã đổi tay.'),
            ),
          );
      }
    }
  }

  /// Màn báo mất chỗ: không chơi tiếp được nữa, chỉ có đường về lobby.
  Widget _buildLostSeat() {
    return Scaffold(
      backgroundColor: const Color(0xFFF8EFF6),
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.person_off_outlined, size: 56),
                const SizedBox(height: 16),
                const Text(
                  'Bạn không còn trong phòng này',
                  style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 8),
                Text(
                  'Phòng ${widget.roomCode} không còn chỗ của bạn — có thể bạn đã '
                  'rời phòng trước đó. Hãy vào phòng khác hoặc tạo phòng mới.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.grey.shade700),
                ),
                const SizedBox(height: 20),
                FilledButton.icon(
                  onPressed: () {
                    widget.session?.clearActiveRoom();
                    Navigator.of(context).popUntil((route) => route.isFirst);
                  },
                  icon: const Icon(Icons.home_outlined),
                  label: const Text('Về màn hình chính'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  // ---------------------------------------------------------------------
  // Bố cục
  // ---------------------------------------------------------------------

  /// Bề rộng rail: desktop 140–170px, mobile ngang 52–60px.
  static double _railWidth(double w, double h) {
    final shortSide = math.min(w, h);
    if (shortSide >= 700) return 150;
    if (shortSide >= 480) return 64;
    return 54;
  }

  /// Màn nhỏ (mobile ngang) → thẻ người chơi thu gọn thành avatar tròn.
  static bool _compactRails(double w, double h) => math.min(w, h) < 700;

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider.value(
      value: _game,
      child: Consumer<GameController>(
        builder: (context, game, _) {
          // Chỗ đã mất (đã rời phòng trước đó, hoặc vào lại phòng không còn
          // mình): báo rõ + đường về, thay vì hiện bàn chơi như thể đang là
          // nhân vật khác và mọi nút đều bị server từ chối.
          if (game.lostSeat) return _buildLostSeat();
          return PopScope(
            canPop: false,
            onPopInvokedWithResult: (didPop, _) {
              if (!didPop) _confirmQuit();
            },
            child: Scaffold(
              backgroundColor: const Color(0xFFF8EFF6),
              body: SafeArea(
                child: LayoutBuilder(
                  builder: (context, constraints) {
                    final w = constraints.maxWidth;
                    final h = constraints.maxHeight;
                    final portrait = w < h;
                    return portrait
                        ? _buildPortrait(context, game, w, h)
                        : _buildLandscape(context, game, w, h);
                  },
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  /// Ngang (điện thoại ngang + desktop): railTrái | Bàn cờ | railPhải.
  Widget _buildLandscape(BuildContext context, GameController game, double w, double h) {
    final railW = _railWidth(w, h);
    final compact = _compactRails(w, h);
    return Row(
      children: [
        SizedBox(
          width: railW,
          child: GameRail(
            allPlayers: game.players,
            activeIndex: game.activeIndex,
            myPlayerKey: widget.myPlayerKey ?? game.myPlayer.id,
            side: RailSide.left,
            compact: compact,
            railWidth: railW,
            onTapPlayer: _showPlayerDetails,
            diceBadgeFor: (p) => p.id == _lastRollerId ? _lastRollerFace : 0,
            leading: [
              RailIconButton(icon: Icons.logout, tooltip: 'Rời trận', onPressed: _confirmQuit),
              // Người giữ quyền phòng mới thấy nút kết thúc; server tự xác
              // thực lại khi nhận request nên bấm nhầm cũng vô hại.
              if (game.amHost && game.roomStatus == RoomStatus.playing)
                RailIconButton(
                  icon: Icons.flag_outlined,
                  tooltip: 'Kết thúc ván',
                  onPressed: _confirmFinish,
                ),
              if (BoardGeometry.debugHitbox)
                RailIconButton(
                  icon: Icons.photo_camera_outlined,
                  tooltip: 'Xuất ảnh hitbox (PNG)',
                  onPressed: _exportDebugBoard,
                ),
            ],
            trailing: [
              RoomCodeChipVertical(code: widget.roomCode),
              const SizedBox(height: 8),
              Flexible(
                child: SingleChildScrollView(
                  child: RiasecGridChips(onTap: (g) => _showGroupInfo(game, g)),
                ),
              ),
            ],
          ),
        ),
        // ===== BÀN CỜ =====
        Expanded(child: _buildBoardStage(context, game)),
        // ===== RAIL PHẢI =====
        SizedBox(
          width: railW,
          child: GameRail(
            allPlayers: game.players,
            activeIndex: game.activeIndex,
            myPlayerKey: widget.myPlayerKey ?? game.myPlayer.id,
            side: RailSide.right,
            compact: compact,
            railWidth: railW,
            onTapPlayer: _showPlayerDetails,
            diceBadgeFor: (p) => p.id == _lastRollerId ? _lastRollerFace : 0,
            leading: [
              if (_fullscreenSupported)
                RailIconButton(
                  icon: _fullscreen ? Icons.fullscreen_exit : Icons.fullscreen,
                  tooltip: _fullscreen ? 'Thoát toàn màn hình' : 'Toàn màn hình',
                  onPressed: _toggleFullscreen,
                ),
              RailIconButton(
                icon: Icons.menu_book_outlined,
                tooltip: 'Luật chơi & Ý nghĩa RIASEC',
                onPressed: () => _showRulesAndLegend(game),
              ),
            ],
          ),
        ),
      ],
    );
  }

  /// Màn dọc (web): bàn cờ ở trên, thẻ người chơi nằm MỘT HÀNG NGANG bên
  /// dưới — không thẻ nào nằm trên bàn.
  Widget _buildPortrait(BuildContext context, GameController game, double w, double h) {
    return Column(
      children: [
        // Hàng nút phụ (trên bàn, ngoài bàn cờ).
        SizedBox(
          height: 56,
          child: Row(
            children: [
              RailIconButton(icon: Icons.logout, tooltip: 'Rời trận', onPressed: _confirmQuit),
              const SizedBox(width: 6),
              RoomCodeChipVertical(code: widget.roomCode),
              const Spacer(),
              if (_fullscreenSupported)
                RailIconButton(
                  icon: _fullscreen ? Icons.fullscreen_exit : Icons.fullscreen,
                  tooltip: _fullscreen ? 'Thoát toàn màn hình' : 'Toàn màn hình',
                  onPressed: _toggleFullscreen,
                ),
              const SizedBox(width: 6),
              RailIconButton(
                icon: Icons.menu_book_outlined,
                tooltip: 'Luật chơi & Ý nghĩa RIASEC',
                onPressed: () => _showRulesAndLegend(game),
              ),
            ],
          ),
        ),
        // ===== BÀN CỜ =====
        Expanded(child: _buildBoardStage(context, game)),
        // ===== HÀNG THẺ NGƯỜI CHƠI (dưới bàn) =====
        Container(
          height: 66,
          color: Colors.white.withValues(alpha: 0.6),
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            itemCount: game.players.length,
            separatorBuilder: (_, _) => const SizedBox(width: 6),
            itemBuilder: (context, i) {
              final p = game.players[i];
              return SizedBox(
                width: 148,
                child: PlayerRailCard(
                  player: p,
                  active: game.activeIndex == i,
                  isMe: p.id == (widget.myPlayerKey ?? game.myPlayer.id),
                  onTap: _showPlayerDetails,
                  asset: characterAssetOf(p, fallbackIndex: i),
                  diceBadge: p.id == _lastRollerId ? _lastRollerFace : 0,
                  layout: PlayerCardLayout.row,
                ),
              );
            },
          ),
        ),
        // ===== DẢI RIASEC RIÊNG (1 hàng, dưới hàng thẻ) =====
        Container(
          height: 42,
          color: Colors.white.withValues(alpha: 0.6),
          padding: const EdgeInsets.fromLTRB(6, 2, 6, 6),
          child: RiasecStrip(onTap: (g) => _showGroupInfo(game, g)),
        ),
      ],
    );
  }

  /// Bàn cờ: FittedBox giữ tỉ lệ, chứa ảnh + highlight + ô điều khiển + quân +
  /// lớp phụ khung giữa (pill kết quả, thẻ Cơ hội/Thử thách).
  Widget _buildBoardStage(BuildContext context, GameController game) {
    return Center(
      child: FittedBox(
        fit: BoxFit.contain,
        // Kích thước ảo cùng tỉ lệ ảnh gốc — mọi toạ độ % bên trong (ô,
        // quân, ô điều khiển) và chữ đều co giãn đồng nhất theo màn hình.
        child: SizedBox(
          width: BoardGeometry.virtualWidth,
          height: BoardGeometry.virtualHeight,
          child: AnimatedBuilder(
            animation: Listenable.merge([_moveController, _pillFadeController]),
            builder: (context, _) => GameBoard(
              controller: game,
              movement: _movingPlayerId == null
                  ? null
                  : MovementAnim(
                      playerId: _movingPlayerId!,
                      from: _moveFrom,
                      anim: _moveController,
                      steps: _moveSteps,
                    ),
              boundaryKey: _boardBoundaryKey,
              // Ô ĐIỀU KHIỂN XÚC XẮC — dải mũi tên giữa START và ô 52.
              controlZone: DiceControlZone(
                boardSize: _boardSize,
                state: _controlState(game),
                blink: _blinkController,
                diceAnimation: _diceController,
                diceFace: _lastRollerFace,
                showRollButton: _canShowRoll(game),
                showNextButton: _hasRolledThisTurn && game.canContinueTurn,
                waitingName: game.activePlayer.name,
                rollLabel: game.orderPhase ? 'TUNG ĐỊNH THỨ TỰ' : 'TUNG XÚC XẮC',
                onRoll: _pressRoll,
                onNext: _pressNext,
              ),
              // Lớp phụ khung giữa: thẻ bài + pill kết quả + bảng thứ tự.
              topOverlay: _buildBoardOverlay(game),
            ),
          ),
        ),
      ),
    );
  }

  /// Lớp phụ TRÊN CÙNG của bàn — chỉ nằm trong khung giữa (8–92% x, 17–83%),
  /// không chạm ô cờ nào.
  Widget _buildBoardOverlay(GameController game) {
    final layers = <Widget>[];

    // 1. Pill kết quả tung ("X tung được N") ở giữa khung bàn.
    if (_showRollPill && (_pillRollerName ?? '').isNotEmpty) {
      layers.add(
        BoardCenterFrame(
          decorated: false,
          child: AnimatedBuilder(
            animation: _pillFadeController,
            builder: (context, _) => RollResultPill(
              playerName: _pillRollerName!,
              value: _lastRollerFace == 0 ? 1 : _lastRollerFace,
              avatarAsset: _pillRollerAsset,
              avatarColor: _pillRollerColor,
              fade: _pillFadeController.value,
            ),
          ),
        ),
      );
    }

    // 2. Bảng thứ tự lượt đầu trận.
    if (_showOrderSummary) {
      layers.add(BoardCenterFrame(child: _OrderSummaryOverlay(players: game.players)));
    } else if (game.orderPhase) {
      layers.add(
        BoardCenterFrame(decorated: false, child: _OrderPhaseBanner(players: game.players)),
      );
    }

    // 3. Thẻ Cơ hội / Thử thách / câu hỏi — nằm trong khung giữa, cuộn
    //    trong khung, các lựa chọn giữ ngang. Chặn chạm ra ngoài khung.
    if (_cardBuilder != null) {
      // Lớp chặn chạm xuống bàn cờ PHẢI được thêm TRƯỚC thẻ: phần tử cuối
      // cùng của `Stack` được vẽ đè lên VÀ nhận chạm trước. Nếu thêm sau thẻ
      // thì lớp này phủ kín cả thẻ, nuốt mọi cú chạm → không chọn được đáp án,
      // không bấm được nút xác nhận, không bấm được nút của thẻ Cơ hội/Thử thách.
      layers.add(
        Positioned.fill(
          child: GestureDetector(behavior: HitTestBehavior.opaque, onTap: () {}),
        ),
      );
      // Thẻ nằm TRÊN lớp chặn nên mọi nút bên trong bấm được bình thường.
      layers.add(BoardCenterFrame(scrollable: true, child: _cardBuilder!()));
    }

    return Stack(children: layers);
  }
}

// -------------------------------------------------------------------------
// Thẻ trong khung giữa
// -------------------------------------------------------------------------

/// Thẻ câu hỏi RIASEC: nội dung cuộn được, 3 đáp án giữ NGUYÊN ngang.
class _QuestionCard extends StatefulWidget {
  const _QuestionCard({
    required this.controller,
    required this.card,
    required this.selected,
    required this.onChanged,
    required this.onConfirm,
  });

  final GameController controller;

  final HollandCard card;

  /// Trả về nhãn đáp án đang chọn ('A' | 'B' | 'C'), hoặc null khi người chơi
  /// CHƯA chọn — dùng để khoá nút xác nhận.
  final String? Function() selected;
  final ValueChanged<String> onChanged;

  /// Chỉ được gọi khi [selected] khác null (nút đã bị disable nếu chưa chọn).
  final Future<void> Function() onConfirm;

  @override
  State<_QuestionCard> createState() => _QuestionCardState();
}

class _QuestionCardState extends State<_QuestionCard> {
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final group = widget.card.group;
    final selected = widget.selected();
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
              decoration: BoxDecoration(
                color: group.color,
                borderRadius: BorderRadius.circular(999),
              ),
              child: Text(
                '${widget.card.id} · ${group.code}',
                style: const TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.w900,
                  fontSize: 12,
                ),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                group.displayName,
                style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 14),
              ),
            ),
            // Vòng đếm giờ lượt ngay trong "ô chọn đáp án": hiện khi đồng hồ
            // server đang chạy, đếm theo `turnDeadlineAt` đã hiệu chỉnh đồng hồ.
            _CardTurnCountdown(controller: widget.controller),
          ],
        ),
        const SizedBox(height: 10),
        Text(
          widget.card.question,
          style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14.5),
        ),
        const SizedBox(height: 12),
        // 3 đáp án LUÔN nằm ngang (Wrap: đủ chỗ thì 1 hàng, thiếu thì xuống
        // hàng mới nhưng vẫn giữ bố cục ngang).
        _OptionRow(options: widget.card.options, selected: selected, onSelect: widget.onChanged),
        const SizedBox(height: 12),
        Row(
          children: [
            // Nói rõ vì sao nút bị khoá (theo UX disabled sẵn có của app).
            if (selected == null)
              const Expanded(
                child: Text(
                  'Chọn một đáp án để xác nhận.',
                  style: TextStyle(fontSize: 12, fontStyle: FontStyle.italic),
                ),
              )
            else
              const Spacer(),
            const SizedBox(width: 8),
            FilledButton(
              // Chưa chọn HOẶC đang gửi → khoá; Material 3 tự vẽ style disabled.
              onPressed: (_busy || selected == null)
                  ? null
                  : () {
                      if (_busy) return; // chặn double-tap trong cùng frame
                      setState(() => _busy = true);
                      widget.onConfirm();
                    },
              child: _busy
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Text('Xác nhận đáp án'),
            ),
          ],
        ),
      ],
    );
  }
}

/// Thẻ Cơ hội / Thử thách: nội dung cuộn, hành động giữ ngang.
class _BonusCard extends StatelessWidget {
  const _BonusCard({
    required this.controller,
    required this.title,
    required this.body,
    required this.acceptLabel,
    required this.onAccept,
    this.onSkip,
  });

  final GameController controller;

  final String title;
  final String body;
  final String acceptLabel;
  final Future<void> Function() onAccept;
  final Future<void> Function()? onSkip;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(title, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w900)),
            ),
            // Vòng đếm ngay trong "ô làm thử thách" — hiện khi đồng hồ server
            // đang chạy (người chơi cần biết còn bao lâu).
            _CardTurnCountdown(controller: controller),
          ],
        ),
        const SizedBox(height: 10),
        Text(body, style: const TextStyle(fontSize: 14, height: 1.4)),
        const SizedBox(height: 14),
        // Các nút giữ ngang.
        Row(
          mainAxisAlignment: MainAxisAlignment.end,
          children: [
            if (onSkip != null) ...[
              TextButton(onPressed: onSkip, child: const Text('Bỏ qua')),
              const SizedBox(width: 8),
            ],
            FilledButton(onPressed: onAccept, child: Text(acceptLabel)),
          ],
        ),
      ],
    );
  }
}

/// Vòng đếm giờ lượt đặt ngay trong thẻ câu hỏi / thử thách (thay vì dưới
/// ô xúc xắc). Lắng nghe [GameController] — driver báo `onTick` mỗi 250ms nên
/// con số chạy theo đúng đồng hồ server, không cần timer riêng.
///
/// Ẩn khi đồng hồ không chạy (offline, chưa có lượt, phòng đã đóng/kết thúc).
/// Chỉ hiển thị — server vẫn quyết định lượt.
class _CardTurnCountdown extends StatelessWidget {
  const _CardTurnCountdown({required this.controller});

  final GameController controller;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        final seconds = controller.clockRunning ? controller.turnRemaining.inSeconds : null;
        if (seconds == null) return const SizedBox.shrink();
        final color = switch (seconds) {
          <= 3 => AppColors.player1,
          <= 8 => AppColors.gold,
          _ => AppColors.violet,
        };
        return Semantics(
          label: 'Còn $seconds giây',
          excludeSemantics: true,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
            decoration: BoxDecoration(
              color: color.withValues(alpha: 0.18),
              borderRadius: BorderRadius.circular(999),
              border: Border.all(color: color, width: 1.5),
            ),
            child: Text(
              '${seconds < 0 ? 0 : seconds}s',
              style: TextStyle(
                color: color,
                fontWeight: FontWeight.bold,
                fontSize: 12,
                height: 1,
              ),
            ),
          ),
        );
      },
    );
  }
}

/// 3 đáp án RIASEC xếp ngang, các ô đều nhau.
class _OptionRow extends StatelessWidget {
  const _OptionRow({required this.options, required this.selected, required this.onSelect});

  final List<HollandOption> options;

  /// Nhãn đang chọn, null khi chưa chọn đáp án nào.
  final String? selected;
  final ValueChanged<String> onSelect;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, c) {
        final per = ((c.maxWidth - 20) / options.length).clamp(120.0, 240.0);
        return Wrap(
          spacing: 10,
          runSpacing: 10,
          alignment: WrapAlignment.center,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            for (final option in options)
              SizedBox(
                width: per,
                child: _OptionCard(
                  option: option,
                  selected: option.label == selected,
                  onTap: () => onSelect(option.label),
                ),
              ),
          ],
        );
      },
    );
  }
}

class _OptionCard extends StatelessWidget {
  const _OptionCard({required this.option, required this.selected, required this.onTap});

  final HollandOption option;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = option.category.color;
    return Material(
      color: selected ? color.withValues(alpha: 0.18) : Colors.transparent,
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Container(
          width: double.infinity,
          constraints: const BoxConstraints(minHeight: 96),
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            border: Border.all(color: selected ? color : Colors.black26, width: selected ? 2 : 1),
            borderRadius: BorderRadius.circular(12),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(
                option.label,
                style: TextStyle(color: color, fontWeight: FontWeight.w900, fontSize: 20),
              ),
              const SizedBox(height: 4),
              Text(
                '${option.text} (+${option.points}${option.category.code})',
                style: const TextStyle(fontSize: 12.5, height: 1.3),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Một dòng điểm RIASEC + Điểm chiến thắng (bottom-sheet luật chơi).
class _PlayerScoreLine extends StatelessWidget {
  const _PlayerScoreLine({required this.player});

  final Player player;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        CircleAvatar(
          radius: 13,
          backgroundColor: player.color,
          child: player.name.isNotEmpty
              ? Text(player.name[0], style: const TextStyle(color: Colors.white, fontSize: 12))
              : null,
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            player.name,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
          ),
        ),
        for (final e in Riasec.values)
          Container(
            width: 30,
            alignment: Alignment.center,
            margin: const EdgeInsets.symmetric(horizontal: 1),
            decoration: BoxDecoration(
              color: e.color.withValues(alpha: 0.18),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Text(
              '${player.scores[e] ?? 0}',
              style: TextStyle(fontSize: 11.5, fontWeight: FontWeight.w800, color: e.color),
            ),
          ),
        const SizedBox(width: 8),
        Text(
          '🏆 ${player.winPoints}',
          style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w800),
        ),
      ],
    );
  }
}

/// Banner giai đoạn quyết định thứ tự lượt đầu trận (trong khung giữa).
class _OrderPhaseBanner extends StatelessWidget {
  const _OrderPhaseBanner({required this.players});

  final List<Player> players;

  @override
  Widget build(BuildContext context) {
    final rolled = [
      for (final p in players)
        if (!p.left && p.orderRolled) p,
    ];
    return Material(
      color: AppColors.violet,
      borderRadius: BorderRadius.circular(20),
      elevation: 3,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.casino, color: Colors.white, size: 20),
            const SizedBox(width: 8),
            Flexible(
              child: Text(
                'Quyết định thứ tự lượt đi — đã tung ${rolled.length}/${players.length}',
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 13,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Bảng tổng kết thứ tự lượt đi (hiện khi giai đoạn quyết định thứ tự khép
/// lại), nằm trong khung giữa.
class _OrderSummaryOverlay extends StatelessWidget {
  const _OrderSummaryOverlay({required this.players});

  final List<Player> players;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const Text(
          'THỨ TỰ LƯỢT ĐI',
          style: TextStyle(fontSize: 17, fontWeight: FontWeight.w900, letterSpacing: 2),
        ),
        const SizedBox(height: 10),
        for (var i = 0; i < players.length; i++)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 3),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                SizedBox(
                  width: 26,
                  child: Text(
                    '${i + 1}.',
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w900,
                      color: AppColors.violet,
                    ),
                  ),
                ),
                Container(
                  width: 18,
                  height: 18,
                  decoration: BoxDecoration(shape: BoxShape.circle, color: players[i].color),
                ),
                const SizedBox(width: 8),
                ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 160),
                  child: Text(
                    players[i].name,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
                  ),
                ),
                const Icon(Icons.casino, size: 17, color: AppColors.violet),
                const SizedBox(width: 4),
                Text(
                  '${players[i].orderDice}',
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w900),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

/// Cách thoát khỏi trận.
enum _QuitChoice { keepSeat, leaveRoom }

/// Dialog rời trận: cảnh báo với đồng hồ đếm ngược 5 giây, chọn giữ chỗ hay
/// rời hẳn.
class _QuitGameDialog extends StatefulWidget {
  const _QuitGameDialog();

  @override
  State<_QuitGameDialog> createState() => _QuitGameDialogState();
}

class _QuitGameDialogState extends State<_QuitGameDialog> {
  static const int _countdown = 5;

  int _remaining = _countdown;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      if (_remaining <= 1) {
        _timer?.cancel();
        setState(() => _remaining = 0);
      } else {
        setState(() => _remaining--);
      }
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final armed = _remaining <= 0;
    return AlertDialog(
      title: const Text('Rời trận đấu?'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Chọn cách thoát. Nếu chỉ muốn tạm đi (điện thoại hết pin, đóng '
            'tab) thì chọn "Tạm thoát" — bạn vẫn giữ chỗ và vào lại được ván '
            'đang chơi.',
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              const Icon(Icons.timer_outlined, color: AppColors.violet),
              const SizedBox(width: 8),
              Text(
                armed
                    ? 'Bạn có thể xác nhận thoát ngay bây giờ.'
                    : 'Còn $_remaining giây nữa mới cho phép xác nhận...',
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
            ],
          ),
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Ở lại')),
        FilledButton.tonal(
          onPressed: armed ? () => Navigator.pop(context, _QuitChoice.leaveRoom) : null,
          child: const Text('Rời phòng hẳn'),
        ),
        FilledButton(
          onPressed: armed ? () => Navigator.pop(context, _QuitChoice.keepSeat) : null,
          child: const Text('Tạm thoát'),
        ),
      ],
    );
  }
}
