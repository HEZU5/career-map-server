package vn.careermap.web.dto;

import java.time.Instant;

/**
 * Kết quả tra trạng thái một đơn thanh toán VNPay.
 *
 * <p>Endpoint này KIỂM TRA CHỦ ĐƠN ({@code findByIdAndPlayerKey}) ⇒ chỉ chủ
 * đơn mới đọc được {@code riasecJson}.
 *
 * <p><b>Nội dung báo cáo ({@code riasecJson}) là thứ ĐÃ TRẢ TIỀN</b> ⇒ CHỈ
 * được trả khi đơn đã {@code PAID}. Đơn {@code PENDING} / {@code EXPIRED} ⇒
 * {@code riasecJson} = {@code ""}. Nếu trả vô điều kiện thì người chơi tạo đơn
 * (chưa trả đồng nào) rồi gọi API là có báo cáo Holland MIỄN PHÍ — đúng thứ
 * 50.000đ phải mua.
 *
 * <p><b>Mã Holland ({@code hollandCode}, 3 chữ — 3 nhóm RIASEC điểm cao nhất)
 * thì LUÔN được trả</b>, kể cả đơn chưa thanh toán — chủ dự án chấp nhận
 * mức lộ này để người chơi vẫn thấy được mã của chính mình.
 *
 * @param orderId     khoá đơn (= {@code vnp_TxnRef})
 * @param status      {@code PENDING} / {@code PAID} / {@code EXPIRED}
 * @param amount      số tiền (VND)
 * @param createdAt   lúc tạo đơn
 * @param expiresAt   mốc hết hạn thanh toán
 * @param hollandCode mã Holland 3 chữ (3 nhóm RIASEC điểm cao nhất) tính từ
 *                    ảnh chụp RIASEC gốc; luôn có (kể cả đơn chưa thanh
 *                    toán), {@code ""} nếu không đọc được điểm nào
 * @param riasecJson  JSON 6 điểm RIASEC của báo cáo; CHỈ có khi đơn đã
 *                    {@code PAID}, còn lại là {@code ""}
 */
public record PaymentStatusResponse(
    String orderId,
    String status,
    long amount,
    Instant createdAt,
    Instant expiresAt,
    String hollandCode,
    String riasecJson) {}
