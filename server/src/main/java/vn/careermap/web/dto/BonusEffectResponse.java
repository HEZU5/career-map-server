package vn.careermap.web.dto;

import java.util.List;

/**
 * Kết quả áp hiệu ứng lá bài Cơ hội / Thử thách.
 *
 * @param effect hiệu ứng đã áp (tên của {@code BonusEffect}), hoặc {@link
 *     #EFFECT_SKIPPED} khi người chơi báo cáo bỏ qua lá
 * @param winPoints số Điểm Chiến thắng mà NGƯỜI CHƠI vừa rút lá nhận được —
 *     {@code 0} với lá chỉ di chuyển (đi thêm ô) vì lá đó thay bằng việc đi;
 *     {@code 0} khi bỏ qua lá
 * @param fromPosition vị trí trước khi di chuyển; {@code null} nếu lá không làm
 *     quân di chuyển
 * @param toPosition vị trí sau khi di chuyển; {@code null} nếu lá không làm quân
 *     di chuyển
 * @param awardedPlayerKeys những người được cộng Điểm Chiến thắng (rỗng với lá
 *     chỉ di chuyển, và rỗng khi bỏ qua lá)
 * @param message câu thông báo tiếng Việt cho người chơi
 */
public record BonusEffectResponse(
    String effect,
    long winPoints,
    Integer fromPosition,
    Integer toPosition,
    List<String> awardedPlayerKeys,
    String message) {

  /**
   * Mã hiệu ứng trả về khi người chơi BÁO CÁO bỏ qua lá (nút "Không hoàn thành").
   *
   * <p>Không phải hiệu ứng lá nào — chỉ là kết quả của việc bỏ qua: không áp
   * hiệu ứng, không cộng điểm, nhưng lượt vẫn đóng.
   */
  public static final String EFFECT_SKIPPED = "SKIPPED";
}
