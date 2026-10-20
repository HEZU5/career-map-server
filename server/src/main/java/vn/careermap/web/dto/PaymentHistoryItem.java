package vn.careermap.web.dto;

import java.time.Instant;

/**
 * Một dòng trong lịch sử thanh toán của người chơi — CHỈ METADATA.
 *
 * <p>KHÔNG có {@code riasecJson}: nội dung báo cáo Holland là thứ người chơi
 * TRẢ TIỀN mới được xem. Endpoint lịch sử tra theo {@code playerKey} — mà
 * {@code playerKey} là chuỗi đoán được ({@code host-<mã phòng>}) và mã phòng
 * nằm trong danh sách phòng công khai ⇒ trả kèm {@code riasecJson} ở đây là
 * lộ báo cáo của người khác (kể cả đơn chưa thanh toán).
 *
 * <p>Nội dung báo cáo chỉ đọc được qua {@code GET /api/payments/{orderId}?playerKey=}
 * — endpoint đó kiểm tra chủ đơn.
 *
 * @param orderId     khoá đơn (= {@code vnp_TxnRef})
 * @param status      {@code PENDING} / {@code PAID} / {@code EXPIRED}
 * @param amount      số tiền (VND)
 * @param createdAt   lúc tạo đơn
 * @param expiresAt   mốc hết hạn thanh toán
 * @param hollandCode mã Holland tính từ {@code riasecJson}; {@code ""} nếu chưa có
 */
public record PaymentHistoryItem(
    String orderId,
    String status,
    long amount,
    Instant createdAt,
    Instant expiresAt,
    String hollandCode) {}
