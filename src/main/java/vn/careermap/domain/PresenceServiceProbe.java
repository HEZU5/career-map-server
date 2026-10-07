package vn.careermap.domain;

import java.time.Instant;

/**
 * Cho {@code Room} một đường gọi ngược tới luật hiện diện mà không phụ thuộc
 * service layer (entity không nên biết service).
 */
public interface PresenceServiceProbe {
  /**
   * Người chơi này còn online trong {@code room} không.
   *
   * @param now thời điểm đánh giá (dùng chung cho cả phòng để một lượt chỉ có
   *     một "chân thời gian", tránh việc hai người bị phân xử ở hai thời điểm
   *     lệch nhau).
   */
  boolean isOnline(Room room, Player player, Instant now);
}