package vn.careermap.exception;

/**
 * Lỗi HẠ TẦNG khi xử lý IPN của VNPay (DB lạnh, connection reset, pool hết
 * kết nối...).
 *
 * <p>Vì sao cần lớp riêng: IPN của VNPay là yêu cầu server-to-server. Nếu lỗi
 * hạ tầng bị nuốt và trả HTTP 200 thì VNPay **KHÔNG** retry ⇒ đơn kẹt PENDING ⇒
 * hết 15 phút thành EXPIRED dù tiền ĐÃ vào tài khoản. Lỗi hạ tầng là lỗi TẠM
 * THỜI, đúng thứ cần trả 5xx để VNPay thử lại.
 *
 * <p>Ngược lại, lỗi NGHIỆP VỤ (chữ ký sai, đơn không tồn tại, status khác
 * {@code "01"}) trả HTTP 200 kèm {@code RspCode} tương ứng — xem
 * {@code PaymentService.handleIpn}.
 *
 * <p>Lớp này KHÔNG gắn {@code @ResponseStatus} và KHÔNG kế thừa
 * {@code ResponseStatusException} — cố ý, để không bị
 * {@code ResponseStatusExceptionAdvice} / {@code ConflictAdvice} bắt và biến
 * thành response 2xx/4xx. Nó nổi lên tầng HTTP ⇒ HTTP 500 ⇒ VNPay retry.
 */
public class PaymentInfrastructureException extends RuntimeException {

  public PaymentInfrastructureException(String message, Throwable cause) {
    super(message, cause);
  }
}
