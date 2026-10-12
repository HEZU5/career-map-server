package vn.careermap.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Phòng đã bị xóa (chủ phòng đóng, hoặc hết TTL).
 *
 * <p>Cần phân biệt với {@link RoomNotFoundException}: "không có phòng này" và
 * "phòng vừa bị đóng" là hai tình huống khác nhau với người chơi — cần một màn
 * hướng dẫn khác nhau, và client phải dừng mọi timer thay vì tiếp tục poll.
 */
@ResponseStatus(HttpStatus.GONE)
public class RoomClosedException extends RuntimeException {
  public RoomClosedException(String code, String reason) {
    super(reason == null || reason.isBlank() ? "Phòng đã đóng" : reason);
  }
}