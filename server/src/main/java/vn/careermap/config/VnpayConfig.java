package vn.careermap.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cấu hình VNPay: đọc từ biến môi trường Render qua {@code ${VNPAY_*:}}.
 *
 * <p>HashSecret là khoá HMAC-SHA256 — KHÔNG bao giờ hardcode vào source.
 * Khi không set biến môi trường (local dev) mọi giá trị là rỗng, nên payUrl
 * sẽ đi kèm TmnCode/secret rỗng — endpoint sandbox VNPay sẽ từ chối, nhưng
 * ứng dụng vẫn khởi động và test vẫn chạy (test tự truyền secret vào).
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

  /** Gateway sandbox mặc định (API cũ {@code pay_web}). */
  public static final String DEFAULT_BASE_URL =
      "https://sandbox.vnpayment.vn/paymentgateway/paymenturl.htm";

  /** Thuật toán chữ ký mặc định. */
  public static final String DEFAULT_HASH_ALGORITHM = "HmacSHA256";

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

  /** URL gateway VNPay (pay_web). */
  private final String baseUrl;

  /** Thuật toán HMAC dùng để ký: {@code HmacSHA256} (mặc định) hoặc {@code HmacSHA512}. */
  private final String hashAlgorithm;

  /** Kiểu hash gửi kèm cho gateway ({@code vnp_SecureHashType}); rỗng = không gửi. */
  private final String hashType;

  public VnpayConfig(
      @Value("${vnpay.enabled:false}") boolean enabled,
      @Value("${vnpay.tmn-code:}") String tmnCode,
      @Value("${vnpay.hash-secret:}") String hashSecret,
      @Value("${vnpay.return-url:}") String returnUrl,
      @Value("${vnpay.ipn-url:}") String ipnUrl,
      @Value("${vnpay.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
      @Value("${vnpay.hash-algorithm:" + DEFAULT_HASH_ALGORITHM + "}") String hashAlgorithm,
      @Value("${vnpay.hash-type:}") String hashType) {
    this.enabled = enabled;
    this.tmnCode = tmnCode;
    this.hashSecret = hashSecret;
    this.returnUrl = returnUrl;
    this.ipnUrl = ipnUrl;
    this.baseUrl = isBlank(baseUrl) ? DEFAULT_BASE_URL : baseUrl.trim();
    this.hashAlgorithm =
        isBlank(hashAlgorithm) ? DEFAULT_HASH_ALGORITHM : hashAlgorithm.trim();
    this.hashType = hashType == null ? "" : hashType.trim();
  }

  /**
   * Ctor rút gọn — dùng 4 giá trị VNPay cơ bản, còn lại lấy mặc định
   * (gateway sandbox + HMAC-SHA256). Tiện cho test và cho code gọi nhanh.
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
        "");
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
}
