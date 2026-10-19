package vn.careermap.web;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.careermap.exception.NotYourTurnException;

/**
 * Hai người gửi `roll-dice` cùng `expectedTurnId` trong cùng nhịp: cả hai đều
 * đọc phòng ở version cũ, cả hai vượt qua so khớp turnId, nhưng khi commit thì
 * `@Version` từ chối kẻ thua. Lỗi đó là dạng "lượt đã đổi" chứ không phải lỗi
 * hệ thống — trả 409 giống NotYourTurnException để máy người chơi im lặng bỏ qua.
 */
@RestControllerAdvice
public class ConflictAdvice {
  @ExceptionHandler(OptimisticLockingFailureException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public String conflict(OptimisticLockingFailureException ex) {
    return NotYourTurnException.MESSAGE;
  }
}