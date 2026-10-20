package vn.careermap.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class NotYourTurnException extends RuntimeException {
  /** Câu trả về cho client khi có xung đột optimistic-lock (xem {@code ConflictAdvice}). */
  public static final String MESSAGE = "Chưa đến lượt của bạn hoặc lượt đã đổi";

  public NotYourTurnException(String code) {
    super("Chưa đến lượt của bạn trong phòng " + code);
  }
}