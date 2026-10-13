package vn.careermap.domain;

/**
 * Trạng thái hiện diện của một người chơi, suy ra từ heartbeat.
 *
 * <p>Thứ tự quan trọng: {@link #LEFT} thắng mọi trạng thái khác — người đã bấm
 * rời thì dù máy còn gửi heartbeat cũng không quay lại được (phòng đã bắt đầu
 * thì server chặn).
 */
public enum PresenceStatus {
  /** Vừa gửi heartbeat. */
  ONLINE,

  /** Hết heartbeat nhưng chưa tới ngưỡng vắng mặt. */
  OFFLINE,

  /** Mất kết nối từ {@code AWAY_AFTER_S} trở lên — bỏ qua lượt. */
  AWAY,

  /** Đã chủ động rời phòng. */
  LEFT
}