package vn.careermap.web.dto;

import java.time.Instant;

/**
 * Kết quả tạo đơn thanh toán: định danh đơn, thông tin thanh toán và hạn.
 *
 * @param orderId ID đơn = {@code vnp_TxnRef} (UUID không dấu gạch ngắn)
 * @param payUrl URL chuyển hướng VNPay; null với chuyển khoản QR
 * @param expiresAt mốc hết hạn thanh toán; client đếm ngược từ mốc này
 */
public record CreatePaymentResponse(
    String orderId,
    String payUrl,
    Instant expiresAt,
    String paymentMethod,
    String paymentCode,
    String qrUrl,
    String bankName,
    String accountNumber,
    String accountHolder) {}
