package vn.careermap.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MỘT file cấu hình duy nhất cho toàn bộ thời gian và luật vắng mặt.
 *
 * <p>Mọi hằng số thời gian của hệ thống nằm ở đây — không rải magic number trong
 * service/widget. Client đọc qua {@code GET /api/game/config} nên đổi giá trị ở
 * đây là áp dụng cho cả hai vế mà không phải sửa hai nơi.
 *
 * <p>Giá trị mặc định có thể ghi đè bằng biến môi trường (Render) mà không cần
 * build lại.
 */
@Component
public class GameRulesConfig {

  /** Lượt tung xúc xắc có bao lâu thì tự tung hộ. */
  public final long turnRollS;

  /** Mở thẻ Cơ hội/Thử thức/RIASEC có bao lâu thì tự bỏ qua. */
  public final long answerTimeoutS;

  /** Mất kết nối bao lâu thì coi là "vắng mặt" (bỏ qua lượt). */
  public final long awayAfterS;

  /** Máy người chơi gửi heartbeat mỗi bao lâu. */
  public final long heartbeatS;

  /** Quá bao lâu không có heartbeat thì coi là offline. */
  public final long offlineDetectS;

  /** Người đang offline thì tự tung sau bao lâu (không bắt cả bàn chờ 15s). */
  public final long fastAutoRollS;

  /** Không còn ai online bao lâu thì dọn phòng. */
  public final long emptyRoomTtlMin;

  /** Ván đã kết thúc giữ lại bao lâu để xem bảng kết quả. */
  public final long finishedRoomTtlMin;

  /** Chiến lược xử lý người vắng mặt: skip | bot | eliminate. */
  public final OfflinePolicy offlinePolicy;

  public GameRulesConfig(
      @Value("${game.turn-roll-s:15}") long turnRollS,
      @Value("${game.answer-timeout-s:60}") long answerTimeoutS,
      @Value("${game.away-after-s:120}") long awayAfterS,
      @Value("${game.heartbeat-s:5}") long heartbeatS,
      @Value("${game.offline-detect-s:15}") long offlineDetectS,
      @Value("${game.fast-auto-roll-s:2}") long fastAutoRollS,
      @Value("${game.empty-room-ttl-min:10}") long emptyRoomTtlMin,
      @Value("${game.finished-room-ttl-min:30}") long finishedRoomTtlMin,
      @Value("${game.offline-policy:skip}") String offlinePolicy) {
    this.turnRollS = turnRollS;
    this.answerTimeoutS = answerTimeoutS;
    this.awayAfterS = awayAfterS;
    this.heartbeatS = heartbeatS;
    this.offlineDetectS = offlineDetectS;
    this.fastAutoRollS = fastAutoRollS;
    this.emptyRoomTtlMin = emptyRoomTtlMin;
    this.finishedRoomTtlMin = finishedRoomTtlMin;
    this.offlinePolicy = OfflinePolicy.parse(offlinePolicy);
  }

  public GameRulesSnapshot snapshot() {
    return new GameRulesSnapshot(
        turnRollS,
        answerTimeoutS,
        awayAfterS,
        heartbeatS,
        offlineDetectS,
        fastAutoRollS,
        emptyRoomTtlMin,
        finishedRoomTtlMin,
        offlinePolicy.name().toLowerCase());
  }
}