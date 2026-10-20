package vn.careermap.web.dto;

/**
 * Yêu cầu áp hiệu ứng lá bài Cơ hội / Thử thách.
 *
 * <p>KHÔNG có field điểm: client không được tự khai số điểm nhận được. Server
 * tự tra bảng hiệu ứng theo {@code cardId} rồi tự tính — đó là điều làm cho
 * server trở thành nguồn sự thật (trước đây client khai {@code winPoints} rồi
 * server tin theo ⇒ ai cũng tự khai điểm tuỳ ý).
 *
 * @param playerId key của người chơi rút lá
 * @param cardId mã lá, CÓ DẤU, đúng như trong danh mục lá (vd {@code CƠ HỘI 03})
 * @param choice lựa chọn của người chơi; chỉ dùng cho lá {@code CƠ HỘI 10} —
 *     {@code PLUS_3} hoặc {@code MOVE_2}; lá khác để {@code null}
 * @param targetPlayerKey người được chọn cho lá {@code CƠ HỘI 04}; lá khác để
 *     {@code null}
 */
public record BonusEffectRequest(
    String playerId, String cardId, String choice, String targetPlayerKey) {}
