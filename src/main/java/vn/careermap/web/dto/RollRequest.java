package vn.careermap.web.dto;

/**
 * @param playerId playerKey của người tung
 * @param expectedTurnId điều kiện compare-and-set: client gửi kèm turnId mà nó
 *     tin là hiện hành. Server so với {@code room.turnId}; lệch thì 409. Nhờ vậy
 *     khi máy người chơi và máy trọng tài cùng tự tung, chỉ một bên ghi được và
 *     bên kia im lặng bỏ qua thay vì tung hai lần.
 * @param auto true khi đây là lần tự tung do hết giờ (log và pill "tự động")
 * @param reason lý do tự tung: client-referee | sweep-timeout | offline-fast
 */
public record RollRequest(
    String playerId, Long expectedTurnId, Boolean auto, String reason) {

  /** Bấm tay: không kèm điều kiện CAS (luôn thuộc về người đang đến lượt). */
  public static RollRequest manual(String playerId) {
    return new RollRequest(playerId, null, Boolean.FALSE, "manual");
  }

  public boolean isAuto() {
    return Boolean.TRUE.equals(auto);
  }
}