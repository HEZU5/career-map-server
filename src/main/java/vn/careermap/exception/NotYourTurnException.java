package vn.careermap.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class NotYourTurnException extends RuntimeException {
  public static final String MESSAGE = "Not your turn or turn already changed";

  public NotYourTurnException(String code) {
    super("Not your turn in room: " + code);
  }
}