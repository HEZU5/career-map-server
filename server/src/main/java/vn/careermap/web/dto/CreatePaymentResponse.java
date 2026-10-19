package vn.careermap.web.dto;

import java.time.Instant;

/**
 * Kết quả tạo đơn thanh toán VNPay: định danh đơn + URL thanh toán + hạn.
 *
 * @param orderId ID đơn = {@code vnp_TxnRef} (UUID không dấu gạch ngắn)
 * @param payUrl URL sandbox VNPay đã ký chữ ký HMAC-SHA256 (vnp_SecureHash)
 * @param expiresAt mốc hết hạn thanh toán; client đếm ngược từ mốc này
 */
public record CreatePaymentResponse(String orderId, String payUrl, Instant expiresAt) {}
