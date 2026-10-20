package vn.careermap.web.dto;

/**
 * @param moved `false` cho lần tung quyết định thứ tự đầu trận — quân đứng yên,
 *     KHÔNG chạy. Client dựa vào cờ này thay vì tự suy đoán theo giai đoạn
 *     (giai đoạn quyết định thứ tự có thể chưa kịp đồng bộ tới client khi
 *     người chơi bấm nút, gây ra quân nhảy lung tung).
 * @param auto true nếu lượt này do hết giờ mà tự tung — client hiện pill
 *     "tự động tung" và giữ hiệu ứng khác với lượt bấm tay.
 * @param turnId turnId sau khi xử lý, để client biết lượt đã chuyển.
 * @param gameFinished true nếu ván vừa kết thúc NGAY trong lần tung này — người
 *     chơi đã đủ điều kiện và vừa đi qua ô START. Client mở màn kết quả ngay
 *     mà không phải chờ sự kiện WebSocket {@code FINISHED} tới.
 * @param winnerKey playerKey của người thắng khi {@code gameFinished} = true;
 *     null nếu ván kết thúc mà không có ai thắng.
 */
public record DiceResponse(
    int diceValue,
    int newPosition,
    boolean moved,
    boolean auto,
    long turnId,
    boolean gameFinished,
    String winnerKey) {

  /**
   * Lần tung bình thường: ván chưa kết thúc, chưa có người thắng.
   *
   * <p>Giữ call site cũ (chỉ truyền 5 tham số) không phải sửa — cờ kết thúc và
   * người thắng luôn mặc định là {@code false} / {@code null}.
   */
  public DiceResponse(int diceValue, int newPosition, boolean moved, boolean auto, long turnId) {
    this(diceValue, newPosition, moved, auto, turnId, false, null);
  }
}
