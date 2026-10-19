package vn.careermap.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.careermap.service.PaymentService;
import vn.careermap.web.dto.CreatePaymentRequest;
import vn.careermap.web.dto.CreatePaymentResponse;

/**
 * REST endpoint cho thanh toán VNPay.
 *
 * <p>POST /api/payments/create: tạo hoặc tái sử dụng đơn PENDING còn hạn,
 * trả payUrl sandbox + expiresAt.
 * <p>POST /api/payments/ipn: VNPay gọi server-to-server báo kết quả; luôn trả
 * HTTP 200 và bắt mọi exception trong service.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

  private final PaymentService paymentService;

  public PaymentController(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  @PostMapping("/create")
  public CreatePaymentResponse create(@RequestBody CreatePaymentRequest request) {
    return paymentService.createOrder(request.playerKey());
  }

  @PostMapping(value = "/ipn", consumes = "application/x-www-form-urlencoded", produces = "application/json")
  @ResponseStatus(HttpStatus.OK)
  public String ipn(@RequestParam MultiValueMap<String, String> params) {
    return paymentService.handleIpn(toMap(params));
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
