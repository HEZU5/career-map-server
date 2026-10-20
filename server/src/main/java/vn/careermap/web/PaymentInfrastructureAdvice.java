package vn.careermap.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.careermap.exception.PaymentInfrastructureException;

/**
 * Lỗi hạ tầng khi xử lý IPN VNPay phải trả HTTP 5xx để gateway retry.
 *
 * <p>Vì sao cần advice riêng: nếu để exception nổi lên, container sẽ tự
 * render trang lỗi HTML (hoặc body lỗi mặc định của Spring) — không ổn định
 * và khó kiểm chứng.
 *
 * <p><b>Body PHẢI có {@code RspCode}</b>: theo tài liệu VNPay, gateway quyết
 * định retry dựa trên {@code RspCode} TRONG BODY chứ không phải HTTP status.
 * {@code 00}/{@code 02} ⇒ kết thúc luồng; {@code 01}/{@code 04}/{@code 97}/
 * {@code 99} hoặc timeout ⇒ retry tối đa 10 lần, cách nhau 5 phút.
 *
 * <p>Vì vậy lỗi hạ tầng trả <b>HTTP 503</b> + body
 * {@code {"RspCode":"99","Message":"..."}}: vừa đúng nghĩa cho HTTP client,
 * vừa mang tín hiệu retry được tài liệu hoá cho VNPay. (Trả 5xx mà body trống
 * thì gateway không biết có nên retry hay không.)
 *
 * <p>Body KHÔNG có field {@code message} từ Spring Boot (xem
 * {@code server.error.include-message=never}) — ở đây {@code Message} là câu
 * tiếng Việt do code chủ động viết, không phải nội dung exception.
 */
@RestControllerAdvice
public class PaymentInfrastructureAdvice {

  @ExceptionHandler(PaymentInfrastructureException.class)
  public ResponseEntity<Map<String, Object>> handle(PaymentInfrastructureException ex) {
    Map<String, Object> body = new LinkedHashMap<>();
    // RspCode 99 => VNPAY bat co che retry IPN ( toi da 10 lan, cach nhau 5 phut).
    body.put("RspCode", "99");
    body.put("Message", "Không xử lý được thông báo thanh toán. Hãy thử lại sau.");
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
  }
}
