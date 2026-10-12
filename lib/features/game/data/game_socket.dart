import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:stomp_dart_client/stomp_dart_client.dart';

import '../../../core/config/api_config.dart';
import '../domain/game_room.dart';
import '../domain/riasec.dart';

/// Sự kiện nhận từ WebSocket: phòng được cập nhật / ván bắt đầu / state ván.
enum GameSocketEventType { room, started, game, presence, closed, solo, finished }

@immutable
class GameSocketEvent {
  const GameSocketEvent.room(this.room)
    : type = GameSocketEventType.room,
      game = null,
      messages = const [],
      reason = null,
      playerKey = null;

  const GameSocketEvent.started(this.room)
    : type = GameSocketEventType.started,
      game = null,
      messages = const [],
      reason = null,
      playerKey = null;

  const GameSocketEvent.game(this.game)
    : type = GameSocketEventType.game,
      room = null,
      messages = const [],
      reason = null,
      playerKey = null;

  /// Toast mất kết nối / quay lại / chuyển quyền chủ phòng.
  const GameSocketEvent.presence(this.messages)
    : type = GameSocketEventType.presence,
      room = null,
      game = null,
      reason = null,
      playerKey = null;

  /// Chủ phòng đóng phòng → phải dừng timer và về lobby.
  const GameSocketEvent.closed(this.reason)
    : type = GameSocketEventType.closed,
      room = null,
      game = null,
      messages = const [],
      playerKey = null;

  /// Chỉ còn một người trong trận → hỏi chơi tiếp hay kết thúc.
  const GameSocketEvent.solo(this.playerKey)
    : type = GameSocketEventType.solo,
      room = null,
      game = null,
      messages = const [],
      reason = null;

  const GameSocketEvent.finished(this.game)
    : type = GameSocketEventType.finished,
      room = null,
      messages = const [],
      reason = null,
      playerKey = null;

  final GameSocketEventType type;

  /// Dữ liệu phòng (tin ROOM / STARTED).
  final GameRoom? room;

  /// Dữ liệu state ván (tin GAME).
  final GameSnapshot? game;

  /// Nội dung toast (tin PRESENCE).
  final List<String> messages;

  /// Lý do phòng đóng (tin ROOM_CLOSED).
  final String? reason;

  /// Key người còn lại (tin SOLO).
  final String? playerKey;
}

/// Người chơi trong ảnh chụp ván — tương ứng `GameSnapshot.GamePlayerInfo`.
@immutable
class GameSnapshotPlayer {
  const GameSnapshotPlayer({
    required this.playerKey,
    required this.name,
    required this.position,
    required this.totalAnswered,
    required this.totalPoints,
    required this.scores,
    this.winPoints = 0,
    this.left = false,
    this.orderRolled = false,
    this.orderDice = 0,
    this.characterName,
    this.presence = Presence.online,
    this.bot = false,
  });

  final String playerKey;
  final String name;
  final int position;
  final int totalAnswered;
  final int totalPoints;
  final Map<Riasec, int> scores;
  final int winPoints;
  final bool left;
  final bool orderRolled;
  final int orderDice;

  /// Nhân vật để vẽ avatar.
  final String? characterName;

  /// Server phán online/offline/away.
  final Presence presence;

  /// Nhãn bot thay người vắng.
  final bool bot;

  factory GameSnapshotPlayer.fromJson(Map<String, dynamic> json) {
    final rawScores = json['scores'] as Map<String, dynamic>? ?? const {};
    final scores = <Riasec, int>{
      for (final entry in rawScores.entries) ?Riasec.fromCode(entry.key): entry.value as int,
    };
    return GameSnapshotPlayer(
      playerKey: json['playerKey'] as String? ?? '',
      name: json['name'] as String? ?? '',
      position: json['position'] as int? ?? 0,
      totalAnswered: json['totalAnswered'] as int? ?? 0,
      totalPoints: json['totalPoints'] as int? ?? 0,
      winPoints: json['winPoints'] as int? ?? 0,
      left: json['left'] as bool? ?? false,
      orderRolled: json['orderRolled'] as bool? ?? false,
      orderDice: json['orderDice'] as int? ?? 0,
      characterName: json['characterName'] as String?,
      presence: Presence.parse(json['presence'] as String?),
      bot: json['bot'] as bool? ?? false,
      scores: Map<Riasec, int>.unmodifiable(scores),
    );
  }
}

/// State thời điểm thật của ván do server đẩy xuống.
@immutable
class GameSnapshot {
  const GameSnapshot({
    required this.code,
    required this.active,
    required this.activePlayerIndex,
    required this.pendingAnswer,
    required this.orderPhase,
    required this.lastDice,
    required this.players,
    this.rollId = 0,
    this.lastRollerKey,
    this.turn = TurnState.empty,
    this.serverTime,
    this.status = RoomStatus.waiting,
    this.ownerKey,
    this.actingHostKey,
    this.effectiveHostKey,
    this.winnerKey,
    this.onlineCount = 0,
    this.turnSkipped = false,
    this.lastRollAuto = false,
  });

  final String code;
  final bool active;
  final int activePlayerIndex;
  final bool pendingAnswer;
  final bool orderPhase;
  final int? lastDice;
  final List<GameSnapshotPlayer> players;

  /// Số thứ tự lần tung (tăng mỗi lần) — client so với lần đã xử lý để biết
  /// đây là lần tung MỚI và hiện kết quả cho mọi người chơi.
  final int rollId;

  /// Key người vừa tung (để gắn avatar/tên vào thông báo).
  final String? lastRollerKey;

  /// Lượt đang mở kèm deadline — nguồn duy nhất cho đồng hồ.
  final TurnState turn;

  /// Giờ server tại lúc gửi, dùng hiệu chỉnh đồng hồ máy.
  final DateTime? serverTime;

  final RoomStatus status;
  final String? ownerKey;
  final String? actingHostKey;
  final String? effectiveHostKey;
  final String? winnerKey;

  /// Số người đang online — 0 nghĩa là cả bàn đang offline, dừng đồng hồ.
  final int onlineCount;

  final bool turnSkipped;
  final bool lastRollAuto;

  factory GameSnapshot.fromJson(Map<String, dynamic> json) {
    final rawPlayers = json['players'] as List<dynamic>? ?? const [];
    final serverRaw = json['serverTime'] as String?;
    return GameSnapshot(
      code: json['code'] as String? ?? '',
      active: json['active'] as bool? ?? false,
      activePlayerIndex: json['activePlayerIndex'] as int? ?? 0,
      pendingAnswer: json['pendingAnswer'] as bool? ?? false,
      orderPhase: json['orderPhase'] as bool? ?? false,
      lastDice: json['lastDice'] as int?,
      rollId: json['rollId'] as int? ?? 0,
      lastRollerKey: json['lastRollerKey'] as String?,
      turn: TurnState.fromJson(json),
      serverTime: serverRaw == null ? null : DateTime.tryParse(serverRaw),
      status: RoomStatus.parse(json['status'] as String?),
      ownerKey: json['ownerKey'] as String?,
      actingHostKey: json['actingHostKey'] as String?,
      effectiveHostKey: json['effectiveHostKey'] as String?,
      winnerKey: json['winnerKey'] as String?,
      onlineCount: (json['onlineCount'] as num?)?.toInt() ?? 0,
      turnSkipped: json['turnSkipped'] as bool? ?? false,
      lastRollAuto: json['lastRollAuto'] as bool? ?? false,
      players: rawPlayers
          .whereType<Map<String, dynamic>>()
          .map(GameSnapshotPlayer.fromJson)
          .toList(),
    );
  }
}

/// Kết nối STOMP tới mép `/ws` của server, đăng ký theo mã phòng.
/// Chỉ nhận tin (mọi hành động vẫn đi qua REST để server là nguồn chân lý).
class GameSocket {
  GameSocket({required this.roomCode, String? url}) : _url = url ?? defaultSocketUrl();

  final String roomCode;
  final String _url;

  StompClient? _client;
  final _controller = StreamController<GameSocketEvent>.broadcast();

  /// Sự kiện phòng / ván chơi. Là broadcast stream — nhiều listen được.
  Stream<GameSocketEvent> get events => _controller.stream;

  bool get isConnected => _client?.connected ?? false;

  /// URL WebSocket suy ra từ cùng một [ApiConfig] với REST, nên không bao giờ
  /// lệch. `https` → `wss` để không bị chặn mixed-content khi trang chạy qua
  /// HTTPS (Firebase Hosting).
  static String defaultSocketUrl() => ApiConfig.socketUrl;

  void connect() {
    _client = StompClient(
      config: StompConfig(
        url: _url,
        onConnect: _onConnect,
        onWebSocketError: (error) => debugPrint('GameSocket ws error: $error'),
        onWebSocketDone: () => debugPrint('GameSocket ws done'),
        onStompError: (frame) => debugPrint('GameSocket stomp error: ${frame.body}'),
        heartbeatOutgoing: const Duration(seconds: 10),
        heartbeatIncoming: const Duration(seconds: 20),
        // Tự kết nối lại khi rớt mạng để ván không bị đơ giữa trận
        // (khách sẽ lại nhận GAME broadcast kể từ lần kết nối sau).
        reconnectDelay: const Duration(seconds: 2),
      ),
    );
    _client!.activate();
  }

  void _onConnect(StompFrame frame) {
    debugPrint('GameSocket connected: ${frame.command}');
    _client?.subscribe(destination: '/topic/rooms/${roomCode.toUpperCase()}', callback: _onFrame);
  }

  void _onFrame(StompFrame frame) {
    final text = frame.body;
    if (text == null || text.isEmpty) return;
    try {
      final json = jsonDecode(text) as Map<String, dynamic>;
      switch (json['type']) {
        case 'ROOM':
          _controller.add(GameSocketEvent.room(GameRoom.fromJson(_roomOf(json))));
        case 'STARTED':
          _controller.add(GameSocketEvent.started(GameRoom.fromJson(_roomOf(json))));
        case 'GAME':
          _controller.add(
            GameSocketEvent.game(GameSnapshot.fromJson(json['game'] as Map<String, dynamic>)),
          );
        case 'PRESENCE':
          final raw = json['messages'] as List<dynamic>? ?? const [];
          _controller.add(
            GameSocketEvent.presence(raw.map((e) => '$e').toList(growable: false)),
          );
        case 'ROOM_CLOSED':
          _controller.add(GameSocketEvent.closed(json['reason'] as String?));
        case 'SOLO':
          _controller.add(GameSocketEvent.solo(json['playerKey'] as String?));
        case 'FINISHED':
          _controller.add(
            GameSocketEvent.finished(GameSnapshot.fromJson(json['game'] as Map<String, dynamic>)),
          );
      }
    } catch (e) {
      debugPrint('GameSocket bad frame: $e');
    }
  }

  static Map<String, dynamic> _roomOf(Map<String, dynamic> json) =>
      json['room'] as Map<String, dynamic>? ?? const {};

  void disconnect() {
    _client?.deactivate();
    _client = null;
  }

  void dispose() {
    disconnect();
    _controller.close();
  }
}
