package vn.careermap.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.config.VnpayConfig;
import vn.careermap.domain.PaymentOrder;
import vn.careermap.domain.PaymentStatus;
import vn.careermap.domain.Player;
import vn.careermap.repo.PaymentOrderRepository;
import vn.careermap.repo.PlayerRepository;
import vn.careermap.web.dto.CreatePaymentResponse;
import vn.careermap.web.dto.WinnerRequest;
import vn.careermap.web.dto.WinnerResponse;

/**
 * Tạo đơn thanh toán VNPay cho báo cáo Holland.
 *
 * <p>Server tự đặt số tiền ({@code payment.amount-vnd} = 50000) và hạn đơn
 * ({@code payment.ttl-minutes} = 15) — không bao giờ nhận từ client.
 *
 * <p>Khoá tra đơn VNPay là {@code vnp_TxnRef} = {@code PaymentOrder.id} (UUID
 * không dấu gạch ngắn, do server sinh). KHÔNG dùng {@code vnp_TxnNo} (do VNPay
 * gán).
 *
 * <p>Kiểm tra điều kiện thắng qua {@link GameService#checkWinner(WinnerRequest)}
 * vì {@code isEligibleToWin} là private trong GameService — không gọi trực
 * tiếp từ service khác.
 */
@Service
public class PaymentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

  private static final String SANDBOX_PAY_URL =
      "https://sandbox.vnpayment.vn/paymentgateway/paymenturl.htm";
  private static final DateTimeFormatter VNPAY_DATE_TIME =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
  private static final String VNP_COMMAND = "pay_web";

  private final GameRulesConfig rules;
  private final PlayerRepository playerRepository;
  private final PaymentOrderRepository orderRepository;
  private final GameService gameService;
  private final VnpaySignatureService signatureService;
  private final VnpayConfig vnpayConfig;

  public PaymentService(
      GameRulesConfig rules,
      PlayerRepository playerRepository,
      PaymentOrderRepository orderRepository,
      GameService gameService,
      VnpaySignatureService signatureService,
      VnpayConfig vnpayConfig) {
    this.rules = rules;
    this.playerRepository = playerRepository;
    this.orderRepository = orderRepository;
    this.gameService = gameService;
    this.signatureService = signatureService;
    this.vnpayConfig = vnpayConfig;
  }

  /**
   * Tạo hoặc tái sử dụng đơn thanh toán VNPay cho người chơi đủ điều kiện.
   *
   * @param playerKey khoá người chơi (không phải ID số)
   * @return thông tin đơn + URL thanh toán sandbox
   * @throws ResponseStatusException 403 nếu người chơi không tồn tại hoặc chưa
   *                                  đủ điều kiện thắng
   */
  @Transactional
  public CreatePaymentResponse createOrder(String playerKey) {
    Player player = playerRepository
        .findByPlayerKey(playerKey)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.FORBIDDEN, "Không tìm thấy người chơi '" + playerKey + "'"));

    WinnerResponse winner = gameService.checkWinner(new WinnerRequest(playerKey));
    if (!winner.eligibleToFinish()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Chưa đủ điều kiện mở báo cáo Holland");
    }

    Instant now = Instant.now();
    Optional<PaymentOrder> existing = orderRepository
        .findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(playerKey, PaymentStatus.PENDING);
    if (existing.isPresent() && !existing.get().isExpiredAt(now)) {
      return toResponse(existing.get());
    }

    PaymentOrder order = PaymentOrder.create(
        UUID.randomUUID().toString().replace("-", ""),
        playerKey,
        player.getRoom() == null ? null : player.getRoom().getCode(),
        rules.getPaymentAmountVnd(),
        rules.getPaymentTtlMinutes());
    orderRepository.save(order);
    return toResponse(order);
  }

  private CreatePaymentResponse toResponse(PaymentOrder order) {
    String createDate = VNPAY_DATE_TIME.format(order.getCreatedAt());
    String expireDate = VNPAY_DATE_TIME.format(order.getExpiresAt());

    Map<String, String> params = new LinkedHashMap<>();
    params.put("vnp_TmnCode", vnpayConfig.getTmnCode());
    params.put("vnp_TxnRef", order.getId());
    params.put("vnp_Amount", String.valueOf(order.getAmount() * 100));
    params.put("vnp_Command", VNP_COMMAND);
    params.put("vnp_CreateDate", createDate);
    params.put("vnp_ExpireDate", expireDate);
    params.put("vnp_CurrCode", "VND");
    params.put("vnp_Locale", "vn");
    params.put("vnp_ReturnUrl", vnpayConfig.getReturnUrl());
    params.put("vnp_IpnUrl", vnpayConfig.getIpnUrl());
    params.put("vnp_TxnExpireDate", expireDate);

    String payUrl = SANDBOX_PAY_URL + "?" + signatureService.signedQueryString(params);
    return new CreatePaymentResponse(order.getId(), payUrl, order.getExpiresAt());
  }

  /**
   * Xử lý IPN server-to-server từ VNPay.
   *
   * <p>Toàn bộ thân hàm được bọc try/catch để **không bao giờ** ném exception ra
   * ngoài — {@code ConflictAdvice} là {@code @RestControllerAdvice}, nếu exception
   * thoát ra ngoài sẽ bị trả 5xx và VNPay retry mãi. Luôn trả HTTP 200 với body
   * JSON {@code {"RspCode":...,"Message":...}} theo chuẩn VNPay.
   *
   * <p>Thứ tự: verify chữ ký → {@code vnp_TransactionStatus == "01"} → tra đơn
   * theo {@code vnp_TxnRef} → nếu đã PAID → kiểm tra số tiền → đánh dấu PAID +
   * lưu. IPN tới sau khi đơn EXPIRED vẫn nhận PAID (tiền đã vào).
   */
  @Transactional
  public String handleIpn(Map<String, String> params) {
    try {
      if (!signatureService.verify(params)) {
        return "{\"RspCode\":\"97\",\"Message\":\"Fail checksum\"}";
      }
      if (!"01".equals(params.get("vnp_TransactionStatus"))) {
        return "{\"RspCode\":\"99\",\"Message\":\"Unknown error\"}";
      }
      String txnRef = params.get("vnp_TxnRef");
      Optional<PaymentOrder> found = orderRepository.findById(txnRef);
      if (found.isEmpty()) {
        return "{\"RspCode\":\"01\",\"Message\":\"Order not found\"}";
      }
      PaymentOrder order = found.get();
      if (order.isPaid()) {
        return "{\"RspCode\":\"02\",\"Message\":\"Order already confirmed\"}";
      }
      String receivedAmount = params.get("vnp_Amount");
      long expectedAmount = order.getAmount() * 100L;
      if (receivedAmount == null || !receivedAmount.equals(String.valueOf(expectedAmount))) {
        return "{\"RspCode\":\"04\",\"Message\":\"Invalid amount\"}";
      }
      order.markPaid(Instant.now());
      orderRepository.save(order);
      return "{\"RspCode\":\"00\",\"Message\":\"Confirm Success\"}";
    } catch (Exception e) {
      log.warn("IPN xử lý thất bại: {}", e);
      return "{\"RspCode\":\"99\",\"Message\":\"Unknown error\"}";
    }
  }
}
