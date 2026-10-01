package vn.careermap.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Lượt đang bận (đã tung và chưa trả lời xong ô) — không được tung tiếp. */
@ResponseStatus(HttpStatus.CONFLICT)
public class TurnBusyException extends RuntimeException {
  public TurnBusyException(String code) {
    super("Turn is busy in room: " + code);
  }
}
