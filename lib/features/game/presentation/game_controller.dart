import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';

import '../../../core/theme/app_colors.dart';
import '../data/career_cards.dart';
import '../data/game_api.dart';
import '../data/game_socket.dart';
import '../domain/board.dart';
import '../domain/game_rules.dart';
import '../domain/game_room.dart';
import '../domain/player.dart';
import '../domain/riasec.dart';

/// Controller duy nhất sở hữu state của ván chơi.
/// - Không chứa BuildContext / Navigator / Dialog (UI lo).
/// - Nhận `GameApi` + `Random` qua constructor → test được.
/// - `online` = chơi đa người qua server: server quyết định lượt đi,
///   client chỉ áp `applyGameSnapshot`; lệch thì không cho roll.
/// - Huỷ `Timer` + close `GameApi` trong `dispose` (code cũ leak).
class GameController extends ChangeNotifier {
  GameController({
    GameApi? api,
    Random? random,
    List<Player>? initialPlayers,
    this.myPlayerKey,
    this.online = false,
  }) : _api = api ?? HttpGameApi(),
       _ownsApi = api == null,
       _random = random ?? Random.secure(),
       _players = List<Player>.unmodifiable(
         initialPlayers ??
             const [
               Player(id: 'p1', name: 'Bạn', color: AppColors.player1),
               Player(id: 'p2', name: 'Minh Anh', color: AppColors.player2),
               Player(id: 'p3', name: 'Gia Huy', color: AppColors.player3),
               Player(id: 'p4', name: 'Bảo Ngọc', color: AppColors.player4),
             ],
       ) {
    _reshuffleHolland();
    _bonusDeck = List<OpportunityCard>.of(kOpportunityCards)..shuffle(_random);
    _challengeDeck = List<ChallengeCard>.of(kChallengeCards)..shuffle(_random);
  }

  void _reshuffleHolland() {
    final decks = <Riasec, List<HollandCard>>{};
    for (final group in Riasec.values) {
      decks[group] = kHollandCards.where((c) => c.group == group).toList()..shuffle(_random);
    }
    _hollandDecks = decks;
  }

  /// Rút lá bài Holland của nhóm [group] cho lượt trả lời hiện tại.
  HollandCard prepHollandCard(Riasec group) {
    var deck = _hollandDecks[group];
    if (deck == null || deck.isEmpty) {
      _reshuffleHolland();
      deck = _hollandDecks[group];
    }
    _currentCard = deck!.removeLast();
    _hollandDecks[group] = deck;
    notifyListeners();
    return _currentCard!;
  }

  /// Rút 1 lá Cơ hội (ô CHANCE).
  OpportunityCard prepBonusCard() {
    if (_bonusDeck.isEmpty) {
      _bonusDeck = List<OpportunityCard>.of(kOpportunityCards)..shuffle(_random);
    }
    _currentBonus = _bonusDeck.removeLast();
    return _currentBonus!;
  }

  /// Rút 1 lá Thử thách (ô CHALLENGE).
  ChallengeCard prepChallengeCard() {
    if (_challengeDeck.isEmpty) {
      _challengeDeck = List<ChallengeCard>.of(kChallengeCards)..shuffle(_random);
    }
    _currentChallenge = _challengeDeck.removeLast();
    return _currentChallenge!;
  }

  final GameApi _api;
  final bool _ownsApi;
  final Random _random;

  /// Bộ bài Holland chia theo nhóm — bốc ngẫu nhiên mỗi lần tới ô RIASEC.
  /// Lá bài chỉ là dữ liệu client (câu hỏi + ánh xạ đáp án → nhóm/điểm),
  /// server vẫn là nguồn chân lý về điểm vì nhận `targetCategory` + `points`.
  Map<Riasec, List<HollandCard>> _hollandDecks = {};
  late List<OpportunityCard> _bonusDeck;
  late List<ChallengeCard> _challengeDeck;

  /// Lá bài đang hiển thị cho lượt trả lời hiện tại.
  HollandCard? _currentCard;
  OpportunityCard? _currentBonus;
  ChallengeCard? _currentChallenge;

  /// Chơi đa người (server điều khiển lượt, nút "Lượt tiếp" bị tắt).
  final bool online;

  /// Chìa khoá người chơi của thiết bị này (host-XX hoặc guest-XX-n).
  final String? myPlayerKey;

  /// Mã phòng — cần cho heartbeat và tự tung dự phòng.
  String? roomCode;

  /// Cấu hình luật do server trả; heartbeat đầu tiên sẽ ghi đè giá trị dự phòng.
  GameRules _rules = GameRules.fallback;

  /// Đồng hồ server: vòng đếm suy ra từ deadline, không đếm lùi cục bộ.
  final ServerClock _clock = ServerClock();

  /// Lượt đang mở (do server đặt deadline một lần duy nhất).
  TurnState _turn = TurnState.empty;

  /// Lần tung vừa rồi do hết giờ — hiện pill "tự động".
  bool _lastRollAuto = false;

  /// Lượt vừa bị bỏ qua vì người chơi vắng mặt.
  bool _turnSkipped = false;

  /// Số người đang online; 0 = cả bàn offline → dừng đồng hồ.
  int _onlineCount = 0;

  /// Phòng đã bị chủ phòng đóng hoặc hết TTL → UI phải dừng mọi timer.
  bool _roomClosed = false;
  String? _roomClosedReason;

  /// Người còn lại khi phòng chỉ còn một mình (đề nghị chơi tiếp / kết thúc).
  String? _soloPlayerKey;

  /// Ván đã kết thúc.
  RoomStatus _roomStatus = RoomStatus.waiting;
  String? _winnerKey;

  /// Quyền điều khiển phòng — chỉ để bật nút; server vẫn tự kiểm tra.
  String? _ownerKey;
  String? _actingHostKey;

  String? get ownerKey => _ownerKey;
  String? get actingHostKey => _actingHostKey;

  GameRules get rules => _rules;
  TurnState get turn => _turn;
  bool get lastRollAuto => _lastRollAuto;
  bool get turnSkipped => _turnSkipped;
  int get onlineCount => _onlineCount;
  bool get roomClosed => _roomClosed;
  String? get roomClosedReason => _roomClosedReason;
  String? get soloPlayerKey => _soloPlayerKey;
  RoomStatus get roomStatus => _roomStatus;
  String? get winnerKey => _winnerKey;

  /// Đồng hồ có đang dùng giờ server không.
  bool get clockSynced => _clock.isSynced && _clock.driftPlausible;

  /// Còn bao lâu nữa hết lượt. Âm = đã quá hạn.
  Duration get turnRemaining => _clock.remaining(_turn.deadlineAt);

  /// Đồng hồ có nên chạy không: có lượt, còn ai online, và phòng chưa đóng.
  /// Cả bàn offline thì không đếm — không ai nhìn, và lượt sẽ được xử lý khi ai
  /// đó quay lại.
  bool get clockRunning {
    if (!online) return false;
    if (_roomClosed || _roomStatus == RoomStatus.finished) return false;
    if (!_turn.isActive) return false;
    return _onlineCount == 0 || !_clock.isSynced ? _onlineCount > 0 : true;
  }

  /// Vòng đếm phần trăm (0..1) cho vòng tròn quanh nút tung.
  double get turnProgress {
    if (!_turn.isActive || !clockRunning) return 0;
    final total = _turn.deadlineAt!.difference(_turnStartedAt).inMilliseconds;
    if (total <= 0) return 1;
    final left = turnRemaining.inMilliseconds;
    return (1 - left / total).clamp(0.0, 1.0);
  }

  /// Lúc lượt hiện tại bắt đầu (đồng hồ server) — mốc để vẽ vòng tròn.
  DateTime _turnStartedAt = DateTime.now();

  List<Player> _players;
  List<Player> get players => _players;

  int _activeIndex = 0;
  int get activeIndex => _activeIndex;
  Player get activePlayer => _players[_activeIndex];

  /// Đang trong giai đoạn đầu trận: mỗi người tung 1 xúc xắc quyết định thứ
  /// tự lượt (ai cao đi trước), chưa có lượt di chuyển thật.
  bool _orderPhase = false;
  bool get orderPhase => _orderPhase;

  /// Có phải lượt của mình không. Offline: luôn là mình.
  bool get myTurn => !online || myPlayerKey == null || activePlayer.id == myPlayerKey;

  /// Người chơi đứng đầu trên thiết bị này (key của mình); online-offline
  /// khi chưa có key (4 người local) sẽ là người chơi đang tới lượt.
  Player get myPlayer {
    for (final p in _players) {
      if (p.id == myPlayerKey) return p;
    }
    return activePlayer;
  }

  /// Online mà [myPlayerKey] không còn dùng được nữa = chỗ đã mất:
  /// - không có trong danh sách phòng (đã xoá phòng), hoặc
  /// - còn record nhưng đã đánh dấu rời (`left`) — server đã bỏ qua lượt và
  ///   chặn vào lại phòng đã bắt đầu, nên không thể chơi tiếp.
  ///
  /// Không báo thì [myPlayer] rơi về `activePlayer` và UI tưởng mình đang chơi
  /// nhân vật của người khác, trong khi mọi nút bấm đều bị server từ chối.
  bool get lostSeat {
    if (!online || myPlayerKey == null) return false;
    for (final p in _players) {
      if (p.id == myPlayerKey) return p.left;
    }
    return true;
  }

  /// Được phép tung ngay bây giờ không: tới lượt mình thì hiện thanh tung.
  /// Cả giai đoạn quyết định thứ tự LẪN lượt thường đều theo lượt (server xoay
  /// từng người một), nên chỉ người đang tới lượt mới thấy nút — tránh nhiều
  /// người chen nhau tung cùng lúc ở đầu trận.
  bool get isMyRollTurn {
    if (!online || myPlayerKey == null) return true;
    final me = myPlayer;
    if (me.left) return false;
    return activePlayer.id == myPlayerKey;
  }

  /// Cho phép ấn nút tung xúc xắc.
  bool get canRoll => !_isRolling && isMyRollTurn;

  int? _diceResult;
  int? get diceResult => _diceResult;

  bool _isRolling = false;
  bool get isRolling => _isRolling;

  /// Lần tung vừa rồi có làm quân DI CHUYỂN không. `false` = lần tung quyết
  /// định thứ tự đầu trận (quân đứng yên) → UI bỏ qua animation chạy quân.
  /// Giá trị lấy từ cờ `moved` của server, KHÔNG đoán theo giai đoạn local.
  bool _lastRollMoved = true;
  bool get lastRollMoved => _lastRollMoved;

  /// Server đang chờ người chơi trả lời ô đích (chặn tung tiếp).
  bool _pendingAnswer = false;
  bool get pendingAnswer => _pendingAnswer;

  /// Ô ĐÍCH của lần tung vừa rồi (vị trí server xác nhận) và người vừa
  /// tới đó. UI mở thẻ theo ô này, KHÔNG theo `activePlayer.position`:
  /// online server có thể đã đổi lượt (ô START/⭐) trước khi HTTP response
  /// về tới client, lúc đó `activePlayer` là người kế tiếp.
  String? _landingPlayerId;
  int? _landingPosition;

  /// Ô đích để mở thẻ; null = chưa có lần tung nào (vào phòng giữa ván).
  /// Chỉ dùng được khi [_landingPlayerId] là người đang trả lời.
  int? get landingPosition => _landingPlayerId == null ? null : _landingPosition;

  /// Thẻ cần mở cho lần tung vừa rồi, hoặc null nếu lượt đó không mở thẻ.
  BoardTile? get landingTile {
    final pos = landingPosition;
    if (pos == null || pos < 0 || pos >= BoardDefinition.tiles.length) {
      return null;
    }
    return BoardDefinition.tiles[pos];
  }

  /// `rollId` cuối cùng đã xử lý — mỗi lần tung server tăng 1, nhờ đó MỌI
  /// người chơi đều biết đó là lần tung mới (không chỉ người bấm nút).
  int _seenRollId = 0;

  /// Mã lần tung ĐÃ xử lý gần nhất. Online = `rollId` của server, offline =
  /// bộ đếm nội bộ. UI so sánh mã này (không so mặt xúc xắc) nên hai lần
  /// tung liên tiếp ra trùng mặt vẫn được nhận ra là lần tung MỚI.
  int get seenRollId => _seenRollId;

  /// Lần tung chung của bàn vừa nhận: mặt xúc xắc + ai vừa tung. Dùng hiển
  /// thị kết quả cho tất cả người chơi.
  int? _sharedRoll;
  int? get sharedRoll => _sharedRoll;
  String? _sharedRollerKey;
  String? get sharedRollerKey => _sharedRollerKey;

  /// Người chơi vừa tung (tra cứu trong [players]).
  Player? get sharedRoller {
    final key = _sharedRollerKey;
    if (key == null) return null;
    for (final p in _players) {
      if (p.id == key) return p;
    }
    return null;
  }

  /// Bản chất là người vừa tung MỘT lần trong ván, đủ để bật animation lắc.
  bool _consumeSharedRoll(int rollId) {
    if (rollId <= _seenRollId) return false;
    _seenRollId = rollId;
    return true;
  }

  Riasec? _highlightedCategory;
  Riasec? get highlightedCategory => _highlightedCategory;

  Timer? _highlightTimer;

  /// API dùng chung cho heartbeat và tự tung dự phòng. Offline thì null.
  GameApi? get api => online ? _api : null;

  /// Lượt này có phải của chính mình — dùng `turnPlayerKey` của server, không
  /// suy từ `activeIndex` vì index có thể lệch khi người khác rời phòng.
  bool get isMyTurnKey => online && _turn.playerKey != null && _turn.playerKey == myPlayerKey;

  /// Có phải pha chờ trả lời không (dùng cho đồng hồ 60s và ẩn nút tung).
  bool get waitingAnswer => _turn.phase == TurnPhase.waitingAnswer;

  /// Vừa cập nhật đồng hồ — UI vẽ lại vòng đếm.
  void onTick() => notifyListeners();

  /// Server gửi giờ → hiệu chỉnh đồng hồ máy.
  void onServerTime(DateTime serverTime) {
    _clock.sync(serverTime);
    notifyListeners();
  }

  /// Nạp luật từ `GET /api/game/config`.
  Future<void> loadRules() async {
    final result = await _api.get('/api/game/config');
    if (result is ApiSuccess<Map<String, dynamic>>) {
      _rules = GameRules.fromJson(result.data);
      notifyListeners();
    }
  }

  /// Mình đang giữ quyền điều khiển phòng (chủ phòng hoặc được giao quyền tạm).
  ///
  /// Chỉ để bật/tắt nút trên UI; server vẫn tự kiểm tra quyền khi nhận request.
  bool get amHost {
    if (!online) return false;
    final key = myPlayerKey;
    return key != null && key == (_actingHostKey ?? _ownerKey);
  }

  /// Kết thúc ván (nút của người giữ quyền): server chốt thắng bại rồi bắn
  /// `FINISHED`. Trả `false` nếu server từ chối — không phải cảnh báo, vì quyền
  /// có thể vừa chuyển sang người khác giữa lúc bấm.
  Future<bool> finishGame(String? winnerKey) async {
    final key = myPlayerKey;
    if (key == null) return false;
    final result = await _api.post('/api/game/finish', {
      'playerId': key,
      'winnerKey': winnerKey,
    });
    if (result is ApiSuccess<Map<String, dynamic>>) {
      applyGameSnapshot(GameSnapshot.fromJson(result.data));
      onFinished(_winnerKey);
      return true;
    }
    return false;
  }

  /// Phòng bị xóa (chủ phòng đóng, hoặc hết TTL) — UI phải dừng mọi timer.
  void onRoomClosed(String reason) {
    _roomClosed = true;
    _roomClosedReason = reason;
    _turn = TurnState.empty;
    notifyListeners();
  }

  /// Chỉ còn một người trong trận.
  void onSolo(String? remainingKey) {
    _soloPlayerKey = remainingKey;
    notifyListeners();
  }

  /// Ván kết thúc.
  void onFinished(String? winnerKey) {
    _roomStatus = RoomStatus.finished;
    _winnerKey = winnerKey;
    _turn = TurnState.empty;
    notifyListeners();
  }

  void dismissSolo() {
    _soloPlayerKey = null;
    notifyListeners();
  }

  /// Tự tung cho lượt [turnId] — dự phòng, server quyết định kết quả.
  ///
  /// Trả `true` nếu server nhận. Trả `false` nếu bị từ chối (lượt đã đổi), và
  /// đây là chuyện bình thường: máy người chơi hoặc sweeper server đã tung
  /// trước. Không hiện lỗi, không báo người chơi.
  Future<bool> autoRoll(int turnId) async {
    final key = myPlayerKey;
    if (key == null) return false;
    final result = await _api.post('/api/game/roll-dice', {
      'playerId': key,
      'expectedTurnId': turnId,
      'auto': true,
      'reason': 'client-referee',
    });
    return result is ApiSuccess<Map<String, dynamic>>;
  }

  /// Áp state do server đẩy qua WebSocket: vị trí, điểm, lượt, xúc xắc.
  /// Idempotent — client cũng tự tính local rồi, snapshot chỉ khớp lại.
  /// Phải DỰNG LẠI `_players` theo thứ tự snapshot (server đã xếp theo
  /// playOrder sau giai đoạn quyết định thứ tự), nếu không activePlayerIndex
  /// sẽ trỏ nhầm người khi thứ tự chơi khác thứ tự vào phòng.
  void applyGameSnapshot(GameSnapshot snapshot) {
    if (snapshot.players.isEmpty) return;

    // Đồng bộ đồng hồ TRƯỚC khi đọc deadline, nếu không vòng đếm sẽ lệch đúng
    // nhịp đầu tiên mỗi lần nhận snapshot.
    final serverNow = snapshot.serverTime;
    if (serverNow != null) _clock.sync(serverNow);

    // turnId đổi = lượt mới → đặt lại mốc vẽ vòng tròn.
    final next = snapshot.turn;
    if (next.turnId != _turn.turnId) {
      _turnStartedAt = _clock.isSynced ? _clock.now() : DateTime.now();
    }
    _turn = next;
    _lastRollAuto = snapshot.lastRollAuto;
    _turnSkipped = snapshot.turnSkipped || next.skipped;
    _onlineCount = snapshot.onlineCount;
    _roomStatus = snapshot.status;
    _winnerKey = snapshot.winnerKey;
    _ownerKey = snapshot.ownerKey;
    _actingHostKey = snapshot.actingHostKey;

    // Quyền điều khiển: owner thật, hoặc người được giao quyền tạm khi owner offline.
    final hostKey = snapshot.actingHostKey ?? snapshot.ownerKey;

    final localByKey = {for (final p in _players) p.id: p};
    final copy = <Player>[];
    for (final remote in snapshot.players) {
      final local = localByKey.remove(remote.playerKey);
      copy.add(
        local == null
            ? Player(
                id: remote.playerKey,
                name: remote.name,
                color: AppColors.player1,
                position: remote.position,
                totalAnswered: remote.totalAnswered,
                totalPoints: remote.totalPoints,
                winPoints: remote.winPoints,
                left: remote.left,
                orderRolled: remote.orderRolled,
                orderDice: remote.orderDice,
                presence: remote.presence,
                bot: remote.bot,
                isHost: remote.playerKey == hostKey,
                scores: remote.scores,
              )
            : local.copyWith(
                position: remote.position,
                totalAnswered: remote.totalAnswered,
                totalPoints: remote.totalPoints,
                winPoints: remote.winPoints,
                left: remote.left,
                orderRolled: remote.orderRolled,
                orderDice: remote.orderDice,
                presence: remote.presence,
                bot: remote.bot,
                isHost: remote.playerKey == hostKey,
                scores: remote.scores,
              ),
      );
    }
    for (final leftover in _players) {
      if (localByKey.containsKey(leftover.id)) {
        copy.add(leftover);
      }
    }
    _players = List<Player>.unmodifiable(copy);

    if (snapshot.activePlayerIndex >= 0 && snapshot.activePlayerIndex < _players.length) {
      _activeIndex = snapshot.activePlayerIndex;
    }
    _orderPhase = snapshot.orderPhase;
    _pendingAnswer = snapshot.pendingAnswer;
    // Lần tung CHUNG của bàn: `rollId` tăng mỗi lần → nhận biết chính xác lần
    // tung MỚI mà không phụ thuộc mạng (poll 2s / broadcast đều idempotent),
    // nhờ vậy MỌI người chơi đều thấy cùng mặt xúc xắc + ai vừa tung.
    if (snapshot.rollId > 0 && _consumeSharedRoll(snapshot.rollId)) {
      _sharedRoll = snapshot.lastDice;
      _sharedRollerKey = snapshot.lastRollerKey;
    } else if (snapshot.lastDice == null) {
      // Ô đã xử lý xong → server xoá lastDice, dọn kết quả cũ khỏi mọi máy.
      _sharedRoll = null;
      _sharedRollerKey = null;
    }
    notifyListeners();
  }

  /// Tung xúc xắc: hỏi server trước, rớt mạng thì random local.
  /// [playerId] khác null khi giai đoạn quyết định thứ tự (người tung không
  /// nhất thiết là người đang ở `activePlayerIndex` vì giai đoạn này không
  /// xoay theo lượt).
  ///
  /// Trả `null` khi server TỪ CHỐI (không phải lượt mình / lượt đang bận) —
  /// vậy bấm hai lần hoặc bấm khi lượt vừa chuyển sẽ không làm lệch state
  /// local với server.
  Future<int?> rollDice({String? playerId}) async {
    if (_isRolling) return _diceResult;
    _isRolling = true;
    notifyListeners();

    final rollerIndex = playerId == null
        ? _activeIndex
        : _players.indexWhere((p) => p.id == playerId);
    if (rollerIndex < 0) {
      _isRolling = false;
      notifyListeners();
      return null;
    }
    final roller = _players[rollerIndex];

    var result = _random.nextInt(6) + 1;
    // Di chuyển theo HƯỚNG CỐ ĐỊNH (BoardDefinition.direction): đi ngược qua
    // ô 52/0 = hoàn thành thêm 1 vòng, vị trí quay về trong vòng cờ 53 ô.
    var nextPosition = BoardDefinition.moveIndex(roller.position, result);

    final apiResult = await _api.post('/api/game/roll-dice', {'playerId': roller.id});
    final serverAccepted = apiResult is ApiSuccess<Map<String, dynamic>>;
    bool? serverMoved;
    if (apiResult is ApiSuccess<Map<String, dynamic>>) {
      final dice = apiResult.data['diceValue'];
      final pos = apiResult.data['newPosition'];
      if (dice is int && dice >= 1 && dice <= 6) result = dice;
      if (pos is int) {
        nextPosition = pos.clamp(0, BoardDefinition.length - 1);
      }
      final moved = apiResult.data['moved'];
      if (moved is bool) serverMoved = moved;
    }

    // SERVER là nguồn chân lý về việc quân có di chuyển: lần tung quyết định
    // thứ tự đầu trận thì quân ĐỨNG YÊN (`moved = false`). Không suy đoán bằng
    // `_orderPhase` cục bộ vì giai đoạn này có thể CHƯA kịp đồng bộ tới client
    // lúc người chơi bấm nút → quân chạy rồi bị server kéo lại (nhảy lung tung).
    // Server cũ không trả `moved` thì lùi về hành vi cũ.
    final orderRoll = online ? !(serverMoved ?? !_orderPhase) : false;
    _lastRollMoved = !orderRoll;

    _diceResult = result;
    // Offline không có server cấp `rollId` → tự tăng bộ đếm nội bộ để UI vẫn
    // nhận ra lần tung mới (kể cả khi trùng mặt với lần trước).
    if (!online) _seenRollId++;
    _sharedRoll = result;
    _sharedRollerKey = roller.id;

    // Server TỪ CHỐI (không phải lượt mình / lượt đang bận): huỷ hết thay đổi
    // local, để mọi máy đồng bộ đúng theo server.
    if (online && !serverAccepted) {
      _diceResult = null;
      _sharedRoll = null;
      _sharedRollerKey = null;
      _isRolling = false;
      notifyListeners();
      return null;
    }

    // Đầu trận: xúc xắc quyết định thứ tự lượt — KHÔNG di chuyển quân,
    // server tự đổi người tung tiếp; ai tung cao nhất khi khép giai đoạn
    // sẽ đi trước (server gửi snapshot đã xếp lại trật tự).
    if (orderRoll) {
      _updatePlayer(rollerIndex, roller.copyWith(orderRolled: true, orderDice: result));
      _isRolling = false;
      notifyListeners();
      return result;
    }

    _updatePlayer(rollerIndex, roller.copyWith(position: nextPosition));

    // Ô ĐÍCH = vị trí server vừa trả về, KHÔNG phải `activePlayer.position`.
    // Online, broadcast/poll có thể tới TRƯỚC HTTP response: với ô START/⭐
    // server đã tự đổi lượt nên `activePlayer` trỏ sang NGƯỜI KẾ TIẾP. Nếu
    // đọc `activePlayer.position` thì UI mở nhầm thẻ của người sau (mà ⭐/START
    // không có thẻ → "ô thử thách chưa hiện ra"), hoặc mở thẻ RIASEC của
    // người khác rồi bị server từ chối khi trả lời. Ghi lại ô đích để thẻ
    // mở đúng bất kể broadcast tới sớm hay muộn.
    _landingPlayerId = roller.id;
    _landingPosition = nextPosition;

    // Server lăn vào ô START / ⭐ trang trí là tự đổi lượt ngay — mirror local
    // để lượt kết thúc liền, không chờ broadcast. Chỉ advance nếu broadcast
    // chưa kịp đổi lượt (tránh advance đúp); tuyệt đối không advance khi
    // vẫn còn giai đoạn quyết định thứ tự (server đã trả `moved = false`).
    final landedTile = BoardDefinition.tiles[nextPosition];
    final needsAnswer = landedTile is! StartTile && landedTile is! StarTile;
    if (online && serverAccepted && !orderRoll && !needsAnswer && _activeIndex == rollerIndex) {
      _advanceLocalTurn();
    }
    _isRolling = false;
    notifyListeners();
    return result;
  }

  /// Trả lời ô Holland đang đứng: chấm theo ánh xạ đáp án → nhóm/điểm của
  /// lá bài đã rút (`prepHollandCard`). Quy tắc tài liệu: đáp án thể hiện rõ
  /// nhất nhóm của lá +2, hai đáp án còn lại +1 cho hai nhóm liên quan.
  /// Online: gửi `targetCategory` + `points` lên server, server cộng điểm
  /// cho đúng nhóm đó và đổi lượt.
  Future<Award> answerCurrentTile({String selectedOption = 'A'}) async {
    final tile = BoardDefinition.tiles[activePlayer.position];
    final Riasec category = tile is RiasecTile ? tile.group : Riasec.realistic;

    const valid = {'A', 'B', 'C'};
    final option = valid.contains(selectedOption) ? selectedOption : 'A';

    // Ánh xạ đáp án theo lá bài đã rút; thiếu lá → +2 cho nhóm của ô.
    final mapped = _currentCard?.optionOf(option);
    final target = mapped?.category ?? category;
    var points = mapped?.points ?? 2;

    // Bắt người trả lời TRƯỚC await: broadcast lời trả lời có thể tới trong
    // lúc chờ REST và đổi lượt — không được đọc _activeIndex sau await.
    final answererIndex = _activeIndex;
    final answerer = activePlayer;

    var resolved = target;

    final apiResult = await _api.post('/api/game/answer', {
      'playerId': answerer.id,
      'questionId': _currentCard?.id ?? 'local-${category.code}',
      'selectedOption': option,
      'category': category.code,
      'targetCategory': target.code,
      'points': points,
    });
    final serverAccepted = apiResult is ApiSuccess<Map<String, dynamic>> && online;
    if (apiResult is ApiSuccess<Map<String, dynamic>>) {
      final p = apiResult.data['pointsEarned'];
      final c = apiResult.data['category'];
      if (p is int) points = p;
      if (c is String) resolved = Riasec.fromCode(c) ?? target;
    }

    final award = Award(points: points, category: resolved);
    _updatePlayer(answererIndex, answerer.addScore(resolved, points));

    // Server xác nhận vừa cộng điểm là vừa đổi lượt — mirror local để lượt
    // người chơi này kết thúc ngay. Chỉ advance nếu broadcast chưa kịp đổi.
    if (serverAccepted) {
      _diceResult = null;
      if (_activeIndex == answererIndex) {
        _advanceLocalTurn();
      }
    }

    _highlightedCategory = resolved;
    notifyListeners();
    _highlightTimer?.cancel();
    _highlightTimer = Timer(const Duration(milliseconds: 1100), () {
      _highlightedCategory = null;
      notifyListeners();
    });
    return award;
  }

  /// Hoàn thành ô Cơ hội / Thử thách: nhận Điểm chiến thắng (winPoints).
  /// `winPoints` không được truyền thì dùng lá bài đã rút; truyền 0 nghĩa là
  /// bỏ qua (vẫn đổi lượt trên server, tránh kẹt ván khi online).
  Future<Award> answerBonusCard({int? winPoints}) async {
    final tile = BoardDefinition.tiles[activePlayer.position];
    var points =
        winPoints ??
        switch (tile) {
          OpportunityTile() => _currentBonus?.winPoints ?? 0,
          ChallengeTile() => _currentChallenge?.winPoints ?? 0,
          _ => 0,
        };
    if (points < 0) points = 0;

    final answererIndex = _activeIndex;
    final answerer = activePlayer;

    final apiResult = await _api.post('/api/game/answer', {
      'playerId': answerer.id,
      'questionId': switch (tile) {
        OpportunityTile() => _currentBonus?.id ?? 'OPP',
        ChallengeTile() => _currentChallenge?.id ?? 'CHA',
        _ => 'BONUS',
      },
      'selectedOption': 'A',
      'category': Riasec.realistic.code,
      'points': 0,
      'winPoints': points,
    });
    final serverAccepted = apiResult is ApiSuccess<Map<String, dynamic>> && online;
    if (apiResult is ApiSuccess<Map<String, dynamic>>) {
      final w = apiResult.data['winPoints'];
      if (w is int) points = w;
    }

    final award = Award(points: points, win: true);
    if (points > 0) {
      _updatePlayer(answererIndex, answerer.addWinPoints(points));
    } else {
      _updatePlayer(answererIndex, answerer.copyWith(totalAnswered: answerer.totalAnswered + 1));
    }

    if (serverAccepted) {
      _diceResult = null;
      if (_activeIndex == answererIndex) {
        _advanceLocalTurn();
      }
    }

    notifyListeners();
    return award;
  }

  /// Chuyển lượt. Online: server tự đổi lượt (broadcast GAME),
  /// nút "Lượt tiếp" bị chặn ở UI nên không làm gì.
  void nextTurn() {
    if (online) return;
    _advanceLocalTurn();
    notifyListeners();
  }

  /// Hiện nút "LƯỢT TIẾP" (ô điều khiển, trạng thái resolved) khi:
  /// offline + tới lượt mình + không đang di chuyển. Online thì server tự đổi
  /// lượt ngay khi xử lý xong ô nên không có nút này.
  bool get canContinueTurn => !online && isMyRollTurn && !_isRolling;

  /// Mirror phía client của `Room.advanceTurn`: sang người tiếp theo,
  /// bỏ qua những người đã rời phòng (avatar tối).
  void _advanceLocalTurn() {
    if (_players.isEmpty) return;
    var next = (_activeIndex + 1) % _players.length;
    var guard = 0;
    while (_players[next].left && guard < _players.length) {
      next = (next + 1) % _players.length;
      guard++;
    }
    _activeIndex = next;
  }

  void _updatePlayer(int index, Player next) {
    final copy = List<Player>.of(_players);
    copy[index] = next;
    _players = List<Player>.unmodifiable(copy);
  }

  @override
  void dispose() {
    _highlightTimer?.cancel();
    if (_ownsApi) _api.dispose();
    super.dispose();
  }
}
