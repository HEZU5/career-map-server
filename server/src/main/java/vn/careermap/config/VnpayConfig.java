package vn.careermap.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cấu hình VNPay: đọc từ biến môi trường Render qua {@code ${VNPAY_*:}}.
 *
 * <p>Mặc định là API 2.1.0 ({@code paymentv2/vpcpay.html}): endpoint cũ
 * {@code paymentgateway/paymenturl.htm} đã bị VNPay thu hồi (HTTP 404).
 *
 * <p>HashSecret là khoá HMAC — KHÔNG bao giờ hardcode vào source. Thuật toán
 * ký theo PHIÊN BẢN API (Ruling B): API 2.1.0 ⇒ HMAC-SHA512, API cũ ⇒
 * HMAC-SHA256. Khi không set biến môi trường (local dev) mọi giá trị là rỗng,
 * nên payUrl sẽ đi kèm TmnCode/secret rỗng — endpoint sandbox VNPay sẽ từ
 * chối, nhưng ứng dụng vẫn khởi động và test vẫn chạy (test tự truyền secret
 * vào).
 */
@Component
public class VnpayConfig {

  /** Tên biến môi trường tương ứng với từng giá trị cấu hình VNPay. */
  public static final String ENV_TMN_CODE = "VNPAY_TMN_CODE";

  public static final String ENV_HASH_SECRET = "VNPAY_HASH_SECRET";
  public static final String ENV_RETURN_URL = "VNPAY_RETURN_URL";
  public static final String ENV_IPN_URL = "VNPAY_IPN_URL";

  /** URL gateway VNPay — đổi được mà KHÔNG cần build lại (sandbox ⇄ production). */
  public static final String ENV_BASE_URL = "VNPAY_BASE_URL";

  /** Thuật toán chữ ký — đổi được mà KHÔNG cần build lại. */
  public static final String ENV_HASH_ALGORITHM = "VNPAY_HASH_ALGORITHM";

  /** Kiểu hash gửi kèm cho gateway qua {@code vnp_SecureHashType}. */
  public static final String ENV_HASH_TYPE = "VNPAY_HASH_TYPE";

  /**
   * Mã lệnh thanh toán ({@code vnp_Command}) — quyết định BỘ GIAO THỨC nào
   * được dùng: {@code pay_web} (API cũ {@code paymentgateway/paymenturl.htm})
   * hay {@code pay} (API 2.1.0 {@code paymentv2/vpcpay.html}).
   */
  public static final String ENV_COMMAND = "VNPAY_COMMAND";

  /** Phiên bản API ({@code vnp_Version}) — API 2.1.0 bắt buộc {@code 2.1.0}. */
  public static final String ENV_VERSION = "VNPAY_VERSION";

  /** Mã danh mục hàng hoá ({@code vnp_OrderType}) — API 2.1.0 BẮT BUỘC. */
  public static final String ENV_ORDER_TYPE = "VNPAY_ORDER_TYPE";

  /** Gateway sandbox mặc định (API 2.1.0 {@code paymentv2/vpcpay.html}). */
  public static final String DEFAULT_BASE_URL =
      "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";

  /** Lệnh mặc định — API 2.1.0 {@code paymentv2/vpcpay.html}. */
  public static final String DEFAULT_COMMAND = "pay";

  /**
   * Phiên bản API mặc định — API 2.1.0 BẮT BUỘC {@code vnp_Version=2.1.0}
   * (gateway trả "Dữ liệu gửi sang không đúng định dạng" nếu thiếu).
   */
  public static final String DEFAULT_VERSION = "2.1.0";

  /**
   * Danh mục hàng hoá mặc định — API 2.1.0 BẮT BUỘC {@code vnp_OrderType}
   * (mẫu chính thức của VNPay dùng {@code other}).
   */
  public static final String DEFAULT_ORDER_TYPE = "other";

  /** Thuật toán chữ ký mặc định. */
  public static final String DEFAULT_HASH_ALGORITHM = "HmacSHA256";

  /** Thuật toán chữ ký của API 2.1.0 — tài liệu VNPay BẮT BUỘC HMAC-SHA512. */
  public static final String DEFAULT_HASH_ALGORITHM_API21 = "HmacSHA512";

  /** Thuật toán chữ ký của API cũ (pay_web) — SHA256. */
  public static final String DEFAULT_HASH_ALGORITHM_LEGACY = "HmacSHA256";

  /** Bật/tắt thanh toán VNPay; đọc để kiểm tra sẵn sàng, chặn sớm khi chưa cấu hình. */
  private final boolean enabled;

  /** Merchant ID (Website ID) cấp bởi VNPay. */
  private final String tmnCode;

  /** Mã bí mật dùng ký HMAC (HashSecret) — đọc từ biến môi trường. */
  private final String hashSecret;

  /** URL VNPay redirect về sau khi thanh toán (GET). */
  private final String returnUrl;

  /** URL IPN (server-to-server) VNPay gọi báo kết quả thật. */
  private final String ipnUrl;

  /** URL gateway VNPay (API 2.1.0: {@code paymentv2/vpcpay.html}). */
  private final String baseUrl;

  /**
   * Thuật toán HMAC dùng để ký: {@code HmacSHA256} (API cũ) hoặc
   * {@code HmacSHA512} (API 2.1.0). Rỗng = tự chọn theo phiên bản API.
   */
  private final String hashAlgorithm;

  /** Kiểu hash gửi kèm cho gateway ({@code vnp_SecureHashType}); rỗng = không gửi. */
  private final String hashType;

  /** Lệnh thanh toán {@code vnp_Command} — {@code pay_web} (API cũ) hoặc {@code pay} (API 2.1.0). */
  private final String command;

  /** Phiên bản API {@code vnp_Version} — mặc định {@code 2.1.0}; rỗng = không gửi (API cũ). */
  private final String version;

  /** Danh mục hàng hoá {@code vnp_OrderType} — mặc định {@code other}; rỗng = không gửi (API cũ). */
  private final String orderType;

  /**
   * Ctor dùng cho Spring ({@code @Value} từ {@code application.properties}).
   *
   * <p><b>Phải có {@code @Autowired}:</b> class này có NHIỀU constructor (ctor
   * rút gọn cho test ở dưới). Không ghim constructor này thì Spring không biết
   * chọn cái nào ⇒ "No default constructor found" ⇒ <b>ứng dụng KHÔNG KHỞI
   * ĐỘNG ĐƯỢC</b> (mọi giá trị VNPay cũng rỗng ⇒ không tạo được đơn).
   *
   * <p>{@code vnpay.hash-algorithm} để RỖNG mặc định: như vậy thuật toán được
   * chọn theo phiên bản API (Ruling B) — 2.1.0 thì SHA512, API cũ thì SHA256.
   * Nếu ghim {@code HmacSHA256} ở đây thì production sẽ ký sai thuật toán.
   */
  @Autowired
  public VnpayConfig(
      @Value("${vnpay.enabled:false}") boolean enabled,
      @Value("${vnpay.tmn-code:}") String tmnCode,
      @Value("${vnpay.hash-secret:}") String hashSecret,
      @Value("${vnpay.return-url:}") String returnUrl,
      @Value("${vnpay.ipn-url:}") String ipnUrl,
      @Value("${vnpay.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
      @Value("${vnpay.hash-algorithm:}") String hashAlgorithm,
      @Value("${vnpay.hash-type:}") String hashType,
      @Value("${vnpay.command:" + DEFAULT_COMMAND + "}") String command,
      @Value("${vnpay.version:" + DEFAULT_VERSION + "}") String version,
      @Value("${vnpay.order-type:" + DEFAULT_ORDER_TYPE + "}") String orderType) {
    this.enabled = enabled;
    this.tmnCode = tmnCode;
    this.hashSecret = hashSecret;
    this.returnUrl = returnUrl;
    this.ipnUrl = ipnUrl;
    this.baseUrl = isBlank(baseUrl) ? DEFAULT_BASE_URL : baseUrl.trim();
    this.hashType = hashType == null ? "" : hashType.trim();
    this.command = isBlank(command) ? DEFAULT_COMMAND : command.trim();
    this.version = version == null ? "" : version.trim();
    this.orderType = orderType == null ? "" : orderType.trim();
    // Ruling B: hash rong -> TU CHON theo phien ban API. Phai gan command/
    // version truoc khi tinh vi isApi21() doc tu hai field nay.
    this.hashAlgorithm =
        isBlank(hashAlgorithm)
            ? (isApi21(this.command, this.version)
                ? DEFAULT_HASH_ALGORITHM_API21
                : DEFAULT_HASH_ALGORITHM_LEGACY)
            : hashAlgorithm.trim();
  }

  /**
   * Ctor đầy đủ (8 tham số) — giữ tương thích với code cũ: lệnh / phiên bản /
   * danh mục hàng hoá lấy mặc định (API 2.1.0 {@code pay}).
   *
   * <p>Thuật toán lấy theo tham số truyền vào — ctor rút gọn GIỮ NGUYÊN hành vi
   * cũ, không tự chọn theo phiên bản như ctor của Spring.
   */
  public VnpayConfig(
      boolean enabled,
      String tmnCode,
      String hashSecret,
      String returnUrl,
      String ipnUrl,
      String baseUrl,
      String hashAlgorithm,
      String hashType) {
    this(
        enabled,
        tmnCode,
        hashSecret,
        returnUrl,
        ipnUrl,
        baseUrl,
        hashAlgorithm,
        hashType,
        DEFAULT_COMMAND,
        DEFAULT_VERSION,
        DEFAULT_ORDER_TYPE);
  }

  /**
   * Ctor rút gọn — dùng 4 giá trị VNPay cơ bản, còn lại lấy mặc định
   * (gateway sandbox API 2.1.0 + HMAC-SHA256). Tiện cho test và cho code gọi
   * nhanh.
   *
   * <p>Giữ nguyên hành vi cũ: ghim {@code HmacSHA256} (KHÔNG tự chọn theo phiên
   * bản như ctor của Spring) ⇒ bộ vector chữ ký SHA256 của API cũ trong test
   * vẫn dùng được. Production dùng ctor của Spring (hash rỗng ⇒ tự chọn
   * SHA512 cho API 2.1.0).
   */
  public VnpayConfig(
      boolean enabled, String tmnCode, String hashSecret, String returnUrl, String ipnUrl) {
    this(
        enabled,
        tmnCode,
        hashSecret,
        returnUrl,
        ipnUrl,
        DEFAULT_BASE_URL,
        DEFAULT_HASH_ALGORITHM,
        "",
        DEFAULT_COMMAND,
        DEFAULT_VERSION,
        DEFAULT_ORDER_TYPE);
  }

  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Cấu hình VNPay đã đủ để tạo đơn chưa: cần đủ CẢ BỐN giá trị —
   * mã merchant (TmnCode), khoá bí mật (HashSecret), URL trả về (ReturnUrl)
   * và URL IPN (IpnUrl).
   *
   * <p>Vì sao phải đủ 4 (chứ không chỉ 2):
   *
   * <ul>
   *   <li>Thiếu {@code HashSecret} → {@code VnpaySignatureService} ném lỗi LÚC
   *       ký payUrl — tức là SAU khi đơn đã nằm trong DB, để lại đơn rác PENDING.
   *   <li>Thiếu {@code TmnCode} → payUrl ra không có {@code vnp_TmnCode}, VNPay từ chối.
   *   <li>Thiếu {@code ReturnUrl} → người chơi thanh toán xong KHÔNG được đưa về
   *       trang kết quả của app.
   *   <li>Thiếu {@code IpnUrl} → server KHÔNG nhận được IPN từ VNPay → đơn kẹt
   *       PENDING → hết 15 phút thành EXPIRED, dù người chơi ĐÃ trả tiền. Đây là
   *       lỗi nguy hiểm nhất nên phải gate trước khi ghi đơn.
   * </ul>
   */
  public boolean isConfigured() {
    return missingSettings().isEmpty();
  }

  /**
   * Danh sách biến môi trường còn THIẾU, theo thứ tự cố định
   * (TmnCode → HashSecret → ReturnUrl → IpnUrl). Rỗng = cấu hình đủ.
   *
   * <p>Dùng để báo đúng biến nào thiếu (thay vì câu chung chung) — tên biến
   * môi trường KHÔNG phải bí mật, còn GIÁ TRỊ của chúng thì không bao giờ
   * được đưa vào body lỗi.
   */
  public List<String> missingSettings() {
    List<String> missing = new ArrayList<>(4);
    if (isBlank(tmnCode)) {
      missing.add(ENV_TMN_CODE);
    }
    if (isBlank(hashSecret)) {
      missing.add(ENV_HASH_SECRET);
    }
    if (isBlank(returnUrl)) {
      missing.add(ENV_RETURN_URL);
    }
    if (isBlank(ipnUrl)) {
      missing.add(ENV_IPN_URL);
    }
    // API 2.1.0 BẮT BUỘC vnp_Version + vnp_OrderType (tài liệu VNPay). Thiếu
    // thì gateway từ chối đơn — chặn ở cửa cho sạch.
    if (isApi21()) {
      if (isBlank(version)) {
        missing.add(ENV_VERSION);
      }
      if (isBlank(orderType)) {
        missing.add(ENV_ORDER_TYPE);
      }
    }
    return List.copyOf(missing);
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  public String getTmnCode() {
    return tmnCode;
  }

  public String getHashSecret() {
    return hashSecret;
  }

  public String getReturnUrl() {
    return returnUrl;
  }

  public String getIpnUrl() {
    return ipnUrl;
  }

  /** URL gateway VNPay dùng để dựng payUrl. */
  public String getBaseUrl() {
    return baseUrl;
  }

  /**
   * Thuật toán HMAC dùng để ký, đã chuẩn hoá về dạng {@code Mac} hiểu được
   * ({@code HmacSHA256} / {@code HmacSHA512}).
   */
  public String getHashAlgorithm() {
    return hashAlgorithm;
  }

  /** Kiểu hash gửi kèm cho gateway qua {@code vnp_SecureHashType}; rỗng = không gửi. */
  public String getHashType() {
    return hashType;
  }

  /** Lệnh thanh toán {@code vnp_Command}. */
  public String getCommand() {
    return command;
  }

  /** Phiên bản API {@code vnp_Version}; rỗng = không gửi. */
  public String getVersion() {
    return version;
  }

  /** Danh mục hàng hoá {@code vnp_OrderType}; rỗng = không gửi. */
  public String getOrderType() {
    return orderType;
  }

  /**
   * Đang dùng API 2.1.0 ({@code paymentv2/vpcpay.html}) hay không.
   *
   * <p>Nhận ra qua {@code vnp_Command=pay} hoặc {@code vnp_Version=2.1.x} —
   * hai bộ giao thức khác nhau: API 2.1.0 bắt buộc {@code vnp_Version} +
   * {@code vnp_OrderType}, CẤM gửi {@code vnp_SecureHashType}, ký bằng
   * HMAC-SHA512 và báo thành công bằng {@code vnp_TransactionStatus=00}
   * (API cũ dùng {@code 01}).
   */
  public boolean isApi21() {
    return isApi21(command, version);
  }

  /**
   * Bản STATIC của {@link #isApi21()} — dùng được trong constructor TRƯỚC khi
   * field {@code command}/{@code version} được gán (dùng để chọn thuật toán
   * chữ ký theo phiên bản API).
   */
  private static boolean isApi21(String command, String version) {
    return "pay".equalsIgnoreCase(command)
        || (version != null && version.startsWith("2.1"));
  }

  /**
   * Mã {@code vnp_TransactionStatus} báo giao dịch THÀNH CÔNG.
   *
   * <p>API cũ ({@code pay_web}) dùng {@code 01}; API 2.1.0 ({@code pay}) dùng
   * {@code 00} — nhầm hai mã này là tiền đã vào mà đơn vẫn kẹt PENDING.
   */
  public String successTransactionStatus() {
    return isApi21() ? "00" : "01";
  }

  /**
   * Có được gửi {@code vnp_SecureHashType} không.
   *
   * <p>API 2.1.0 <b>KHÔNG</b> được gửi tham số này (tài liệu "Chuyển đổi
   * phiên bản và thuật toán mã hóa"): gửi thừa là lệch bộ tham số mà
   * gateway tính hash ⇒ đơn bị từ chối.
   */
  public boolean allowsSecureHashType() {
    return !isApi21();
  }
}
