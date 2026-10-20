package vn.careermap.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import vn.careermap.web.dto.PaymentHistoryItem;
import vn.careermap.web.dto.PaymentStatusResponse;
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
   * @throws ResponseStatusException 503 nếu thanh toán VNPay đang tắt, 403 nếu
   *                                  người chơi không tồn tại hoặc chưa đủ điều
   *                                  kiện thắng
   */
  @Transactional
  public CreatePaymentResponse createOrder(String playerKey) {
    // Thanh toán đang tắt -> KHÔNG tạo đơn rác (payUrl rỗng/không ký được).
    // Client đã ẩn nút mua khi vnpay.enabled=false, nhưng client cũ hoặc
    // gọi API trực tiếp vẫn tới được đây — chặn ở server là lớp chắn cuối.
    if (!vnpayConfig.isEnabled()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Thanh toán VNPay đang tạm thời không khả dụng. Vui lòng thử lại sau.");
    }

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
    // Chup diem RIASEC cua CHINH nguoi mua (khong phai tong cua ca phong), de bao
    // cao Holland la cua rieng ho.
    String riasecJson = toJson(player.getScores());
    if (riasecJson != null && !riasecJson.isEmpty()) {
      order.setRiasecJson(riasecJson);
    }
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

  /**
   * Tra trạng thái một đơn thanh toán của người chơi.
   *
   * <p>Nạp đơn theo {@code orderId} VÀ {@code playerKey}: nếu không khớp người
   * mua (đơn của người khác) → 404. Nếu đơn đang {@code PENDING} và đã quá hạn
   * thì đặt {@code EXPIRED} và lưu ngay (lazy expire, không dùng {@code @Scheduled}).
   *
   * @throws ResponseStatusException 404 nếu không tìm thấy đơn hoặc không thuộc
   *                                 người chơi này
   */
  @Transactional
  public PaymentStatusResponse getStatus(String orderId, String playerKey) {
    PaymentOrder order = orderRepository
        .findByIdAndPlayerKey(orderId, playerKey)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Không tìm thấy đơn thanh toán '" + orderId + "'"));

    Instant now = Instant.now();
    if (order.getStatus() == PaymentStatus.PENDING && now.isAfter(order.getExpiresAt())) {
      order.markExpired();
      orderRepository.save(order);
    }

    String riasecJson = order.getRiasecJson();
    if (riasecJson == null || riasecJson.trim().isEmpty()) {
      riasecJson = "";
    }
    return new PaymentStatusResponse(
        order.getId(),
        order.getStatus().name(),
        order.getAmount(),
        order.getExpiresAt(),
        hollandCode(order.getRiasecJson()),
        riasecJson);
  }

  /**
   * Lịch sử thanh toán của người chơi, đơn mới nhất trước, tối đa 100.
   */
  @Transactional
  public List<PaymentHistoryItem> history(String playerKey) {
    List<PaymentOrder> orders = orderRepository.findByPlayerKeyOrderByCreatedAtDesc(playerKey);
    List<PaymentHistoryItem> result = new ArrayList<>();
    int limit = Math.min(orders.size(), 100);
    Instant now = Instant.now();
    for (int i = 0; i < limit; i++) {
      PaymentOrder order = orders.get(i);
      if (order.getStatus() == PaymentStatus.PENDING && now.isAfter(order.getExpiresAt())) {
        order.markExpired();
        orderRepository.save(order);
      }
      String riasecJson = order.getRiasecJson();
      if (riasecJson == null || riasecJson.trim().isEmpty()) {
        riasecJson = "";
      }
      result.add(new PaymentHistoryItem(
          order.getId(),
          order.getStatus().name(),
          order.getAmount(),
          order.getCreatedAt(),
          order.getExpiresAt(),
          hollandCode(order.getRiasecJson()),
          riasecJson));
    }
    return result;
  }

  /**
   * Tính mã Holland từ chuỗi JSON chứa 6 điểm RIASEC.
   *
   * <p>Nhóm có điểm cao nhất → mã. Hoà theo thứ tự {@code R, I, A, S, E, C}
   * (R trước). Trả {@code ""} nếu {@code riasecJson} null/rỗng hoặc không đọc
   * được điểm nào.
   *
   * @param riasecJson chuỗi JSON dạng {@code {"R":4,"I":2,...}} (hoặc các biến
   *                   thể có khoảng trắng/dấu {@code =})
   * @return mã Holland một chữ cái, hoặc {@code ""}
   */
  public static String hollandCode(String riasecJson) {
    if (riasecJson == null || riasecJson.trim().isEmpty()) {
      return "";
    }
    String text = riasecJson.trim();

    // Thử đọc JSON trước.
    Map<String, Integer> scores = new LinkedHashMap<>();
    // Regex trích xuất từng cặp "KEY": number hoặc KEY=number / KEY: number
    // theo thứ tự ưu tiên các nhóm R,I,A,S,E,C.
    String[] groups = {"R", "I", "A", "S", "E", "C"};
    boolean foundAny = false;
    for (String group : groups) {
      Integer extracted = extractScore(text, group);
      if (extracted != null) {
        foundAny = true;
      }
      scores.put(group, extracted == null ? 0 : extracted);
    }

    // Nếu không đọc được bất kỳ key RIASEC hợp lệ nào → "".
    if (!foundAny) {
      return "";
    }

    String best = "R";
    int bestScore = scores.get("R");
    for (String group : groups) {
      int s = scores.get(group);
      if (s > bestScore) {
        bestScore = s;
        best = group;
      }
    }
    return best;
  }

  private static Integer extractScore(String text, String group) {
    // JSON: "R": 4  hoặc  "R":4
    java.util.regex.Matcher m = java.util.regex.Pattern
        .compile("\"" + group + "\"\\s*[:=]\\s*(-?\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
        .matcher(text);
    if (m.find()) {
      try {
        return Integer.parseInt(m.group(1));
      } catch (NumberFormatException ignored) {
      }
    }
    // Không có ngoặc kép: R:4 / R=4 / R 4
    m = java.util.regex.Pattern
        .compile("\\b" + group + "\\b\\s*[:=]\\s*(-?\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
        .matcher(text);
    if (m.find()) {
      try {
        return Integer.parseInt(m.group(1));
      } catch (NumberFormatException ignored) {
      }
    }
    // Không tìm thấy key hợp lệ.
    return null;
  }

  private static String toJson(java.util.List<vn.careermap.domain.Score> scores) {
    if (scores == null || scores.isEmpty()) {
      return null;
    }
    Map<String, Integer> snapshot = new LinkedHashMap<>();
    for (vn.careermap.domain.RiasecCategory c : vn.careermap.domain.RiasecCategory.values()) {
      snapshot.put(c.name(), 0);
    }
    for (vn.careermap.domain.Score s : scores) {
      if (s.getCategory() == null) continue;
      snapshot.merge(s.getCategory().name(), s.getPoints(), Integer::sum);
    }
    return toJson(snapshot);
  }
  private static String toJson(java.util.Map<String, Integer> snapshot) {
    if (snapshot == null || snapshot.isEmpty()) {
      return null;
    }
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Integer> e : snapshot.entrySet()) {
      if (!first) {
        sb.append(",");
      }
      first = false;
      sb.append("\"").append(e.getKey()).append("\":")
          .append(e.getValue() == null ? 0 : e.getValue());
    }
    return sb.append("}").toString();
  }
}
