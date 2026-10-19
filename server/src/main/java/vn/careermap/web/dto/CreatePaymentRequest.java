package vn.careermap.web.dto;

/**
 * Yêu cầu tạo đơn thanh toán VNPay cho báo cáo Holland.
 *
 * @param playerKey khoá người chơi (không phải ID số); server tự kiểm tra
 *                  điều kiện thắng qua GameService.checkWinner
 */
public record CreatePaymentRequest(String playerKey) {}
