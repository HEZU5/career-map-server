import 'package:flutter/foundation.dart';

/// Bảng cấu hình luật chơi do server trả về (`GET /api/game/config`).
///
/// Client KHÔNG hard-code 15 giây hay 2 giây. Đọc từ server để đổi giá trị ở
/// `GameRulesConfig` là áp dụng cho cả hai vế, không phải sửa hai nơi.
///
/// Giá trị mặc định ở đây chỉ là dự phòng cho lúc mạng chưa kịp trả lời;
/// heartbeat đầu tiên sẽ ghi đè bằng giá trị thật.
@immutable
class GameRules {
  const GameRules({
    this.turnRollS = 15,
    this.answerTimeoutS = 60,
    this.awayAfterS = 120,
    this.heartbeatS = 5,
    this.offlineDetectS = 15,
    this.fastAutoRollS = 2,
    this.emptyRoomTtlMin = 10,
    this.finishedRoomTtlMin = 30,
    this.offlinePolicy = 'skip',
  });

  /// Lượt tung xúc xắc có bao lâu thì tự tung hộ.
  final int turnRollS;

  /// Mở thẻ có bao lâu thì tự bỏ qua.
  final int answerTimeoutS;

  /// Mất kết nối bao lâu thì bị bỏ qua lượt.
  final int awayAfterS;

  /// Chu kỳ gửi heartbeat.
  final int heartbeatS;

  /// Quá bao lâu không heartbeat thì coi là offline.
  final int offlineDetectS;

  /// Người offline tự tung sau bao lâu (không bắt cả bàn chờ 15s).
  final int fastAutoRollS;

  /// Không còn ai online bao lâu thì dọn phòng.
  final int emptyRoomTtlMin;

  /// Ván kết thúc giữ lại bao lâu để xem bảng kết quả.
  final int finishedRoomTtlMin;

  /// skip | bot | eliminate.
  final String offlinePolicy;

  static const fallback = GameRules();

  factory GameRules.fromJson(Map<String, dynamic> json) => GameRules(
    turnRollS: (json['turnRollS'] as num?)?.toInt() ?? 15,
    answerTimeoutS: (json['answerTimeoutS'] as num?)?.toInt() ?? 60,
    awayAfterS: (json['awayAfterS'] as num?)?.toInt() ?? 120,
    heartbeatS: (json['heartbeatS'] as num?)?.toInt() ?? 5,
    offlineDetectS: (json['offlineDetectS'] as num?)?.toInt() ?? 15,
    fastAutoRollS: (json['fastAutoRollS'] as num?)?.toInt() ?? 2,
    emptyRoomTtlMin: (json['emptyRoomTtlMin'] as num?)?.toInt() ?? 10,
    finishedRoomTtlMin: (json['finishedRoomTtlMin'] as num?)?.toInt() ?? 30,
    offlinePolicy: json['offlinePolicy'] as String? ?? 'skip',
  );
}

/// Đồng hồ server.
///
/// Client giữ một bản sao "giờ server" để suy ra thời gian còn lại mà không phụ
/// thuộc đồng hồ máy người chơi — máy lệch 3 phút vẫn thấy vòng đếm đúng, và mọi
/// người trong phòng luôn thấy cùng một con số.
class ServerClock {
  ServerClock();

  DateTime? _serverNow;
  DateTime? _syncedAtLocal;

  /// Đồng bộ lại từ `serverTime` của snapshot/heartbeat.
  void sync(DateTime serverTime) {
    _serverNow = serverTime;
    _syncedAtLocal = DateTime.now();
  }

  bool get isSynced => _serverNow != null;

  /// Giờ server ước tính tại thời điểm hiện tại.
  DateTime now() {
    final base = _serverNow;
    if (base == null) return DateTime.now();
    return base.add(DateTime.now().difference(_syncedAtLocal!));
  }

  /// Còn bao lâu nữa thì hết hạn. Âm nghĩa là đã quá hạn.
  Duration remaining(DateTime? deadlineAt) {
    if (deadlineAt == null) return Duration.zero;
    return deadlineAt.difference(now());
  }

  /// Đồng hồ có nên bỏ qua lệch pha không — quá 30 giây thì coi như chưa đồng bộ
  /// và dùng đồng hồ máy, thà hiển thị lệch còn hơn là đơ.
  bool get driftPlausible {
    final base = _serverNow;
    final at = _syncedAtLocal;
    if (base == null || at == null) return false;
    final drift = base.difference(at).abs();
    return drift < const Duration(seconds: 30);
  }
}