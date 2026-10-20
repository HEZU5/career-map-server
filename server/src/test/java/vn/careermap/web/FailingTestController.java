package vn.careermap.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
}
