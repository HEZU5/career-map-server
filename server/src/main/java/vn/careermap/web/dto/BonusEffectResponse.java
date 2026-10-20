package vn.careermap.web.dto;

import java.util.List;

/**
 * Kết quả áp hiệu ứng lá bài Cơ hội / Thử thách.
 *
 * @param effect hiệu ứng đã áp (tên của {@code BonusEffect})
 * @param winPoints số Điểm Chiến thắng mà NGƯỜI CHƠI vừa rút lá nhận được —
 *     {@code 0} với lá chỉ di chuyển (đi thêm ô) vì lá đó thay bằng việc đi
 * @param fromPosition vị trí trước khi di chuyển; {@code null} nếu lá không làm
 *     quân di chuyển
 * @param toPosition vị trí sau khi di chuyển; {@code null} nếu lá không làm quân
 *     di chuyển
 * @param awardedPlayerKeys những người được cộng Điểm Chiến thắng (rỗng với lá
 *     chỉ di chuyển)
 * @param message câu thông báo tiếng Việt cho người chơi
 */
public record BonusEffectResponse(
    String effect,
    long winPoints,
    Integer fromPosition,
    Integer toPosition,
    List<String> awardedPlayerKeys,
    String message) {}
