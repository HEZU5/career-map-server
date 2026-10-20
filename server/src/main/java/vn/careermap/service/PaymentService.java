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
import vn.careermap.domain.Room;
import vn.careermap.exception.PaymentInfrastructureException;
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

  private static final DateTimeFormatter VNPAY_DATE_TIME =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
  private static final String VNP_COMMAND = "pay_web";

  /** Mô tả đơn gửi kèm cho VNPay ({@code vnp_OrderInfo}); tối đa 50 ký tự. */
  private static final String VNP_ORDER_INFO = "Báo cáo Holland";

  /**
   * Khoá tạo đơn theo {@code playerKey} — dải khoá (striped lock).
   *
   * <p>Vì sao: hai tab (hoặc hai lần bấm nhanh) cùng gọi {@code createOrder}
   * sẽ cùng đọc "chưa có đơn PENDING" rồi cùng tạo ⇒ HAI đơn PENDING cho
   * cùng một người. Client chỉ nhớ được một {@code pendingOrderId} nên sẽ poll
   * nhầm đơn (đơn thật đã PAID nhưng app đếm ngược đơn còn PENDING rồi báo
   * "hết hạn"), và người chơi có thể phải trả tiền HAI lần.
   *
   * <p>Dải khoá cố định ({@link #LOCK_STRIPES}) thay vì map theo {@code playerKey}
   * để bộ nhớ không phình theo số người chơi từng truy cập. Hai người khác
   * nhau có thể chung một khoá — chỉ là chậm hơn một chút, không sao.
   *
   * <p>Khoá ở tầng JVM: đủ cho quy mô hiện tại (một instance). Khi scale ra
   * nhiều instance thì cần thêm ràng buộc UNIQUE ở DB — nằm ngoài phạm vi
   * lần này (không có migration trong dự án).
   */
  private static final int LOCK_STRIPES = 64;

  private final Object[] orderLocks = new Object[LOCK_STRIPES];

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
    for (int i = 0; i < LOCK_STRIPES; i++) {
      orderLocks[i] = new Object();
    }
  }

  /**
   * Tạo hoặc tái sử dụng đơn thanh toán VNPay cho người chơi đủ điều kiện.
   *
   * @param playerKey khoá người chơi (không phải ID số)
   * @return thông tin đơn + URL thanh toán sandbox
   * @throws ResponseStatusException 503 nếu thanh toán VNPay đang tắt hoặc
   *                                  cấu hình chưa đủ (thiếu mã merchant /
   *                                  khoá bí mật / URL trả về / URL IPN),
   *                                  403 nếu người chơi không
   *                                  tồn tại, ván chưa kết thúc, đã kết thúc quá
   *                                  lâu (phòng đã bị dọn) hoặc chưa đủ điều
   *                                  kiện thắng
   */
  @Transactional
  public CreatePaymentResponse createOrder(String playerKey) {
    return createOrder(playerKey, "");
  }

  /**
   * Tạo hoặc tái sử dụng đơn thanh toán VNPay — kèm IP người mua để điền
   * {@code vnp_IpAddr}.
   *
   * @param playerKey khoá người chơi (không phải ID số)
   * @param clientIp  IP người mua (lấy từ header {@code X-Forwarded-For}); rỗng
   *                  thì không gửi {@code vnp_IpAddr} (chuẩn VNPay cũng vậy)
   * @return thông tin đơn + URL thanh toán
   */
  @Transactional
  public CreatePaymentResponse createOrder(String playerKey, String clientIp) {
    // Thanh toán đang tắt -> KHÔNG tạo đơn rác (payUrl rỗng/không ký được).
    // Client đã ẩn nút mua khi vnpay.enabled=false, nhưng client cũ hoặc
    // gọi API trực tiếp vẫn tới được đây — chặn ở server là lớp chắn cuối.
    if (!vnpayConfig.isEnabled()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Thanh toán VNPay đang tạm thời không khả dụng. Vui lòng thử lại sau.");
    }
    // Bật cờ nhưng cấu hình chưa đủ -> chặn ở CỬA, TRƯỚC khi đụng bất kỳ
    // thứ gì. Nếu để lọt:
    //  - thiếu HashSecret -> VnpaySignatureService ném lỗi LÚC ký payUrl,
    //    tức là SAU khi orderRepository.save(order) -> còn đơn rác PENDING.
    //  - thiếu ReturnUrl  -> người chơi thanh toán xong không được đưa về app.
    //  - thiếu IpnUrl     -> server KHÔNG nhận IPN -> đơn kẹt PENDING -> hết
    //    15 phút thành EXPIRED dù người chơi ĐÃ trả tiền.
    if (!vnpayConfig.isConfigured()) {
      String missing = String.join(", ", vnpayConfig.missingSettings());
      log.warn("VNPay đang bật nhưng cấu hình chưa đủ (thiếu: {}) - chặn tạo đơn.", missing);
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Thanh toán VNPay chưa được cấu hình đầy đủ (thiếu " + missing
              + "). Vui lòng thử lại sau.");
    }

    Player player = playerRepository
        .findByPlayerKey(playerKey)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.FORBIDDEN, "Không tìm thấy người chơi '" + playerKey + "'"));

    // Ván CHƯA kết thúc thì không bán: điểm lúc đó còn đang dở dang, mua xong
    // báo cáo lại sai (và tiền cũng mất).
    Room room = player.getRoom();
    if (room == null) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Bạn không còn ở phòng nào — ván đã kết thúc quá lâu.");
    }
    if (!room.isFinished()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Ván chưa kết thúc — hãy chờ ván kết thúc rồi mở báo cáo.");
    }

    WinnerResponse winner = gameService.checkWinner(new WinnerRequest(playerKey));
    if (!winner.eligibleToFinish()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Chưa đủ điều kiện mở báo cáo Holland");
    }

    // Diem RIASEC lay tu ANH CHUP theo TUNG NGUOI CHOI, chup luc ket thuc van.
    // Phong bi don sau 30 phut => khong con anh chup => bao cao se rong.
    // Tra loi 403 ro rang thay vi im lang tao bao cao rong (nguoi cho da tra tien).
    String riasecJson = room.getRiasecSnapshotOf(playerKey);
    if (riasecJson == null || riasecJson.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Ván đã kết thúc quá lâu — dữ liệu báo cáo không còn. Hãy chơi ván mới nhé.");
    }

    // Khoá theo playerKey: hai tab bấm "Nhan bao cao" cung luc thi chi tao
    // MOT don PENDING (client chi nho duoc mot pendingOrderId).
    synchronized (lockFor(playerKey)) {
      Instant now = Instant.now();
      Optional<PaymentOrder> existing = orderRepository
          .findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(playerKey, PaymentStatus.PENDING);
      if (existing.isPresent() && !existing.get().isExpiredAt(now)) {
        return toResponse(existing.get(), clientIp);
      }

      PaymentOrder order = PaymentOrder.create(
          UUID.randomUUID().toString().replace("-", ""),
          playerKey,
          room.getCode(),
          rules.getPaymentAmountVnd(),
          rules.getPaymentTtlMinutes());
      order.setRiasecJson(riasecJson);
      orderRepository.save(order);
      return toResponse(order, clientIp);
    }
  }

  /** Khoá tạo đơn của một người chơi (dải khoá theo hash của `playerKey`). */
  private Object lockFor(String playerKey) {
    int hash = playerKey == null ? 0 : playerKey.hashCode();
    int index = Math.floorMod(hash, LOCK_STRIPES);
    return orderLocks[index];
  }

  private CreatePaymentResponse toResponse(PaymentOrder order, String clientIp) {
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
    // vnp_OrderInfo: BAT BUOC voi lenh pay_web.
    params.put("vnp_OrderInfo", VNP_ORDER_INFO);
    // vnp_IpAddr: IP nguoi mua. Khong co header X-Forwarded-For thi BO QUA
    // (gia tri rong se bi loc khoi chu ky nhu chuan VNPay).
    if (clientIp != null && !clientIp.isBlank()) {
      params.put("vnp_IpAddr", clientIp.trim());
    }
    // vnp_SecureHashType: chi gui khi duoc cau hinh. Khong gui thi gateway
    // co the tu mac dinh SHA512 => hash lech => gateway tu choi don.
    String hashType = vnpayConfig.getHashType();
    if (hashType != null && !hashType.isBlank()) {
      params.put("vnp_SecureHashType", hashType.trim());
    }
    // KHONG gui vnp_TxnExpireDate: KHONG phai param chuan cua API cu
    // paymentgateway/paymenturl.htm. Gateway co the loai param la truoc khi
    // tu tinh hash => hash cua ta != hash cua VNPay => bi tu choi.

    String payUrl = vnpayConfig.getBaseUrl() + "?" + signatureService.signedQueryString(params);
    return new CreatePaymentResponse(order.getId(), payUrl, order.getExpiresAt());
  }

  /**
   * Xử lý IPN server-to-server từ VNPay.
   *
   * <p>Quy tắc (ruling mới, đảo ngược ràng buộc cũ "luôn trả HTTP 200"):
   *
   * <ul>
   *   <li>Lỗi NGHIỆP VỤ (chữ ký sai, đơn không tồn tại, status khác
   *       {@code "01"}, đã PAID, sai số tiền) → HTTP <b>200</b> kèm
   *       {@code RspCode} tương ứng. Không retry cũng được vì đơn này không
   *       bao giờ thành PAID.</li>
   *   <li>Lỗi HẠ TẦNG (DB lạnh, connection reset, pool hết) → để exception
   *       ném ra ⇒ HTTP <b>5xx</b> ⇒ VNPay retry. Đây là thứ phải retry:
   *       tiền ĐÃ vào mà đơn kẹt PENDING thì người chơi mất tiền.</li>
   * </ul>
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
      // Lỗi hạ tầng (DB) hoặc lỗi không lường trước: KHÔNG được trả 200.
      // Trả 200 ở đây = nuốt lỗi = VNPay không retry = đơn kẹt PENDING =
      // hết hạn EXPIRED dù tiền đã vào.
      log.error("IPN xử lý thất bại (lỗi hạ tầng) — trả 5xx để VNPay retry.", e);
      throw new PaymentInfrastructureException("Lỗi hạ tầng khi xử lý IPN VNPay", e);
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
        order.getCreatedAt(),
        order.getExpiresAt(),
        hollandCode(order.getRiasecJson()),
        riasecJson);
  }

  /**
   * Lịch sử thanh toán của người chơi, đơn mới nhất trước, tối đa 100.
   *
   * <p>CHỈ trả METADATA — KHÔNG trả {@code riasecJson}.
   *
   * <p>Vì sao: {@code playerKey} là chuỗi ĐOÁN ĐƯỢC ({@code host-<mã phòng>})
   * và mã phòng nằm trong danh sách phòng CÔNG KHAI ({@code GET /api/rooms}).
   * Trước đây endpoint này trả cả {@code riasecJson} ⇒ bất kỳ ai cũng đọc
   * được báo cáo Holland của người khác — kể cả đơn CHƯA thanh toán (tức nội
   * dung đã trả tiền bị lộ miễn phí). Nay endpoint chỉ còn metadata; nội dung
   * báo cáo chỉ đọc được qua {@code GET /api/payments/{orderId}?playerKey=} —
   * endpoint này KIỂM TRA CHỦ ĐƠN ({@code findByIdAndPlayerKey}).
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
      result.add(new PaymentHistoryItem(
          order.getId(),
          order.getStatus().name(),
          order.getAmount(),
          order.getCreatedAt(),
          order.getExpiresAt(),
          hollandCode(order.getRiasecJson())));
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
}
