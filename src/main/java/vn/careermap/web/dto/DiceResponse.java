package vn.careermap.web.dto;

/**
 * @param moved `false` cho lần tung quyết định thứ tự đầu trận — quân đứng yên,
 *     KHÔNG chạy. Client dựa vào cờ này thay vì tự suy đoán theo giai đoạn
 *     (giai đoạn quyết định thứ tự có thể chưa kịp đồng bộ tới client khi
 *     người chơi bấm nút, gây ra quân nhảy lung tung).
 */
public record DiceResponse(int diceValue, int newPosition, boolean moved) {}