package vn.careermap.web;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import vn.careermap.exception.NotYourTurnException;
import vn.careermap.exception.PlayerLeftException;
import vn.careermap.exception.RoomAlreadyStartedException;
import vn.careermap.exception.RoomClosedException;
import vn.careermap.exception.RoomFullException;
import vn.careermap.exception.RoomNotFoundException;
import vn.careermap.exception.TurnBusyException;

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
 *
 * <p>Nhóm thứ hai: 7 lớp exception trong {@code vn.careermap.exception} gắn
 * {@link ResponseStatus} ở MỨC LỚP nên không kế thừa
 * {@link ResponseStatusException}. Trước đây chúng rơi xuống
 * {@code ResponseStatusExceptionResolver} → {@code sendError()} → body lỗi
 * mặc định của Spring, KHÔNG có field {@code message} → client chỉ thấy
 * "Server trả 409." thay vì lý do cụ thể. Handler {@link #handleRoomError}
 * bắt tường minh 7 lớp đó và trả body {@code {status, error, message}} y hệt.
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

  /**
   * Body lỗi cho 7 lớp exception gắn {@link ResponseStatus} ở MỨC LỚP.
   *
   * <p>Liệt kê TƯỜNG MINH 7 lớp (không dùng {@code RuntimeException.class}
   * để tránh bắt nhầm lỗi 500 nội bộ). HTTP status đọc từ chính annotation
   * {@code @ResponseStatus} bằng
   * {@link AnnotatedElementUtils#findMergedAnnotation(Class, Class)} — giống
   * hệt cách {@code ResponseStatusExceptionResolver} của Spring đọc — nên
   * không phải hardcode 404/409/410 ở đây.
   *
   * <p>{@code message} = {@code ex.getMessage()}: 7 lớp này đã ghi câu tiếng
   * Việt dành cho người chơi trong constructor.
   *
   * @param ex một trong 7 lớp exception được liệt kê ở {@code @ExceptionHandler}
   */
  @ExceptionHandler({
    RoomNotFoundException.class,
    RoomFullException.class,
    RoomAlreadyStartedException.class,
    RoomClosedException.class,
    PlayerLeftException.class,
    TurnBusyException.class,
    NotYourTurnException.class
  })
  public ResponseEntity<ApiError> handleRoomError(RuntimeException ex) {
    ResponseStatus annotated =
        AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class);
    HttpStatusCode status = annotated != null ? annotated.code() : HttpStatus.INTERNAL_SERVER_ERROR;
    return ResponseEntity.status(status)
        .body(new ApiError(status.value(), reasonPhrase(status), ex.getMessage()));
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
