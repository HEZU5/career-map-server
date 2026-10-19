package vn.careermap.web.dto;

import java.time.Instant;

/**
 * Một dòng trong lịch sử thanh toán của người chơi.
 *
 * @param orderId     khoá đơn (= {@code vnp_TxnRef})
 * @param status      {@code PENDING} / {@code PAID} / {@code EXPIRED}
 * @param amount      số tiền (VND)
 * @param createdAt   lúc tạo đơn
 * @param expiresAt   mốc hết hạn thanh toán
 * @param hollandCode mã Holland tính từ {@code riasecJson}; {@code ""} nếu chưa có
 */
public record PaymentHistoryItem(
    String orderId, String status, long amount, Instant createdAt, Instant expiresAt, String hollandCode) {}