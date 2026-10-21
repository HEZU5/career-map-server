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
import java.util.stream.Collectors;
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

  /**
   * Mô tả đơn gửi kèm cho VNPay ({@code vnp_OrderInfo}).
   *
   * <p>CHUẨN VNPay: tiếng Việt KHÔNG DẤU và không ký tự đặc biệt (mọi giá trị
   * gửi đi đều phải vậy). Nên dùng ASCII không dấu, không khoảng trắng:
   * {@code "Bao cao Holland"}.
   */
  private static final String VNP_ORDER_INFO = "Bao cao Holland";

  /**
   * IP dùng cho {@code vnp_IpAddr} khi không đọc được IP client.
   *
   * <p>Gateway API 2.1.0 coi {@code vnp_IpAddr} là tham số BẮT BUỘC ⇒ không
   * được bỏ hẳn tham số. {@code 127.0.0.1} là giá trị IP trong mẫu chính
   * thức của VNPay (dùng cho đơn tạo từ server).
   */
  private static final String VNPAY_FALLBACK_IP = "127.0.0.1";

  /**
   * Tên tham số chứa chữ ký — giá trị của nó KHÔNG BAO GIỜ được ghi log (che
   * lại bằng {@code ***}).
   *
   * <p>So khớp KHÔNG phân biệt hoa thường: kẻ xấu gửi {@code vnp_securehash}
   * cũng phải bị che — nếu chỉ che đúng tên thì chỉ cần đổi tên là lộ chữ
   * ký hợp lệ.
   */
  private static final String SECURE_HASH_PARAM = "vnp_SecureHash";

  /** Tên tham số chứa mã đơn — giá trị của nó cũng phải escape + cắt khi log. */
  private static final String TXN_REF_PARAM = "vnp_TxnRef";

  /**
   * Hình dạng chữ ký VNPay: hex 64 ký tự (HMAC-SHA256) hoặc 128 ký tự
   * (HMAC-SHA512).
   *
   * <p>Dùng làm lớp che CUỐI: kẻ xấu đổi TÊN tham số chữ ký HOÀN TOÀN (vd
   * {@code vnp_h4sh}) thì không còn tên nào giống {@code vnp_SecureHash} để so
   * khớp, nhưng giá trị vẫn là chữ ký hợp lệ của bộ tham số gốc ⇒ in ra là
   * lộ chữ ký ⇒ kẻ xấu dò được {@code HashSecret} offline.
   */
  private static final java.util.regex.Pattern SIGNATURE_SHAPE =
      java.util.regex.Pattern.compile("^(?:[0-9a-fA-F]{64}|[0-9a-fA-F]{128})$");

  /**
   * Số ký tự tối đa cho một TÊN hoặc GIÁ TRỊ tham số khi ghi log (tránh log
   * phình vô hạn).
   */
  private static final int LOG_PARAM_VALUE_MAX = 120;

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
   *                  hoặc sai dạng thì dùng fallback {@code 127.0.0.1} — tham số
   *                  {@code vnp_IpAddr} là BẮT BUỘC với API 2.1.0 nên không
   *                  được bỏ hẳn
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
    params.put("vnp_Command", vnpayConfig.getCommand());
    params.put("vnp_CreateDate", createDate);
    params.put("vnp_ExpireDate", expireDate);
    params.put("vnp_CurrCode", "VND");
    params.put("vnp_Locale", "vn");
    params.put("vnp_ReturnUrl", vnpaySafe(vnpayConfig.getReturnUrl(), "vnp_ReturnUrl"));
    params.put("vnp_IpnUrl", vnpaySafe(vnpayConfig.getIpnUrl(), "vnp_IpnUrl"));
    // vnp_OrderInfo: BAT BUOC (ca pay_web lan pay). ASCII khong dau; khoang
    // trang thi gateway van chap nhan (mau chinh thuc cua VNPay cung vay).
    params.put("vnp_OrderInfo", VNP_ORDER_INFO);
    // vnp_Version / vnp_OrderType: BAT BUOC voi API 2.1.0 (vnp_Command=pay).
    // RONG = khong gui (API cu khong co hai tham so nay).
    putIfNotBlank(params, "vnp_Version", vnpayConfig.getVersion());
    putIfNotBlank(params, "vnp_OrderType", vnpayConfig.getOrderType());
    // vnp_IpAddr: IP nguoi mua. KHONG BO HANG tham so nay: gateway 2.1.0 coi
    // vnp_IpAddr la tham so BAT BUOC (thieu => "Dữ liệu gửi sang không đúng
    // định dạng" => don bi tu choi). Khong doc duoc IP client => fallback
    // 127.0.0.1 (gia tri IP trong mau chinh thuc cua VNPay) + log de biet.
    String ip = vnpayIp(clientIp);
    if (ip.isEmpty()) {
      log.warn("Khong doc duoc IP client - gui vnp_IpAddr=127.0.0.1 (fallback) cho VNPay.");
      ip = VNPAY_FALLBACK_IP;
    }
    params.put("vnp_IpAddr", ip);
    // vnp_SecureHashType: chi gui khi duoc cau hinh. Khong gui thi gateway
    // co the tu mac dinh SHA512 => hash lech => gateway tu choi don.
    // API 2.1.0 KHONG duoc gui tham so nay (tai lieu VNPay) => bo qua.
    // (Yeu cau cua Ruling C: tham so nay cung bi loai khoi chuoi bam, nghien la
    // no cung khong bao gio duoc ghep vao query gui len gateway.)
    String hashType = vnpayConfig.getHashType();
    if (hashType != null && !hashType.isBlank() && vnpayConfig.allowsSecureHashType()) {
      params.put("vnp_SecureHashType", hashType.trim());
    }
    // KHONG gui vnp_TxnExpireDate: KHONG phai param chuan cua API cu
    // paymentgateway/paymenturl.htm. Gateway co the loai param la truoc khi
    // tu tinh hash => hash cua ta != hash cua VNPay => bi tu choi.

    String payUrl = vnpayConfig.getBaseUrl() + "?" + signatureService.signedQueryString(params);
    return new CreatePaymentResponse(order.getId(), payUrl, order.getExpiresAt());
  }

  /** Chỉ thêm tham số khi giá trị không rỗng (rỗng thì KHÔNG gửi lên VNPay). */
  private static void putIfNotBlank(Map<String, String> params, String key, String value) {
    if (value != null && !value.isBlank()) {
      params.put(key, value.trim());
    }
  }

  /**
   * Mô tả bộ tham số IPN nhận được để ghi log chẩn đoán chữ ký.
   *
   * <p>Vì sao cần: IPN sai chữ ký bị từ chối với {@code RspCode=97} ⇒ theo
   * tài liệu VNPay, VNPay CÓ retry (tối đa 10 lần, cách nhau 5 phút), nhưng
   * nếu nguyên nhân lệch chữ ký không được sửa thì đơn vẫn kẹt PENDING ⇒ tiền
   * đã vào mà đơn không bao giờ PAID. Không có dấu vết thì không chẩn đoán được
   * chữ ký lệch ở đâu khi chạy giao dịch thật.
   *
   * <p>Đủ để người vận hành DỰNG LẠI chuỗi băm: đây là danh sách key + giá
   * trị (đã giải mã) của mọi tham số nhận được, theo đúng thứ tự alphabet mà
   * {@link VnpaySignatureService} dùng khi băm.
   *
   * <p>An toàn:
   *
   * <ul>
   *   <li>Giá trị {@code vnp_SecureHash} bị CHE ({@code ***}) — in ra nó là lộ
   *       một chữ ký hợp lệ ⇒ kẻ xấu dò được HashSecret offline. Che theo
   *       TÊN (không phân biệt hoa thường) lẫn theo GIÁ TRỊ — xem
   *       {@link #maskValueForLog}.</li>
   *   <li>TÊN và GIÁ TRỊ quá dài bị cắt còn {@value #LOG_PARAM_VALUE_MAX} ký tự
   *       để log không phình vô hạn.</li>
   *   <li>TÊN tham số cũng được escape + cắt y hệt giá trị — vì tên tham số
   *       CŨNG do client kiểm soát được (container decode
   *       {@code ?foo%0A%5BINFO%5D=x=1} ⇒ tên chứa newline) ⇒ in thô là mời
   *       chèn DÒNG LOG GIẢ. Xem {@link #maskKeyForLog}.</li>
   * </ul>
   */
  private static String describeParams(Map<String, String> params) {
    if (params == null || params.isEmpty()) {
      return "(không có tham số)";
    }
    return params.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> maskKeyForLog(e.getKey()) + "=" + maskValueForLog(e.getKey(), e.getValue(), params))
        .collect(Collectors.joining(", "));
  }

  /**
   * Escape + cắt một chuỗi bất kỳ trước khi ghi log — dùng CHUNG cho cả TÊN
   * lẫn GIÁ TRỊ tham số.
   *
   * <p>Vì sao tách thành một hàm duy nhất: endpoint {@code /api/payments/ipn}
   * là CÔNG KHAI, KHÔNG xác thực ⇒ cả tên lẫn giá trị tham số đều do bất kỳ ai
   * kiểm soát được. Tên tham số cũng được container decode ⇒ client gửi
   * {@code ?foo%0A%5BINFO%5D=x=1} là có tên chứa newline THÔ. Nếu để nguyên
   * xuống log thì client tự tạo được DÒNG LOG GIẢ (log injection) trong log
   * server (đọc được của ai đó khác trong cùng log).
   *
   * <p>Một hàm duy nhất ⇒ không thể "quên" escape ở một nhánh (nhánh quên là
   * đúng lớp lỗi đã sót ở vòng sửa trước: giá trị được escape nhưng tên thì
   * không).
   *
   * @param text chuỗi cần escape + cắt (có thể {@code null})
   * @return chuỗi an toàn để ghi log
   */
  private static String escapeAndTruncateForLog(String text) {
    String escaped = (text == null ? "" : text).replace("\r", "\\r").replace("\n", "\\n");
    if (escaped.length() > LOG_PARAM_VALUE_MAX) {
      return escaped.substring(0, LOG_PARAM_VALUE_MAX)
          + "...(cắt bớt " + (escaped.length() - LOG_PARAM_VALUE_MAX) + " ký tự)";
    }
    return escaped;
  }

  /**
   * Escape + cắt TÊN tham số trước khi ghi log.
   *
   * <p>Tên tham số KHÔNG bao giờ là chữ ký ⇒ không cần che 3 lớp như giá trị
   * — chỉ cần escape + cắt, DÙNG CHUNG quy tắc với giá trị qua
   * {@link #escapeAndTruncateForLog} (không nhân bản logic).
   *
   * @param key tên tham số (có thể {@code null})
   * @return tên tham số an toàn để ghi log
   */
  private static String maskKeyForLog(String key) {
    return escapeAndTruncateForLog(key);
  }

  /**
   * Che giá trị chữ ký + escape + cắt bớt giá trị quá dài trước khi ghi log.
   *
   * <p><b>Mọi</b> giá trị tham số đi vào log đều phải qua hàm này — kể cả
   * {@code vnp_TxnRef} nằm trong message của {@code log.warn}. Lý do: endpoint
   * {@code /api/payments/ipn} là CÔNG KHAI, KHÔNG xác thực ⇒ mọi giá trị tham
   * số đều do bất kỳ ai kiểm soát được ⇒ in thô là mời chèn DÒNG LOG GIẢ
   * (log injection) vào log server.
   *
   * @param key tên tham số
   * @param value giá trị tham số (có thể {@code null})
   * @param params toàn bộ tham số nhận được (dùng để che theo GIÁ TRỊ)
   */
  private static String maskValueForLog(String key, String value, Map<String, String> params) {
    if (isSecureHashKey(key) || isSecureHashValue(value, params)) {
      return "***";
    }
    return escapeAndTruncateForLog(value);
  }

  /**
   * Tên tham số có phải tên chứa chữ ký không (KHÔNG phân biệt hoa thường).
   *
   * <p>Che theo tên là lớp RẺ nhất nhưng không đủ: kẻ xấu chỉ cần đổi tên
   * tham số là lọt ⇒ phải che thêm theo giá trị.
   */
  private static boolean isSecureHashKey(String key) {
    return key != null && SECURE_HASH_PARAM.equalsIgnoreCase(key);
  }

  /**
   * Giá trị này có phải CHỮ KÝ (theo GIÁ TRỊ) không — bất kể tên tham số gọi
   * nó là gì.
   *
   * <p>Hai lớp:
   *
   * <ol>
   *   <li>Trùng giá trị chữ ký đang có trong bộ tham số (đặt dưới một tên
   *       khác) — vd IPN có cả {@code vnp_SecureHash} lẫn {@code vnp_hash}
   *       cùng một giá trị ⇒ cả hai đều phải bị che.</li>
   *   <li>Hình dạng chữ ký VNPay (hex 64/128) — bắt được trường hợp kẻ xấu
   *       đổi tên tham số HOÀN TOÀN: không còn tên nào để so khớp, nhưng giá
   *       trị vẫn là chữ ký hợp lệ của bộ tham số gốc ⇒ in ra là lộ chữ ký.</li>
   * </ol>
   */
  private static boolean isSecureHashValue(String value, Map<String, String> params) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    if (params != null) {
      for (Map.Entry<String, String> e : params.entrySet()) {
        if (isSecureHashKey(e.getKey())
            && value.equalsIgnoreCase(e.getValue() == null ? "" : e.getValue())) {
          return true;
        }
      }
    }
    return SIGNATURE_SHAPE.matcher(value).matches();
  }

  /**
   * Chuẩn hoá giá trị gửi lên VNPay: bỏ DẤU tiếng Việt và bỏ KHOẢNG TRẮNG.
   *
   * <p>VNPay yêu cầu mọi giá trị gửi đi là tiếng Việt không dấu, không ký tự
   * đặc biệt. URL có dấu là URL hỏng (gateway không đọc được) — nên bỏ dấu
   * luôn thay vì để người vận hành tự phát hiện.
   *
   * @param name tên tham số (để log khi phải sửa giá trị)
   */
  private String vnpaySafe(String raw, String name) {
    if (raw == null) {
      return "";
    }
    String cleaned = toAsciiNoSpace(raw);
    if (!cleaned.equals(raw)) {
      log.warn("Gia tri {} co dau/khoang trang — da bo chuan hoa truoc khi gui VNPay.", name);
    }
    return cleaned;
  }

  /**
   * Chuẩn hoá {@code vnp_IpAddr}: chỉ nhận địa chỉ IP ĐÚNG DẠNG (IPv4 hoặc
   * IPv6).
   *
   * <p>Header {@code X-Forwarded-For} do CLIENT kiểm soát ⇒ không được đẩy
   * bừa ký tự lạ vào payUrl (và vào cả chữ ký). IP sai dạng ⇒ trả về rỗng để
   * caller dùng fallback {@code 127.0.0.1} (gateway VNPay cũng từ chối IP sai
   * dạng, nhưng tham số vẫn phải có mặt với API 2.1.0).
   */
  private static String vnpayIp(String raw) {
    if (raw == null) {
      return "";
    }
    String trimmed = raw.trim();
    // Chi lay IP dau tien ("ip1, ip2" -> "ip1").
    int comma = trimmed.indexOf(',');
    if (comma >= 0) {
      trimmed = trimmed.substring(0, comma).trim();
    }
    if (IPV4.matcher(trimmed).matches()) {
      return trimmed;
    }
    if (trimmed.indexOf(':') >= 0 && IPV6.matcher(trimmed).matches()) {
      return trimmed;
    }
    return "";
  }

  /** IPv4: {@code 203.0.113.7}. */
  private static final java.util.regex.Pattern IPV4 =
      java.util.regex.Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

  /** IPv6 (dạng rút gọn): chỉ ký tự hex và dấu hai chấm. */
  private static final java.util.regex.Pattern IPV6 =
      java.util.regex.Pattern.compile("^[0-9a-fA-F:]{2,45}$");

  /**
   * Bỏ dấu tiếng Việt + bỏ khoảng trắng + ép còn ASCII thuần.
   *
   * <p>Ví dụ {@code "Báo cáo Holland"} → {@code "BaocaoHolland"}.
   *
   * <p>Ba bước: (1) chuẩn hoá Unicode NFD rồi bỏ dấu phân tổ hợp; (2) thay
   * các chữ KHÔNG có dấu phân tổ hợp ({@code đ}, {@code ñ}, {@code ø}…); (3) xoá
   * mọi ký tự còn lại ngoài ASCII in được — để chắc chắn giá trị gửi đi đúng
   * chuẩn VNPay.
   */
  static String toAsciiNoSpace(String raw) {
    if (raw == null || raw.isEmpty()) {
      return "";
    }
    String normalized = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD);
    String noMarks = normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    StringBuilder sb = new StringBuilder(noMarks.length());
    for (int i = 0; i < noMarks.length(); i++) {
      char c = noMarks.charAt(i);
      switch (c) {
        case 'đ' -> sb.append('d');
        case 'Đ' -> sb.append('D');
        case 'ñ' -> sb.append('n');
        case 'Ñ' -> sb.append('N');
        case 'ø' -> sb.append('o');
        case 'Ø' -> sb.append('O');
        case 'æ' -> sb.append("ae");
        case 'Æ' -> sb.append("AE");
        case 'ß' -> sb.append("ss");
        case 'ł' -> sb.append('l');
        case 'Ł' -> sb.append('L');
        default -> {
          // Bỏ khoảng trắng và mọi ký tự ngoài ASCII in được.
          if (c > 0x20 && c < 0x7F) {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }

  /**
   * Xử lý IPN server-to-server từ VNPay.
   *
   * <p>Quy tắc (ruling mới, đảo ngược ràng buộc cũ "luôn trả HTTP 200"):
   *
   * <ul>
   *   <li>Lỗi NGHIỆP VỤ (chữ ký sai, đơn không tồn tại, status khác mã thành
   *       công, đã PAID, sai số tiền) → HTTP <b>200</b> kèm {@code RspCode}
   *       tương ứng. Lỗi nghiệp vụ không tự hết nên retry cũng vô ích — và
   *       theo tài liệu VNPay, {@code RspCode} 01/04/97/99 vẫn bị retry tối
   *       đa 10 lần, cách nhau 5 phút (chỉ tốn thêm lượt gọi, không hại).</li>
   *   <li>Lỗi HẠ TẦNG (DB lạnh, connection reset, pool hết) → để exception
   *       ném ra ⇒ HTTP <b>5xx</b> ⇒ VNPay retry. Đây là thứ phải retry:
   *       tiền ĐÃ vào mà đơn kẹt PENDING thì người chơi mất tiền.</li>
   * </ul>
   *
   * <p>Thứ tự: verify chữ ký → giao dịch có thành công không → tra đơn theo
   * {@code vnp_TxnRef} → nếu đã PAID → kiểm tra số tiền → đánh dấu PAID + lưu.
   * IPN tới sau khi đơn EXPIRED vẫn nhận PAID (tiền đã vào).
   */
  @Transactional
  public String handleIpn(Map<String, String> params) {
    try {
      if (!signatureService.verify(params)) {
        // VAN TRA 97 (khong doi hanh vi).
        //
        // Theo tai lieu VNPay, RspCode=97 KHONG ket thuc luong: VNPay SẼ retry
        // tối đa 10 lần, cách nhau 5 phút (cùng nhóm với 01/04/99). Nên đơn
        // vẫn có cơ hội chuyển PAID ở một lần retry sau, NẾU nguyên nhân lệch
        // chữ ký đã được sửa; nếu không thì đơn vẫn PENDING => het 15 phut =>
        // EXPIRED du tien da vao.
        //
        // Dù vậy phai CO DAU VET de chan doan duoc chu ky lech o dau (sau khi
        // chuyen sang API 2.1.0: HMAC-SHA512 + bo vnp_SecureHashType khoi chuoi
        // bam => rui ro "sai chu ky" tang manh).
        log.warn(
            "IPN VNPay SAI CHỮ KÝ (RspCode=97) — đơn '{}' chưa chuyển PAID. "
                + "VNPay sẽ retry tối đa 10 lần, cách nhau 5 phút: nếu nguyên nhân "
                + "lệch chữ ký đã được sửa thì một lần retry sau có thể thành công; "
                + "nếu không, đơn sẽ hết hạn (EXPIRED) dù tiền đã vào. "
                + "Tham số nhận được (chữ ký tính trên các tham số CÓ GIÁ TRỊ, BỎ QUA "
                + "vnp_SecureHash, vnp_SecureHashType và vnp_IpnUrl): {}",
            // Mã đơn cũng phải qua hàm che/escape/cắt: endpoint IPN công khai,
            // không xác thực ⇒ vnp_TxnRef do bất kỳ ai kiểm soát được ⇒ in THÔ
            // là mời chèn dòng log giả (log injection).
            maskValueForLog(
                TXN_REF_PARAM, params == null ? null : params.get(TXN_REF_PARAM), params),
            describeParams(params));
        return "{\"RspCode\":\"97\",\"Message\":\"Fail checksum\"}";
      }
      if (!isSuccessfulTransaction(params)) {
        // Giao dich THAT BAI o ngan hang (vnp_TransactionStatus != ma thanh cong).
        //
        // Vì sao KHONG trả 99: 99 (cùng 01/04/97) là ma "merchant chua cap nhat
        // duoc tinh trang giao dich" => VNPAY retry toi da 10 lan, cach nhau 5
        // phut. Mot giao dich da THAT BAI o ngan hang thi 10 lan retry cung
        // khong bao gio thanh cong => chi la toi 50 phut cho mot IPN vô nghia.
        //
        // Vì sao 02 (chu khong phai 00): 02 = "merchant da cap nhat duoc tinh
        // trang giao dich" => VNPAY KET THUC LUONG ngay. 00 giong "Confirm
        // Success" cho mot giao dich that bai thi doi la.
        //
        // Don goc van de PENDING (khong phai PAID) => nguoi choi co the thu
        // thanh toan LAI tren cung don nay (vnp_TxnRef giu nguyen).
        return "{\"RspCode\":\"02\",\"Message\":\"Order not confirmed\"}";
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
   * Giao dịch trong IPN có THÀNH CÔNG không.
   *
   * <p>Mã thành công phụ thuộc BỘ GIAO THỨC: API cũ ({@code vnp_Command=pay_web})
   * báo {@code vnp_TransactionStatus=01}, API 2.1.0 ({@code vnp_Command=pay})
   * báo {@code 00}. Nhầm hai mã này là tiền đã vào mà đơn vẫn kẹt PENDING.
   *
   * @see VnpayConfig#successTransactionStatus()
   */
  private boolean isSuccessfulTransaction(Map<String, String> params) {
    String status = params.get("vnp_TransactionStatus");
    return status != null && status.trim().equals(vnpayConfig.successTransactionStatus());
  }

  /**
   * Tra trạng thái một đơn thanh toán của người chơi.
   *
   * <p>Nạp đơn theo {@code orderId} VÀ {@code playerKey}: nếu không khớp người
   * mua (đơn của người khác) → 404. Nếu đơn đang {@code PENDING} và đã quá hạn
   * thì đặt {@code EXPIRED} và lưu ngay (lazy expire, không dùng {@code @Scheduled}).
   *
   * <p>Nội dung báo cáo ({@code riasecJson}) là thứ ĐÃ TRẢ TIỀN ⇒ CHỈ trả khi
   * đơn đã {@code PAID}. Mã Holland ({@code hollandCode}, 1 chữ) thì LUÔN trả.
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

    // Nội dung báo cáo (6 điểm RIASEC) là thứ ĐÃ TRẢ TIỀN => CHỈ trả khi đơn
    // đã PAID. Đơn PENDING/EXPIRED trả "" (rỗng).
    //
    // Vì sao phải chặn: nếu trả vô điều kiện thì người chơi tạo đơn (chưa
    // trả đồng nào) rồi gọi GET /api/payments/{orderId}?playerKey= là có
    // TOÀN BỘ báo cáo Holland MIỄN PHÍ — đúng thứ 50.000đ phải mua.
    String riasecJson = "";
    if (order.getStatus() == PaymentStatus.PAID) {
      String stored = order.getRiasecJson();
      if (stored != null && !stored.trim().isEmpty()) {
        riasecJson = stored;
      }
    }

    // Mã Holland (1 chữ) LUÔN trả — kể cả đơn PENDING/EXPIRED. Tính từ ảnh
    // chụp RIASEC GỐC (không phải từ chuỗi đã che ở trên) để mã luôn đúng.
    // Chủ dự án chấp nhận mức lộ 1 chữ này để người chơi vẫn thấy được mã
    // của chính mình kể cả khi chưa thanh toán.
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
          order.getExpiresAt()));
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
