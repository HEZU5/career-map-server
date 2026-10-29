package vn.careermap.web.dto;

/** Tóm tắt giao dịch thanh toán để hiển thị trong trang quản trị.
 *
 * <p>KHÔNG chứa {@code riasecJson} để đảm bảo bảo mật thông tin khách hàng
 * (không lộ nội dung báo cáo Holland). */
public record PaymentSummary(
    String orderId,
    String playerKey,
    String roomCode,
    long amount,
    String status,
    String createdAt,
    String paidAt) {}
