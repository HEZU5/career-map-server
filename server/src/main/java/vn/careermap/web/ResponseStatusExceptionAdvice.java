package vn.careermap.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Body lỗi cho {@link ResponseStatusException} — loại lỗi do CHÍNH code chủ
 * động ném ra kèm câu tiếng Việt dành cho người chơi (vd "Chưa đủ điều
 * kiện mở báo cáo Holland", "Thanh toán VNPay đang tạm thời không khả dụng").
 *
 * <p>Vì sao cần class này: Spring Boot mặc định
 * {@code server.error.include-message=never} → body lỗi KHÔNG có field
 * {@code message}, client chỉ thấy "Server trả 403.". Bật khoá đó lên
 * {@code always} lại làm lộ nội dung exception nội bộ trong body lỗi 500
 * (JDBC URL + user DB, tên bảng/constraint SQL, tên biến môi trường). Class
 * này thay thế khoá toàn cục: CHỈ {@code ResponseStatusException} mới có field
 * {@code message}; mọi lỗi khác (kể cả 500) giữ nguyên body mặc định của
 * Spring — KHÔNG có {@code message}.
 *
 * <p>Không cần {@code @Order}/{@code @Priority}: Spring chọn handler theo KIỂU
 * exception, nên không xung đột với {@link ConflictAdvice} (class kia bắt
 * {@code OptimisticLockingFailureException}).
 */
@RestControllerAdvice
public class ResponseStatusExceptionAdvice {

  /**
   * Trả body {@code {"status":..,"error":..,"message":..}} với HTTP status
   * đúng bằng {@code ex.getStatusCode()}.
   *
   * @param ex lỗi do code chủ động ném, {@code getReason()} là câu tiếng Việt
   *            dành cho người chơi (có thể null)
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiError> handle(ResponseStatusException ex) {
    HttpStatusCode status = ex.getStatusCode();
    return ResponseEntity.status(status)
        .body(new ApiError(status.value(), reasonPhrase(status), ex.getReason()));
  }

  private static String reasonPhrase(HttpStatusCode status) {
    if (status instanceof HttpStatus httpStatus) {
      return httpStatus.getReasonPhrase();
    }
    HttpStatus resolved = HttpStatus.resolve(status.value());
    return resolved != null ? resolved.getReasonPhrase() : status.toString();
  }

  /**
   * Body lỗi JSON.
   *
   * @param status mã HTTP (vd 403)
   * @param error câu mô tả HTTP tiếng Anh (vd "Forbidden")
   * @param message câu tiếng Việt cho người chơi, null nếu exception không có
   *               reason
   */
  public record ApiError(int status, String error, String message) {}
}
