package vn.careermap.web.dto;

import java.time.Instant;

/**
 * Kết quả tra trạng thái một đơn thanh toán VNPay.
 *
 * @param orderId     khoá đơn (= {@code vnp_TxnRef})
 * @param status      {@code PENDING} / {@code PAID} / {@code EXPIRED}
 * @param amount      số tiền (VND)
 * @param expiresAt   mốc hết hạn thanh toán
 * @param hollandCode mã Holland tính từ {@code riasecJson}; {@code ""} nếu chưa có
 */
public record PaymentStatusResponse(
    String orderId, String status, long amount, Instant expiresAt, String hollandCode) {}
