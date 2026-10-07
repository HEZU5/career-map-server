package vn.careermap.config;

/**
 * Cách xử lý người chơi đã vắng mặt quá {@code AWAY_AFTER_S}.
 *
 * <p>Cài đầy đủ {@link #SKIP} (bỏ qua lượt, giữ nguyên quân/điểm). {@link #BOT} và
 * {@link #ELIMINATE} là chỗ cắm sẵn theo đúng kiểu strategy — thêm sau không
 * phải đụng vào chỗ gọi.
 */
public enum OfflinePolicy {
  /** Bỏ qua hẳn lượt: không tung, không mở thẻ, quân và điểm giữ nguyên. */
  SKIP,

  /** Trợ lý thay thế: bot tung + trả lời ngẫu nhiên, gắn nhãn bot. */
  BOT,

  /** Loại khỏi ván như thoát chủ động. */
  ELIMINATE;

  public static OfflinePolicy parse(String raw) {
    if (raw == null || raw.isBlank()) return SKIP;
    try {
      return valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      // Cấu hình sai không được làm sập server — rơi về hành vi an toàn nhất.
      return SKIP;
    }
  }
}