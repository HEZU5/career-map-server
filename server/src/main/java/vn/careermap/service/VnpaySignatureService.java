package vn.careermap.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import vn.careermap.config.VnpayConfig;

/**
 * Ký đơn thanh toán VNPay bằng HMAC-SHA256, KHÔNG phải SHA512.
 *
 * <p>Thuật toán (bất biến, không đổi):
 * <ol>
 *   <li>Bỏ {@code vnp_SecureHash} khỏi danh sách (chữ ký cũ của người khác).</li>
 *   <li>Bỏ tham số giá trị null hoặc rỗng.</li>
 *   <li>Sắp xếp key theo alphabet (TreeMap order).</li>
 *   <li>Nối {@code key=value} bằng {@code &}.</li>
 *   <li>HMAC-SHA256, KHÔNG phải SHA512 với khoá {@code HashSecret}.</li>
 *   <li>Hex lowercase — độ dài đúng 64 ký tự.</li>
 * </ol>
 */
@Service
public class VnpaySignatureService {

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final String SECURE_HASH_KEY = "vnp_SecureHash";

  private final VnpayConfig config;

  public VnpaySignatureService(VnpayConfig config) {
    this.config = config;
  }

  /**
   * Chữ ký hex lowercase của các tham số đã được chuẩn hoá.
   *
   * @param params các tham số {@code vnp_*}; {@code vnp_SecureHash} và giá trị
   *               null/rỗng tự bị bỏ
   * @return hex HMAC-SHA256, dài đúng 64 ký tự
   */
  public String sign(Map<String, String> params) {
    return hmacHex(canonical(params));
  }

  /**
   * Xác minh chữ ký của một bộ tham số IPN: ký lại từ các tham số (bỏ
   * {@code vnp_SecureHash} và giá trị null/rỗng, sắp xếp alphabet, nối
   * {@code key=value} bằng {@code &}, HMAC-SHA256 hex lowercase) rồi so với
   * {@code vnp_SecureHash} nhận được.
   *
   * @return {@code true} nếu chữ ký khớp (so sánh không phân biệt hoa thường).
   */
  public boolean verify(Map<String, String> params) {
    String received = params == null ? null : params.get(SECURE_HASH_KEY);
    if (received == null || received.isEmpty()) {
      return false;
    }
    return received.equalsIgnoreCase(sign(params));
  }

  /**
   * Query string đã sẵn sàng ghép vào payUrl, gồm {@code &vnp_SecureHash=...}
   * ở cuối (hex lowercase, dài 64 ký tự).
   *
   * <p>Giá trị tham số được URL-encode (URLEncoder UTF-8) để URL hợp lệ;
   * chữ ký vẫn tính trên giá trị thô — đúng chuẩn VNPay.
   */
  public String signedQueryString(Map<String, String> params) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> entry : sortedEntries(params)) {
      sb.append(entry.getKey()).append('=')
          .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
          .append('&');
    }
    sb.append(SECURE_HASH_KEY).append('=').append(sign(params));
    return sb.toString();
  }

  private static List<Map.Entry<String, String>> sortedEntries(Map<String, String> params) {
    return params.entrySet().stream()
        .filter(e -> !e.getKey().equals(SECURE_HASH_KEY))
        .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
        .sorted(Map.Entry.comparingByKey())
        .toList();
  }

  private static String canonical(Map<String, String> params) {
    return sortedEntries(params).stream()
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(Collectors.joining("&"));
  }

  private String hmacHex(String data) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(
          config.getHashSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Không khởi tạo được " + HMAC_ALGORITHM, e);
    }
  }
}
