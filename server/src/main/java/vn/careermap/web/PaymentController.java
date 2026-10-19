package vn.careermap.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.careermap.service.PaymentService;
import vn.careermap.web.dto.CreatePaymentRequest;
import vn.careermap.web.dto.CreatePaymentResponse;

/**
 * REST endpoint cho thanh toán VNPay.
 *
 * <p>POST /api/payments/create: tạo hoặc tái sử dụng đơn PENDING còn hạn,
 * trả payUrl sandbox + expiresAt.
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
}
