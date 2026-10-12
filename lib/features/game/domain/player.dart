import 'package:flutter/material.dart';

import 'game_room.dart';
import 'riasec.dart';

/// Model bất biến — code cũ dùng `class PlayerState` với field mutable
/// (`position`, `totalPoints`...) nên UI và logic giẫm chân nhau,
/// không test được, dễ race-condition.
@immutable
class Player {
  const Player({
    required this.id,
    required this.name,
    required this.color,
    this.characterName,
    this.position = 0,
    this.totalAnswered = 0,
    this.totalPoints = 0,
    this.winPoints = 0,
    this.left = false,
    this.orderRolled = false,
    this.orderDice = 0,
    this.presence = Presence.online,
    this.bot = false,
    this.isHost = false,
    Map<Riasec, int>? scores,
  }) : scores =
           scores ??
           const {
             Riasec.realistic: 0,
             Riasec.investigative: 0,
             Riasec.artistic: 0,
             Riasec.social: 0,
             Riasec.enterprising: 0,
             Riasec.conventional: 0,
           };

  final String id;

  /// Tên người chơi tự nhập — không bị nhân vật ghi đè.
  final String name;

  /// Nhân vật nghề nghiệp (chỉ để vẽ avatar), rỗng nếu chưa chọn.
  final String? characterName;

  final Color color;
  final int position;
  final int totalAnswered;
  final int totalPoints;
  final Map<Riasec, int> scores;

  /// Điểm chiến thắng — nhận từ lá Cơ hội / Thử thách
  /// (tách biệt khỏi Điểm Holland của các ô R/I/A/S/E/C).
  final int winPoints;

  /// Đã rời phòng giữa trận (server đánh dấu) — UI hiện avatar tối màu.
  final bool left;

  /// Đã tung xúc xắc quyết định thứ tự lượt (đầu trận).
  final bool orderRolled;

  /// Điểm xúc xắc quyết định thứ tự lượt — ai cao đi trước.
  final int orderDice;

  /// Trạng thái hiện diện do server quyết (heartbeat), không suy từ việc có
  /// mở socket hay không: WebSocket ở đây chỉ đẩy một chiều.
  final Presence presence;

  /// Quân do máy điều khiển khi người chơi vắng mặt quá lâu.
  final bool bot;

  /// Người này đã rời phòng — thắng cả [presence.left] lẫn mọi màu khác.
  bool get gone => left || presence == Presence.left;

  /// Đang giữ quyền điều khiển phòng: chủ phòng thật, hoặc người được giao quyền
  /// tạm khi owner offline.
  ///
  /// Đây là bản sao hiển thị, không phải quyền thật trên server.
  final bool isHost;

  Player copyWith({
    int? position,
    int? totalAnswered,
    int? totalPoints,
    int? winPoints,
    bool? left,
    bool? orderRolled,
    int? orderDice,
    Presence? presence,
    bool? bot,
    bool? isHost,
    Map<Riasec, int>? scores,
  }) {
    return Player(
      id: id,
      name: name,
      color: color,
      characterName: characterName,
      position: position ?? this.position,
      totalAnswered: totalAnswered ?? this.totalAnswered,
      totalPoints: totalPoints ?? this.totalPoints,
      winPoints: winPoints ?? this.winPoints,
      left: left ?? this.left,
      orderRolled: orderRolled ?? this.orderRolled,
      orderDice: orderDice ?? this.orderDice,
      presence: presence ?? this.presence,
      bot: bot ?? this.bot,
      isHost: isHost ?? this.isHost,
      scores: scores ?? this.scores,
    );
  }

  Player addScore(Riasec category, int points) {
    final next = Map<Riasec, int>.of(scores);
    next[category] = (next[category] ?? 0) + points;
    return copyWith(
      scores: Map<Riasec, int>.unmodifiable(next),
      totalPoints: totalPoints + points,
      totalAnswered: totalAnswered + 1,
    );
  }

  Player addWinPoints(int points) {
    return copyWith(winPoints: winPoints + points, totalAnswered: totalAnswered + 1);
  }
}

/// Kết quả trả điểm cho 1 ô — thay `class Award(points, category-String)`.
@immutable
class Award {
  const Award({required this.points, this.category, this.win = false});

  final int points;

  /// Nhóm Holland nhận điểm (null khi là Điểm chiến thắng từ Cơ hội/Thử thách).
  final Riasec? category;

  /// `true` = đây là Điểm chiến thắng (không thuộc nhóm Holland nào).
  final bool win;
}
