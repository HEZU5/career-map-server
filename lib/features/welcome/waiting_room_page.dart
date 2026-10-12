import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/storage/session_store.dart';
import '../../core/theme/app_colors.dart';
import '../game/data/game_api.dart';
import '../game/data/game_socket.dart';
import '../game/data/room_api.dart';
import '../game/domain/game_room.dart';
import '../game/presentation/game_page.dart';
import '../game/presentation/room_players.dart';
import 'character_select_page.dart';
import 'domain/sample_character.dart';

/// Phòng chờ: hiển thị mã phòng, danh sách người chơi (live qua WebSocket,
/// fallback poll), chờ đủ người để bắt đầu. Tại đây người chơi:
/// - Chọn/đổi nhân vật (4 lựa chọn, không giới tính) bằng cách chạm avatar.
/// Thứ tự lượt đi không còn quyết định ở đây: ngay khi ván bắt đầu, mỗi
/// người tung 1 xúc xắc trên bàn cờ, ai cao nhất đi trước.
/// Bấm bắt đầu hoặc nhận tin STARTED là cả phòng tự sang bàn cờ.
class WaitingRoomPage extends StatefulWidget {
  const WaitingRoomPage({
    super.key,
    required this.roomCode,
    required this.isHost,
    required this.myName,
    required this.myPlayerKey,
    required this.initialRoom,
    this.session,
  });

  final String roomCode;
  final bool isHost;
  final String myName;

  /// Chìa khoá người chơi trên server (host-XX khi tạo, guest-XX-n khi vào).
  final String myPlayerKey;
  final GameRoom initialRoom;

  /// Phiên thiết bị — dùng để xoá thẻ "vào lại trận" khi rời phòng hẳn.
  final SessionStore? session;

  @override
  State<WaitingRoomPage> createState() => _WaitingRoomPageState();
}

class _WaitingRoomPageState extends State<WaitingRoomPage> {
  final RoomApi _roomApi = RoomApi();
  GameSocket? _socket;
  StreamSubscription<GameSocketEvent>? _sub;
  late GameRoom _room = widget.initialRoom;
  Timer? _poll;
  bool _starting = false;
  bool _movedToGame = false;
  bool _renaming = false;

  @override
  void initState() {
    super.initState();
    _poll = Timer.periodic(const Duration(seconds: 3), (_) => _refresh());
    _socket = GameSocket(roomCode: widget.roomCode);
    _sub = _socket!.events.listen(_onSocketEvent);
    _socket!.connect();
  }

  @override
  void dispose() {
    _poll?.cancel();
    _sub?.cancel();
    _socket?.dispose();
    _roomApi.dispose();
    super.dispose();
  }

  void _onSocketEvent(GameSocketEvent event) {
    if (!mounted) return;
    switch (event.type) {
      case GameSocketEventType.room:
        final room = event.room;
        if (room != null) setState(() => _room = room);
      case GameSocketEventType.started:
        final room = event.room;
        if (room != null) {
          setState(() => _room = room);
          _goToGame(room);
        }
      case GameSocketEventType.game:
        break;
      case GameSocketEventType.closed:
        // Chủ phòng đóng phòng → về lobby kèm lý do, không phải "không tìm
        // thấy phòng" (phòng có thật, chỉ bị đóng).
        _handleRoomClosed(event.reason);
      case GameSocketEventType.presence:
        // Phòng chờ chưa có đồng hồ lượt; hiện tại toast presence để dành cho
        // bàn cờ, nên ở đây chỉ cần cập nhật lại danh sách người chơi.
        unawaited(_refresh());
      case GameSocketEventType.solo:
      case GameSocketEventType.finished:
        break;
    }
  }

  Future<void> _refresh() async {
    final result = await _roomApi.fetch(widget.roomCode);
    if (result is ApiSuccess<GameRoom> && mounted) {
      // Phòng đã bắt đầu mà bỏ lỡ tin STARTED (WS chậm/rớt) → tự sang bàn cờ.
      if (result.data.active) {
        _goToGame(result.data);
        return;
      }
      setState(() => _room = result.data);
    }
  }

  /// Người chơi CÒN ở phòng chờ (lọc bỏ người đã thoát).
  List<RoomPlayer> get _activePlayers => [
    for (final p in _room.players)
      if (!p.left) p,
  ];

  bool get _canStart => widget.isHost && _activePlayers.length >= 2;

  Future<void> _changeCharacter() async {
    if (_renaming || _room.active) return;
    final character = await Navigator.push<CareerCharacter>(
      context,
      MaterialPageRoute(builder: (_) => const CharacterSelectPage()),
    );
    if (character == null || !mounted) return;
    setState(() => _renaming = true);
    final result = await _roomApi.pickCharacter(
      widget.roomCode,
      widget.myPlayerKey,
      character.name,
    );
    if (!mounted) return;
    setState(() => _renaming = false);
    if (result is ApiSuccess<GameRoom>) {
      setState(() => _room = result.data);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('Nhân vật: ${character.name}')));
    } else if (result is ApiFailure<GameRoom>) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('Không chọn được nhân vật: ${result.message}')));
    }
  }

  Future<void> _startGame() async {
    if (_starting || !_canStart) return;
    setState(() => _starting = true);
    final result = await _roomApi.start(widget.roomCode);
    if (!mounted) return;
    setState(() => _starting = false);
    // Tin STARTED đã do server broadcast; nếu socket chậm thì tự sang.
    switch (result) {
      case ApiSuccess<GameRoom>(:final data):
        Future<void>.delayed(const Duration(milliseconds: 1200), () {
          if (mounted) _goToGame(data);
        });
      case ApiFailure<GameRoom>(:final message):
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Không bắt đầu được ván: $message')));
    }
  }

  void _goToGame(GameRoom room) {
    if (_movedToGame) return;
    _movedToGame = true;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(
        builder: (_) => GamePage(
          roomCode: room.code,
          initialPlayers: gamePlayersFromRoom(room),
          myPlayerKey: widget.myPlayerKey,
          isOnline: true,
          session: widget.session,
        ),
      ),
    );
  }

  /// Chủ phòng đóng phòng (hoặc hết TTL): về màn chính kèm lý do.
///
/// Phải khác hẳn màn "không tìm thấy phòng" — phòng có thật, chỉ đã bị đóng.
/// Dừng mọi timer trước khi đi để không poll tiếp vào phòng không còn.
Future<void> _handleRoomClosed(String? reason) async {
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

Future<void> _backToMain() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Rời phòng?'),
        content: const Text('Bạn sẽ quay lại màn hình chính và rời phòng chờ.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Ở lại'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Về màn hình chính'),
          ),
        ],
      ),
    );
    if (confirmed == true && mounted) {
      await _roomApi.leave(widget.roomCode, widget.myPlayerKey);
      // Đã rời phòng hẳn → xoá phiên, không hiện thẻ "vào lại trận".
      widget.session?.clearActiveRoom();
      if (mounted) {
        Navigator.of(context).popUntil((route) => route.isFirst);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _backToMain();
      },
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Phòng chờ'),
          leading: IconButton(icon: const Icon(Icons.close), onPressed: _backToMain),
        ),
        body: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            _RoomCodeCard(code: widget.roomCode),
            const SizedBox(height: 16),
            Text(
              '${_activePlayers.length}/4 người chơi',
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.bold,
                color: AppColors.violet,
              ),
            ),
            if (widget.isHost)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(
                  'Bạn bè nhập mã ${widget.roomCode} để tham gia',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.black54),
                ),
              ),
            const SizedBox(height: 16),
            Wrap(
              alignment: WrapAlignment.center,
              spacing: 12,
              runSpacing: 12,
              children: [
                for (final p in _activePlayers)
                  _PlayerAvatar(
                    player: p,
                    isMe: p.playerKey == widget.myPlayerKey,
                    onTap: p.playerKey == widget.myPlayerKey ? _changeCharacter : null,
                  ),
              ],
            ),
            if (_activePlayers.isEmpty)
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text(
                  'Chưa có ai...',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.black54),
                ),
              ),
            const SizedBox(height: 20),
            if (widget.isHost)
              FilledButton.icon(
                onPressed: _canStart && !_starting ? _startGame : null,
                icon: _starting
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.play_arrow),
                label: Text(_canStart ? 'Bắt đầu chơi' : 'Đang chờ thêm người chơi...'),
              )
            else
              FilledButton.icon(
                onPressed: null,
                icon: const Icon(Icons.hourglass_empty),
                label: const Text('Chờ chủ phòng bắt đầu...'),
              ),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              onPressed: _backToMain,
              icon: const Icon(Icons.logout),
              label: const Text('Về màn hình chính'),
            ),
          ],
        ),
      ),
    );
  }
}

/// Thẻ mã phòng lớn kèm nút copy.
class _RoomCodeCard extends StatelessWidget {
  const _RoomCodeCard({required this.code});

  final String code;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.pink,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: AppColors.secondaryButton),
      ),
      padding: const EdgeInsets.all(20),
      child: Column(
        children: [
          const Text('Mã phòng', style: TextStyle(fontSize: 13, color: Colors.black54)),
          const SizedBox(height: 6),
          Text(
            code,
            style: const TextStyle(
              fontSize: 40,
              fontWeight: FontWeight.w900,
              letterSpacing: 4,
              color: AppColors.violet,
            ),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: code));
              if (!context.mounted) return;
              ScaffoldMessenger.of(context)
                ..hideCurrentSnackBar()
                ..showSnackBar(SnackBar(content: Text('Đã copy mã phòng $code')));
            },
            icon: const Icon(Icons.copy, size: 18),
            label: const Text('Copy mã'),
          ),
        ],
      ),
    );
  }
}

/// Ảnh đại diện người chơi — resolve theo nhân vật (name); chạm avatar
/// của mình để đổi nhân vật.
class _PlayerAvatar extends StatelessWidget {
  const _PlayerAvatar({required this.player, required this.isMe, this.onTap});

  final RoomPlayer player;
  final bool isMe;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    // Nhân vật lấy từ `characterName`. Phòng cũ (tạo trước khi tách trường) đang
    // lưu nhân vật trong chính `name`, nên vẫn thử `byName(name)` cho tương thích.
    final character =
        CareerCharacter.byName(player.characterName) ?? CareerCharacter.byName(player.name);
    final color = character?.color ?? CareerCharacter.fallbackColor();
    final asset = character?.assetPath;
    return Tooltip(
      message: isMe
          ? 'Chạm để đổi nhân vật${player.characterName == null ? '' : '\nNhân vật: ${player.characterName}'}'
          : '${player.name}${player.characterName == null ? '' : '\nNhân vật: ${player.characterName}'}',
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: onTap,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Stack(
              children: [
                CircleAvatar(
                  radius: 30,
                  backgroundColor: color,
                  backgroundImage: asset == null ? null : AssetImage(asset),
                  child: asset == null
                      ? Icon(CareerCharacter.fallbackIcon(), color: Colors.white, size: 30)
                      : null,
                ),
                if (isMe)
                  Positioned(
                    left: -6,
                    bottom: -4,
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                        color: AppColors.primaryButton,
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: const Text(
                        'BẠN',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 8,
                          fontWeight: FontWeight.w900,
                        ),
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 4),
            SizedBox(
              width: 110,
              child: Column(
                children: [
                  Text(
                    player.name,
                    textAlign: TextAlign.center,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 12),
                  ),
                  if (player.characterName != null)
                    Text(
                      player.characterName!,
                      textAlign: TextAlign.center,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 10.5, color: Colors.black54),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
