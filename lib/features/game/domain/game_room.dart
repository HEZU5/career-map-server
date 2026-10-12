import 'package:flutter/foundation.dart';

@immutable
class RoomPlayer {
  const RoomPlayer({
    required this.playerKey,
    required this.name,
    this.characterName,
    this.left = false,
    this.orderRolled = false,
    this.orderDice = 0,
    this.presence = Presence.online,
    this.bot = false,
  });

  final String playerKey;

  /// Tên người chơi tự nhập — KHÔNG bị nhân vật ghi đè.
  final String name;

  /// Nhân vật nghề nghiệp đã chọn (hiển thị phụ, không thay tên).
  final String? characterName;
  final bool left;

  /// Đã tung xúc xắc quyết định thứ tự lượt (phòng chờ).
  final bool orderRolled;
  final int orderDice;

  /// Server phán online/offline/away — nguồn sự thật cho avatar và toast.
  final Presence presence;

  /// Nhãn "bot thay người vắng", để không nhầm với người thật.
  final bool bot;

  factory RoomPlayer.fromJson(Map<String, dynamic> json) => RoomPlayer(
    playerKey: json['playerKey'] as String? ?? '',
    name: json['name'] as String? ?? 'Khách',
    characterName: json['characterName'] as String?,
    left: json['left'] as bool? ?? false,
    orderRolled: json['orderRolled'] as bool? ?? false,
    orderDice: json['orderDice'] as int? ?? 0,
    presence: Presence.parse(json['presence'] as String?),
    bot: json['bot'] as bool? ?? false,
  );

  RoomPlayer copyWith({
    String? name,
    String? characterName,
    bool? left,
    bool? orderRolled,
    int? orderDice,
    Presence? presence,
    bool? bot,
  }) => RoomPlayer(
    playerKey: playerKey,
    name: name ?? this.name,
    characterName: characterName ?? this.characterName,
    left: left ?? this.left,
    orderRolled: orderRolled ?? this.orderRolled,
    orderDice: orderDice ?? this.orderDice,
    presence: presence ?? this.presence,
    bot: bot ?? this.bot,
  );
}

/// Lượt chơi đang mở, do server đặt deadline một lần duy nhất.
///
/// Vòng đếm ở UI là `deadlineAt - giờ hiện tại đã bù lệch`, KHÔNG phải một timer
/// đếm lùi độc lập — nhờ vậy tab nền bị throttle hoặc máy lag không làm lệch.
@immutable
class TurnState {
  const TurnState({
    this.turnId = 0,
    this.phase = TurnPhase.none,
    this.playerKey,
    this.deadlineAt,
    this.skipped = false,
  });

  final int turnId;
  final TurnPhase phase;
  final String? playerKey;
  final DateTime? deadlineAt;

  /// Lượt vừa bị bỏ qua vì người chơi vắng mặt (hiện pill thông báo).
  final bool skipped;

  bool get isActive => phase != TurnPhase.none && deadlineAt != null;

  factory TurnState.fromJson(Map<String, dynamic> json) {
    final raw = json['turnDeadlineAt'] as String?;
    return TurnState(
      turnId: (json['turnId'] as num?)?.toInt() ?? 0,
      phase: TurnPhase.parse(json['turnPhase'] as String?),
      playerKey: json['turnPlayerKey'] as String?,
      deadlineAt: raw == null ? null : DateTime.tryParse(raw)?.toLocal(),
      skipped: json['turnSkipped'] as bool? ?? false,
    );
  }

  static const empty = TurnState();
}

/// Trạng thái hiện diện do SERVER phán, client không tự suy đoán.
enum Presence {
  online,
  offline,
  away,
  left;

  static Presence parse(String? raw) => switch (raw) {
    'offline' => Presence.offline,
    'away' => Presence.away,
    'left' => Presence.left,
    _ => Presence.online,
  };

  /// Avatar tối đi, nhưng vẫn giữ chỗ — khác hẳn `left` (rời hẳn, mất ghế).
  bool get isDimmed => this == Presence.offline || this == Presence.away;
}

/// Trạng thái pha lượt — nguồn sự thật duy nhất cho đồng hồ.
enum TurnPhase {
  none,
  waitingRoll,
  waitingAnswer;

  static TurnPhase parse(String? raw) => switch (raw) {
    'WAITING_ROLL' => TurnPhase.waitingRoll,
    'WAITING_ANSWER' => TurnPhase.waitingAnswer,
    _ => TurnPhase.none,
  };
}

/// Vòng đời phòng.
enum RoomStatus {
  waiting,
  playing,
  finished;

  static RoomStatus parse(String? raw) => switch (raw) {
    'PLAYING' => RoomStatus.playing,
    'FINISHED' => RoomStatus.finished,
    _ => RoomStatus.waiting,
  };
}

/// Một dòng trong danh sách phòng công khai còn mở (endpoint
/// `GET /api/rooms/open`) — chỉ đủ thông tin để hiển thị và bấm "Vào".
@immutable
class OpenRoom {
  const OpenRoom({
    required this.code,
    required this.hostName,
    required this.playerCount,
    required this.maxPlayers,
  });

  final String code;
  final String hostName;
  final int playerCount;
  final int maxPlayers;

  int get freeSlots => (maxPlayers - playerCount).clamp(0, maxPlayers);

  factory OpenRoom.fromJson(Map<String, dynamic> json) => OpenRoom(
    code: json['code'] as String? ?? '',
    hostName: json['hostName'] as String? ?? 'Khách',
    playerCount: json['playerCount'] as int? ?? 0,
    maxPlayers: json['maxPlayers'] as int? ?? 4,
  );
}

@immutable
class GameRoom {
  const GameRoom({
    required this.code,
    required this.hostName,
    required this.maxPlayers,
    required this.players,
    this.active = false,
    this.isPrivate = false,
    this.ownerKey,
    this.actingHostKey,
    this.effectiveHostKey,
    this.status = RoomStatus.waiting,
  });

  final String code;
  final String hostName;
  final int maxPlayers;
  final List<RoomPlayer> players;
  final bool active;

  /// Phòng riêng tư: ẩn khỏi danh sách phòng công khai, vẫn vào được bằng mã.
  final bool isPrivate;

  /// Người tạo phòng — chủ phòng, không đổi khi mất kết nối.
  final String? ownerKey;

  /// Người giữ quyền tạm khi chủ phòng đang vắng mặt.
  final String? actingHostKey;

  /// Ai thực sự có quyền lúc này — dùng để bật/tắt nút "Đóng phòng".
  final String? effectiveHostKey;

  final RoomStatus status;

  /// `true` nếu mình đang giữ quyền chủ phòng (chính hoặc tạm thời).
  bool isHost(String? myKey) =>
      myKey != null && effectiveHostKey != null && effectiveHostKey == myKey;

  factory GameRoom.fromJson(Map<String, dynamic> json) {
    final rawPlayers = json['players'] as List<dynamic>? ?? const [];
    return GameRoom(
      code: json['code'] as String? ?? '',
      hostName: json['hostName'] as String? ?? '',
      maxPlayers: json['maxPlayers'] as int? ?? 4,
      active: json['active'] as bool? ?? false,
      isPrivate: json['isPrivate'] as bool? ?? false,
      ownerKey: json['ownerKey'] as String?,
      actingHostKey: json['actingHostKey'] as String?,
      effectiveHostKey: json['effectiveHostKey'] as String?,
      status: RoomStatus.parse(json['status'] as String?),
      players: rawPlayers.whereType<Map<String, dynamic>>().map(RoomPlayer.fromJson).toList(),
    );
  }
}
