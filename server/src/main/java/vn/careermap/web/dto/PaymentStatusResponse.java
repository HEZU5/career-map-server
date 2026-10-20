package vn.careermap.web.dto;

import java.time.Instant;

/**
 * Kết quả tra trạng thái một đơn thanh toán VNPay.
 *
 * <p>Endpoint này KIỂM TRA CHỦ ĐƠN ({@code findByIdAndPlayerKey}) ⇒ chỉ chủ
 * đơn mới đọc được {@code riasecJson} (nội dung báo cáo đã trả tiền).
 *
 * @param orderId     khoá đơn (= {@code vnp_TxnRef})
 * @param status      {@code PENDING} / {@code PAID} / {@code EXPIRED}
 * @param amount      số tiền (VND)
 * @param createdAt   lúc tạo đơn
 * @param expiresAt   mốc hết hạn thanh toán
 * @param hollandCode mã Holland tính từ {@code riasecJson}; {@code ""} nếu chưa có
 * @param riasecJson  JSON 6 điểm RIASEC của báo cáo; {@code ""} nếu chưa có
 */
public record PaymentStatusResponse(
    String orderId,
    String status,
    long amount,
    Instant createdAt,
    Instant expiresAt,
    String hollandCode,
    String riasecJson) {}
