package vn.careermap.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import vn.careermap.config.VnpayConfig;

/**
 * Ký đơn thanh toán VNPay bằng HMAC — API 2.1.0 dùng HMAC-SHA512, API cũ dùng
 * HMAC-SHA256 (thuật toán lấy theo cấu hình {@code vnpay.hash-algorithm}).
 *
 * <p>Thuật toán (bất biến, không đổi):
 * <ol>
 *   <li>Bỏ {@code vnp_SecureHash} khỏi danh sách (chữ ký cũ của người khác).</li>
 *   <li>Bỏ {@code vnp_SecureHashType}: API 2.1.0 KHÔNG gửi tham số này và IPN
 *       2.1.0 có thể kèm nó — mẫu chính thức của VNPay loại nó khỏi danh sách
 *       trước khi băm. Băm kèm nó mà VNPay không băm kèm ⇒ IPN bị từ chối
 *       ({@code RspCode=97}) ⇒ đơn kẹt PENDING ⇒ người chơi mất tiền.</li>
 *   <li>Bỏ tham số giá trị null hoặc rỗng.</li>
 *   <li>Sắp xếp key theo alphabet (TreeMap order).</li>
 *   <li>URL-encode <b>CẢ key lẫn value</b> (UTF-8, {@code URLEncoder}).</li>
 *   <li>Nối {@code key=value} bằng {@code &}.</li>
 *   <li>HMAC theo thuật toán trong cấu hình ({@code vnpay.hash-algorithm},
 *       mặc định {@code HmacSHA256}), KHÔNG phải SHA512 với khoá
 *       {@code HashSecret}.</li>
 *   <li>Hex lowercase — độ dài 64 ký tự với SHA256, 128 ký tự với SHA512.</li>
 * </ol>
 *
 * <p><b>BẤT BIẾN CỐT LÕI của VNPay:</b> chuỗi dùng để TÍNH HASH phải giống
 * HỆT chuỗi dùng trong QUERY gửi đi. Demo chính thức của VNPay (Java / PHP /
 * C#) làm đúng như vậy: {@code hashData} và {@code query} đều dùng
 * {@code urlencode(key)=urlencode(value)}. Vì vậy {@link #canonical} và
 * {@link #signedQueryString} PHẢI dùng CHUNG một hàm encode — không được
 * hash trên giá trị thô rồi gửi giá trị đã encode (lỗi này làm mọi giao
 * dịch bị gateway từ chối và mọi IPN bị trả {@code RspCode=97}).
 *
 * <p>Vì sao thuật toán nằm trong cấu hình: tài liệu VNPay hiện hành mô tả API
 * mới {@code paymentv2/vpcpay.html} dùng <b>HMAC-SHA512</b> (hash 128 hex ký
 * tự) và <b>KHÔNG</b> gửi {@code vnp_SecureHashType}, còn API cũ
 * {@code paymentgateway/paymenturl.htm} dùng SHA256.
 */
@Service
public class VnpaySignatureService {

  private static final String SECURE_HASH_KEY = "vnp_SecureHash";

  /** Tham số kiểu hash — API 2.1.0 KHÔNG gửi, và IPN 2.1.0 có thể kèm. KHÔNG băm kèm. */
  private static final String SECURE_HASH_TYPE_KEY = "vnp_SecureHashType";

  private final VnpayConfig config;

  public VnpaySignatureService(VnpayConfig config) {
    this.config = config;
  }

  /**
   * Chữ ký hex lowercase của các tham số đã được chuẩn hoá.
   *
   * @param params các tham số {@code vnp_*}; {@code vnp_SecureHash},
   *               {@code vnp_SecureHashType} và giá trị null/rỗng tự bị bỏ
   * @return hex HMAC theo {@code vnpay.hash-algorithm} (64 ký tự với SHA256,
   *         128 ký tự với SHA512)
   */
  public String sign(Map<String, String> params) {
    return hmacHex(canonical(params));
  }

  /**
   * Xác minh chữ ký của một bộ tham số IPN: ký lại từ các tham số (bỏ
   * {@code vnp_SecureHash} và giá trị null/rỗng, sắp xếp alphabet, URL-encode
   * key + value, nối {@code key=value} bằng {@code &}, HMAC hex lowercase) rồi
   * so với {@code vnp_SecureHash} nhận được.
   *
   * <p>Tham số IPN tới từ container đã được GIẢI MÃ (Spring decode
   * {@code @RequestParam}); {@link #canonical} encode lại đúng bằng cách VNPay
   * dùng lúc gửi ({@code URLEncoder} UTF-8) ⇒ dựng lại đúng chuỗi đã hash.
   *
   * <p>So sánh chữ ký là CONSTANT-TIME ({@link MessageDigest#isEqual}) để
   * không lộ thời gian theo vị trí ký tự lệch. Chữ ký nhận được được CHUẨN
   * HOÁ về lowercase trước khi so ⇒ vẫn chấp nhận hex HOA (đúng hành vi cũ
   * của {@code equalsIgnoreCase}).
   *
   * @return {@code true} nếu chữ ký khớp (không phân biệt hoa thường).
   */
  public boolean verify(Map<String, String> params) {
    String received = params == null ? null : params.get(SECURE_HASH_KEY);
    if (received == null || received.isEmpty()) {
      return false;
    }
    // So sánh CONSTANT-TIME (MessageDigest.isEqual): không lộ thời gian theo
    // vị trí ký tự đầu tiên lệch ⇒ kẻ xấu không dò được chữ ký bằng cách đo
    // thời gian.
    //
    // CHUẨN HOÁ chữ ký nhận được về lowercase TRƯỚC khi so: sign() trả hex
    // lowercase, nhưng trước đây verify() dùng equalsIgnoreCase ⇒ chấp nhận cả
    // hex HOA. Chuyển thẳng sang isEqual mà không chuẩn hoá sẽ từ chối một
    // IPN có vnp_SecureHash viết HOA ⇒ RspCode=97 ⇒ đơn treo PENDING ⇒ mất
    // tiền. Locale.ROOT để không bị lỗi "I" của locale Thổ Nhĩ Kỳ.
    byte[] expected = sign(params).getBytes(StandardCharsets.UTF_8);
    byte[] actual = received.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expected, actual);
  }

  /**
   * Query string đã sẵn sàng ghép vào payUrl, gồm {@code &vnp_SecureHash=...}
   * ở cuối (hex lowercase).
   *
   * <p>Key và value đều được URL-encode bằng {@link #encode} — HÀM GIỐNG HỆT
   * hàm dùng để tính hash ({@link #canonical}). Bất biến của VNPay: <b>dạng
   * dùng để hash phải bằng dạng dùng trong query</b>; lệch một ký tự encode là
   * gateway từ chối đơn và IPN trả {@code RspCode=97}.
   *
   * <p>{@code vnp_SecureHashType} KHÔNG có trong query (API 2.1.0 không gửi
   * tham số này) — xem {@link #SECURE_HASH_TYPE_KEY}.
   */
  public String signedQueryString(Map<String, String> params) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> entry : sortedEntries(params)) {
      sb.append(encode(entry.getKey())).append('=')
          .append(encode(entry.getValue()))
          .append('&');
    }
    sb.append(SECURE_HASH_KEY).append('=').append(sign(params));
    return sb.toString();
  }

  /**
   * Hàm encode DUY NHẤT dùng cho cả hash lẫn query.
   *
   * <p>UTF-8 + {@code URLEncoder} (dấu {@code +} cho khoảng trắng, {@code %XX}
   * hoa cho ký tự đặc biệt) — đúng như demo chính thức của VNPay
   * ({@code URLEncoder.encode} trong Java, {@code urlencode} trong PHP).
   */
  private static String encode(String raw) {
    return URLEncoder.encode(raw, StandardCharsets.UTF_8);
  }

  private static List<Map.Entry<String, String>> sortedEntries(Map<String, String> params) {
    return params.entrySet().stream()
        .filter(e -> !e.getKey().equals(SECURE_HASH_KEY))
        .filter(e -> !e.getKey().equals(SECURE_HASH_TYPE_KEY))
        .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
        .sorted(Map.Entry.comparingByKey())
        .toList();
  }

  /**
   * Chuỗi chuẩn hoá để băm: {@code key=value&key=value}, key theo alphabet,
   * <b>key và value đều đã URL-encode</b>.
   *
   * <p>Phải giống HỆT phần query gửi đi — xem {@link #signedQueryString}.
   */
  private static String canonical(Map<String, String> params) {
    return sortedEntries(params).stream()
        .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
        .collect(Collectors.joining("&"));
  }

  /**
   * Thuật toán HMAC đang dùng, đã chuẩn hoá về dạng {@code Mac} hiểu được.
   *
   * <p>Chấp nhận cả {@code SHA256}/{@code SHA512} (tên ngắn) lẫn
   * {@code HmacSHA256}/{@code HmacSHA512} (tên đầy đủ) để người vận hành gõ
   * {@code VNPAY_HASH_ALGORITHM=SHA512} không bị lỗi.
   */
  static String normalizeAlgorithm(String raw) {
    if (raw == null) {
      return VnpayConfig.DEFAULT_HASH_ALGORITHM;
    }
    String value = raw.trim();
    if (value.isEmpty()) {
      return VnpayConfig.DEFAULT_HASH_ALGORITHM;
    }
    String upper = value.toUpperCase();
    if (upper.startsWith("HMAC")) {
      return "Hmac" + upper.substring("HMAC".length());
    }
    if ("SHA256".equals(upper)) {
      return "HmacSHA256";
    }
    if ("SHA512".equals(upper)) {
      return "HmacSHA512";
    }
    // Không nhận ra -> trả nguyên để Mac.getInstance() ném lỗi rõ ràng.
    return value;
  }

  private String hmacHex(String data) {
    String algorithm = normalizeAlgorithm(config.getHashAlgorithm());
    try {
      Mac mac = Mac.getInstance(algorithm);
      mac.init(new SecretKeySpec(
          config.getHashSecret().getBytes(StandardCharsets.UTF_8), algorithm));
      return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Không khởi tạo được " + algorithm, e);
    }
  }
}
