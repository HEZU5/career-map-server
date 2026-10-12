import 'dart:async';

import '../data/game_api.dart';
import '../domain/game_rules.dart';
import 'game_controller.dart';

/// Nhịp sống và đồng hồ cho một phòng online.
///
/// Tách khỏi [GameController] vì nó thuộc về *vòng đời kết nối* chứ không thuộc
/// về *luật chơi*: có thể bật/tắt, restart, và huỷ độc lập với việc dựng bàn cờ.
///
/// Ba nhịp độc lập, ba mục đích khác nhau:
///
/// 1. **Heartbeat** mỗi [GameRules.heartbeatS] — báo còn sống. Đây là tín hiệu
///    duy nhất server có để biết ai online, vì WebSocket ở đây chỉ đẩy một chiều
///    nên không có sự kiện `onDisconnect`.
/// 2. **Đồng hồ lượt** — vẽ vòng đếm và phát tín hiệu khi hết hạn. Không quyết
///    định lượt; chỉ để UI chạy và (khi cần) bắn một lần tự tung dự phòng.
/// 3. **Tự tung dự phòng** — khi hết giờ mà người chơi còn bấm được, máy trọng
///    tài gọi API giúp. Server vẫn là nơi quyết định: request mang `turnId`, và
///    nếu lượt đã đổi thì server từ chối, không ai bị ảnh hưởng.
class TurnClockDriver {
  TurnClockDriver({
    required this.controller,
    required this.roomCode,
    required this.playerKey,
    GameRules? initialRules,
  }) : _rules = initialRules ?? controller.rules;

  final GameController controller;
  final String roomCode;
  final String playerKey;

  GameRules _rules;

  Timer? _heartbeat;
  Timer? _ticker;
  bool _autoRollSent = false;
  bool _stopped = false;

  /// Lý do hiện tại phòng không thể đóng vòng đồng hồ (debug + UI).
  String? lastHaltReason;

  GameRules get rules => _rules;

  /// Cập nhật luật khi server trả về giá trị thật.
  void updateRules(GameRules rules) {
    _rules = rules;
    _restartHeartbeat();
  }

  void start() {
    _stopped = false;
    _autoRollSent = false;
    _restartHeartbeat();
    _ticker ??= Timer.periodic(const Duration(milliseconds: 250), _onTick);
    // Nhịp đầu tiên để UI có trạng thái ngay, không chờ 250ms.
    unawaited(sendHeartbeat());
  }

  void stop() {
    _stopped = true;
    _heartbeat?.cancel();
    _heartbeat = null;
    _ticker?.cancel();
    _ticker = null;
  }

  /// Đồng bộ lại sau khi app quay lại tiền cảnh: gửi ngay một nhịp, đồng thời bỏ
  /// cờ "đã tự tung" để nếu lượt đã hết hạn trong lúc ngủ thì được thử lại.
  Future<void> onResumed() async {
    _autoRollSent = false;
    await sendHeartbeat();
  }

  Future<void> sendHeartbeat() async {
    if (_stopped) return;
    final api = controller.api;
    if (api == null) return;
    final result = await api.post(
      _heartbeatPath(),
      const {},
    );
    if (result is ApiSuccess<Map<String, dynamic>>) {
      final data = result.data;
      final serverTime = data['serverTime'] as String?;
      final parsed = serverTime == null ? null : DateTime.tryParse(serverTime);
      if (parsed != null) {
        controller.onServerTime(parsed);
        final hb = (data['heartbeatS'] as num?)?.toInt();
        if (hb != null && hb > 0 && hb != _rules.heartbeatS) {
          _rules = GameRules(
            turnRollS: _rules.turnRollS,
            answerTimeoutS: _rules.answerTimeoutS,
            awayAfterS: _rules.awayAfterS,
            heartbeatS: hb,
            offlineDetectS: _rules.offlineDetectS,
            fastAutoRollS: _rules.fastAutoRollS,
            emptyRoomTtlMin: _rules.emptyRoomTtlMin,
            finishedRoomTtlMin: _rules.finishedRoomTtlMin,
            offlinePolicy: _rules.offlinePolicy,
          );
          _restartHeartbeat();
        }
      }
      if (data['roomClosed'] == true) {
        controller.onRoomClosed('Phòng đã đóng');
      }
    }
  }

  /// `playerKey` đi kèm query string (bí mật, không nên nằm trong log body).
  String _heartbeatPath() => Uri.parse('/api/rooms/$roomCode/heartbeat').replace(
    queryParameters: {'playerKey': playerKey},
  ).toString();

  void _restartHeartbeat() {
    _heartbeat?.cancel();
    final seconds = _rules.heartbeatS.clamp(2, 60);
    _heartbeat = Timer.periodic(Duration(seconds: seconds), (_) {
      unawaited(sendHeartbeat());
    });
  }

  void _onTick(Timer _) {
    final turn = controller.turn;
    if (!turn.isActive) {
      _autoRollSent = false;
      lastHaltReason = null;
      controller.onTick();
      return;
    }
    if (controller.onlineCount == 0) {
      // Cả bàn offline: dừng đồng hồ, không tự tung — không ai xem, và lượt sẽ
      // được xử lý lại khi có người quay về.
      lastHaltReason = 'Cả bàn đang offline';
      _autoRollSent = false;
      controller.onTick();
      return;
    }
    lastHaltReason = null;
    controller.onTick();

    final remaining = controller.turnRemaining;
    if (remaining > Duration.zero || _autoRollSent) return;

    _autoRollSent = true;
    // Máy của chính người đang đến lượt: tự tung gần như tức thì, họ đang mở
    // app và nhìn thấy màn hết giờ thì có thể đã bấm.
    if (controller.isMyTurnKey) {
      unawaited(controller.autoRoll(turn.turnId));
      return;
    }
    // Máy trọng tài: người đang đến lượt có thể đã ngủ/mất mạng, thiết bị này
    // giúp tự tung sau một nhịp — nhưng server vẫn là nơi quyết, và request mang
    // `turnId` nên lượt đã đổi là bị từ chối.
    final delay = Future<void>.delayed(Duration(seconds: _rules.fastAutoRollS));
    unawaited(
      delay.then((_) async {
        if (_stopped) return;
        await controller.autoRoll(turn.turnId);
      }),
    );
  }

  void dispose() => stop();
}