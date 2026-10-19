package vn.careermap.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
}
