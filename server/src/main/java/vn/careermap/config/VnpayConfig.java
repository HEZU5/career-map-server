package vn.careermap.config;

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

  /** Bật/tắt thanh toán VNPay; đọc để kiểm tra sẵn sàng, chặn sớm khi chưa cấu hình. */
  private final boolean enabled;

  /** Merchant ID (Website ID) cấp bởi VNPay. */
  private final String tmnCode;

  /** Mã bí mật dùng ký HMAC-SHA256 (HashSecret) — đọc từ biến môi trường. */
  private final String hashSecret;

  /** URL VNPay redirect về sau khi thanh toán (GET). */
  private final String returnUrl;

  /** URL IPN (server-to-server) VNPay gọi báo kết quả thật. */
  private final String ipnUrl;

  public VnpayConfig(
      @Value("${vnpay.enabled:false}") boolean enabled,
      @Value("${vnpay.tmn-code:}") String tmnCode,
      @Value("${vnpay.hash-secret:}") String hashSecret,
      @Value("${vnpay.return-url:}") String returnUrl,
      @Value("${vnpay.ipn-url:}") String ipnUrl) {
    this.enabled = enabled;
    this.tmnCode = tmnCode;
    this.hashSecret = hashSecret;
    this.returnUrl = returnUrl;
    this.ipnUrl = ipnUrl;
  }

  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Cấu hình VNPay đã đủ để ký payUrl chưa: cần mã merchant (TmnCode) và
   * khoá bí mật (HashSecret).
   *
   * <p>Cần để chặn ở cửa TRƯỚC khi ghi đơn: nếu {@code enabled=true} nhưng
   * thiếu khoá thì {@code VnpaySignatureService} ném lỗi LÚC ký payUrl — tức
   * là sau khi đơn đã nằm trong DB, để lại đơn rác PENDING.
   */
  public boolean isConfigured() {
    return !isBlank(tmnCode) && !isBlank(hashSecret);
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
}
