package vn.careermap.web;

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
 * và khó kiểm chứng. Ở đây ta trả <b>JSON 500</b> tường minh: đúng mã HTTP mà
 * VNPay cần để retry, và KHÔNG lộ chi tiết hạ tầng (URL DB, tên bảng, stack
 * trace).
 *
 * <p>Body KHÔNG có field {@code message} từ Spring Boot (xem
 * {@code server.error.include-message=never}) — ở đây {@code message} là câu
 * tiếng Việt do code chủ động viết, không phải nội dung exception.
 */
@RestControllerAdvice
public class PaymentInfrastructureAdvice {

  @ExceptionHandler(PaymentInfrastructureException.class)
  public ResponseEntity<Map<String, Object>> handle(PaymentInfrastructureException ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            Map.of(
                "status", HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "error", HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "message", "Không xử lý được thông báo thanh toán. Hãy thử lại sau."));
  }
}
