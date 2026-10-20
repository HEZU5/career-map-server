package vn.careermap.web;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.careermap.exception.NotYourTurnException;
import vn.careermap.exception.PlayerLeftException;
import vn.careermap.exception.RoomAlreadyStartedException;
import vn.careermap.exception.RoomClosedException;
import vn.careermap.exception.RoomFullException;
import vn.careermap.exception.RoomNotFoundException;
import vn.careermap.exception.TurnBusyException;

/**
 * Controller CHỈ dùng cho test: ném lỗi để kiểm tra body lỗi.
 *
 * <p>Đặt ở top-level (không phải nested class) vì Spring chỉ component-scan
 * các bean "independent" — nested class bị bỏ qua nên handler không được
 * đăng ký.
 *
 * <p>Chỉ được nạp vào context của {@code @WebMvcTest(controllers =
 * FailingTestController.class)}; các test slice khác bị
 * {@code WebMvcTypeExcludeFilter} loại bỏ.
 */
@RestController
public class FailingTestController {

  /** Chuỗi "bí mật" giả lập lo lắng của reviewer — KHÔNG được xuất hiện. */
  public static final String SECRET =
      "jdbc:postgresql://db.internal:5432/career_map refused (user=careerap, password=hunter2)";

  @GetMapping("/test/forbidden")
  void forbidden() {
    throw new ResponseStatusException(
        HttpStatus.FORBIDDEN, "Chưa đủ điều kiện mở báo cáo Holland");
  }

  @GetMapping("/test/unavailable")
  void unavailable() {
    throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "Thanh toán VNPay đang tạm thời không khả dụng.");
  }

  @GetMapping("/test/crash")
  void crash() {
    throw new IllegalStateException(SECRET + " / VNPAY_HASH_SECRET is null");
  }

  // --- 7 lớp exception gắn @ResponseStatus ở MỨC LỚP ---------------------
  // Mỗi endpoint ném đúng 1 lớp để kiểm tra advice trả body
  // {status, error, message} đọc status từ annotation @ResponseStatus.

  @GetMapping("/test/room-not-found")
  void roomNotFound() {
    throw new RoomNotFoundException("R12345");
  }

  @GetMapping("/test/room-full")
  void roomFull() {
    throw new RoomFullException("R12345");
  }

  @GetMapping("/test/room-already-started")
  void roomAlreadyStarted() {
    throw new RoomAlreadyStartedException("R12345");
  }

  @GetMapping("/test/room-closed")
  void roomClosed() {
    throw new RoomClosedException("R12345", null);
  }

  @GetMapping("/test/player-left")
  void playerLeft() {
    throw new PlayerLeftException("guest-R12345-1");
  }

  @GetMapping("/test/turn-busy")
  void turnBusy() {
    throw new TurnBusyException("R12345");
  }

  @GetMapping("/test/not-your-turn")
  void notYourTurn() {
    throw new NotYourTurnException("R12345");
  }

  /**
   * Xung đột optimistic-lock — do {@code ConflictAdvice} bắt, KHÔNG phải
   * {@code ResponseStatusExceptionAdvice}. Dùng để chứng minh 2 advice không
   * xung đột với nhau.
   */
  @GetMapping("/test/conflict")
  void conflict() {
    throw new OptimisticLockingFailureException("stale version");
  }
}
