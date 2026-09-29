package vn.careermap.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class PlayerLeftException extends RuntimeException {
  public PlayerLeftException(String playerKey) {
    super("Player already left: " + playerKey);
  }
}