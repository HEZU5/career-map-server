package vn.careermap.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.careermap.service.PaymentService;
import vn.careermap.web.dto.CreatePaymentRequest;
import vn.careermap.web.dto.CreatePaymentResponse;
import vn.careermap.web.dto.PaymentHistoryItem;
import vn.careermap.web.dto.PaymentStatusResponse;

/**
 * REST endpoint cho thanh toán VNPay.
 *
 * <p>POST /api/payments/create: tạo hoặc tái sử dụng đơn PENDING còn hạn,
 * trả payUrl sandbox + expiresAt.
 * <p>GET /api/payments/history?playerKey=: CHỈ metadata (không có nội dung báo
 * cáo) — xem {@link PaymentHistoryItem}.
 * <p>POST /api/payments/ipn: VNPay gọi server-to-server báo kết quả.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

  private final PaymentService paymentService;

  public PaymentController(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  /**
   * Tạo đơn thanh toán.
   *
   * <p>IP người mua lấy từ header {@code X-Forwarded-For} (Render đặt sẵn) để
   * điền {@code vnp_IpAddr} — KHÔNG nhận IP từ body vì client có thể tự bịa.
   */
  @PostMapping("/create")
  public CreatePaymentResponse create(
      @RequestBody CreatePaymentRequest request,
      @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
    return paymentService.createOrder(request.playerKey(), firstIp(forwardedFor));
  }

  /**
   * Lịch sử thanh toán — CHỈ metadata, không có nội dung báo cáo.
   *
   * <p>{@code playerKey} là BẮT BUỘC (thiếu ⇒ 400): không có khoá thì không
   * tra được đơn nào.
   */
  @GetMapping("/history")
  public List<PaymentHistoryItem> history(@RequestParam String playerKey) {
    return paymentService.history(playerKey);
  }

  /** Tra trạng thái đơn — có kiểm tra chủ đơn (xem {@code PaymentService}). */
  @GetMapping("/{orderId}")
  public PaymentStatusResponse status(
      @PathVariable String orderId, @RequestParam String playerKey) {
    return paymentService.getStatus(orderId, playerKey);
  }

  /**
   * IPN server-to-server từ VNPay.
   *
   * <p>KHÔNG khai báo {@code consumes}: nếu khai {@code application/x-www-form-urlencoded}
   * thì một request sai Content-Type sẽ bị Spring trả <b>415</b> ⇒ VNPay coi là
   * lỗi server và retry mãi. Bỏ ràng buộc này thì request vẫn vào được handler
   * và luôn trả HTTP 200 (kể cả khi thiếu param) — đúng như tài liệu VNPay.
   *
   * <p>Lỗi HẠ TẦNG (DB) cố ý KHÔNG bị bắt ở đây: nó nổi lên ⇒ HTTP 5xx ⇒
   * VNPay retry. Xem {@code PaymentService.handleIpn}.
   */
  @PostMapping(value = "/ipn", produces = "application/json")
  @ResponseStatus(HttpStatus.OK)
  public String ipn(@RequestParam MultiValueMap<String, String> params) {
    return paymentService.handleIpn(toMap(params));
  }

  /** IP đầu tiên trong {@code X-Forwarded-For}; rỗng nếu không có header. */
  private static String firstIp(String forwardedFor) {
    if (forwardedFor == null) {
      return "";
    }
    int comma = forwardedFor.indexOf(',');
    String ip = comma >= 0 ? forwardedFor.substring(0, comma) : forwardedFor;
    return ip.trim();
  }

  private static Map<String, String> toMap(MultiValueMap<String, String> params) {
    Map<String, String> result = new LinkedHashMap<>();
    if (params == null) {
      return result;
    }
    for (Map.Entry<String, List<String>> entry : params.entrySet()) {
      List<String> values = entry.getValue();
      if (values != null && !values.isEmpty()) {
        result.put(entry.getKey(), values.get(0));
      }
    }
    return result;
  }
}
