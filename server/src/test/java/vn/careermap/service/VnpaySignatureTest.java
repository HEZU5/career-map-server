package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import vn.careermap.config.VnpayConfig;

/**
 * Chữ ký VNPay là HMAC — SHA256 (API cũ) hoặc SHA512 (API 2.1.0) — khoá là
 * {@code HashSecret}.
 *
 * <p>Các vector SHA256 dưới đây là vector của API CŨ: fixture
 * {@link #configApiCu} ghim rõ ràng {@code pay_web} + {@code HmacSHA256}.
 * Bộ giao thức API 2.1.0 (SHA512) được kiểm ở
 * {@link #chuKyApi21Sha512DungDungChuoiDaGuiLenGateway()}.
 *
 * <p><b>QUAN TRỌNG — vì sao test này KHÔNG tự verify bằng chính
 * implementation:</b> lỗi chí mạng của wave trước (hash trên giá trị THÔ
 * trong khi query gửi giá trị ĐÃ URL-enCODE) đã LỌT QUA 147 test xanh vì
 * mọi test đều so sánh "hash của implementation" với "hash của chính
 * implementation". Vì vậy ở đây:
 *
 * <ul>
 *   <li>Vector KỲ VĂNG được hardcode, tính bằng HMAC-SHA256 của .NET
 *       ({@code HMACSHA256}) — độc lập hoàn toàn với code Java.</li>
 *   <li>Test {@link #chuKyTrongQueryDungDungChuoiDaGuiLenGateway()} tự băm
 *       chuỗi query thật bằng {@code javax.crypto.Mac} (viết tay trong test)
 *       và so với chữ ký đính kèm — kiểm tra đúng BẤT BIẾN của VNPay:
 *       <b>dạng dùng để hash PHẢI BẰNG dạng dùng trong query</b>.</li>
 * </ul>
 *
 * <p>Chuẩn dùng để dựng vector: demo chính thức của VNPay (Java/PHP/C#) —
 * key và value đều URL-encode, sắp xếp alphabet, nối {@code &}.
 */
class VnpaySignatureTest {

  private static final String SECRET = "secret";

  /**
   * Fixture API CŨ: {@code pay_web} + HMAC-SHA256.
   *
   * <p>Vì sao phải ghim rõ ràng thay vì dùng ctor rút gọn: ctor rút gọn giờ
   * TỰ CHỌN thuật toán theo phiên bản API ⇒ với mặc định 2.1.0 nó ký SHA512.
   * Các vector SHA256 dưới đây là vector ĐỘC LẬP (tính bằng .NET) của bộ giao
   * thức API CŨ ⇒ phải ghim đúng "API cũ + SHA256", không được trôi theo mặc
   * định (nếu không, test sẽ "xanh" vì cả hai vế cùng đổi ⇒ mất tác dụng kiểm).
   */
  private final VnpayConfig configApiCu = new VnpayConfig(
      true, "TMNCODE", SECRET, "https://app.example/return", "https://app.example/ipn",
      "https://sandbox.vnpayment.vn/paymentgateway/paymenturl.htm", "HmacSHA256", "",
      "pay_web", "", "");
  private final VnpaySignatureService signatureServiceApiCu =
      new VnpaySignatureService(configApiCu);

  /**
   * HMAC-SHA256 viết TAY trong test (không dùng {@link VnpaySignatureService})
   * — độc lập với implementation cần kiểm.
   */
  private static String hmacSha256Hex(String secret, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new AssertionError("Không tính được HMAC-SHA256 trong test", e);
    }
  }

  /**
   * HMAC-SHA512 viết TAY trong test — dùng để kiểm chứng bộ giao thức API
   * 2.1.0 mà KHÔNG tin vào chính implementation cần kiểm.
   */
  private static String hmacSha512Hex(String secret, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA512");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
      return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new AssertionError("Không tính được HMAC-SHA512 trong test", e);
    }
  }

  @Test
  void chuKyDungThuAToanSha256() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("a", "1");
    params.put("b", "2");

    String hash = signatureServiceApiCu.sign(params);

    // Vector .NET: HMACSHA256("secret", "a=1&b=2")
    assertThat(hash)
        .isEqualTo("604fe97c66c6393ff22e3cae366eee1131e351ebc736bf12f5d62e1755b7a233");
    assertThat(hash).hasSize(64);
  }

  /**
   * {@code vnp_SecureHash} và giá trị rỗng/không có KHÔNG được tham gia ký.
   *
   * <p>Vector độc lập: canonical chỉ còn {@code b=2&d=3}.
   */
  @Test
  void boQuaSecureHashVaGiaTriRong() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("vnp_SecureHash", "chu-cu-cua-nguoi-khac");
    params.put("b", "2");
    params.put("a", "");
    params.put("c", null);
    params.put("d", "3");

    String hash = signatureServiceApiCu.sign(params);

    // Vector .NET: HMACSHA256("secret", "b=2&d=3")
    assertThat(hash)
        .isEqualTo("751d53aab086158abfe76d2883ff9197eb3f978856f14f786ea16f855b676813");
  }

  /**
   * BA tham số có ký tự đặc biệt PHẢI được hash trên dạng ĐÃ URL-enCODE.
   *
   * <p>{@code vnp_ReturnUrl} / {@code vnp_IpnUrl} chứa {@code ://} và {@code /};
   * {@code vnp_OrderInfo} chứa khoảng trắng và dấu tiếng Việt. Nếu hash trên
   * dạng thô mà query gửi dạng encode thì gateway từ chối đơn.
   *
   * <p>Canonical (đúng như demo chính thức VNPay):
   * {@code vnp_IpnUrl=https%3A%2F%2Fapp.example%2Fipn&vnp_OrderInfo=B%C3%A1o+c%C3%A1o+Holland&vnp_ReturnUrl=https%3A%2F%2Fapp.example%2Freturn&vnp_TxnRef=0123456789abcdef0123456789abcdef}
   */
  @Test
  void chuKyBamTrenGiaTriDaUrlEncode() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("vnp_ReturnUrl", "https://app.example/return");
    params.put("vnp_IpnUrl", "https://app.example/ipn");
    params.put("vnp_OrderInfo", "Báo cáo Holland");
    params.put("vnp_TxnRef", "0123456789abcdef0123456789abcdef");

    String canonical =
        "vnp_IpnUrl=https%3A%2F%2Fapp.example%2Fipn"
            + "&vnp_OrderInfo=B%C3%A1o+c%C3%A1o+Holland"
            + "&vnp_ReturnUrl=https%3A%2F%2Fapp.example%2Freturn"
            + "&vnp_TxnRef=0123456789abcdef0123456789abcdef";

    // Vector .NET: HMACSHA256("secret", canonical đã encode)
    assertThat(signatureServiceApiCu.sign(params))
        .isEqualTo("08b9b54136ca0d11401fddfacbde6e0018014c93cbeb80977048aa95a2623f1c");
    // Và đúng bằng cách băm tay trên canonical đã encode.
    assertThat(signatureServiceApiCu.sign(params)).isEqualTo(hmacSha256Hex(SECRET, canonical));
  }

  /**
   * Hash trên dạng THÔ là SAI — phải khác vector trên.
   *
   * <p>Khẳng định ngược lại để không bao giờ quay lại lỗi cũ: cùng bộ tham số,
   * hash trên dạng thô phải RA KHÁC hash trên dạng đã encode.
   */
  @Test
  void hashTrenGiaTriThoKhacHashTrenGiaTriDaEncode() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("vnp_ReturnUrl", "https://app.example/return");
    params.put("vnp_IpnUrl", "https://app.example/ipn");
    params.put("vnp_OrderInfo", "Báo cáo Holland");
    params.put("vnp_TxnRef", "0123456789abcdef0123456789abcdef");

    String rawCanonical =
        "vnp_IpnUrl=https://app.example/ipn"
            + "&vnp_OrderInfo=Báo cáo Holland"
            + "&vnp_ReturnUrl=https://app.example/return"
            + "&vnp_TxnRef=0123456789abcdef0123456789abcdef";

    assertThat(signatureServiceApiCu.sign(params)).isNotEqualTo(hmacSha256Hex(SECRET, rawCanonical));
  }

  /**
   * BẤT BIẾN CỐT LÕI: chữ ký phải là HMAC của ĐÚNG chuỗi query gửi lên
   * gateway.
   *
   * <p>Test tự băm chuỗi query thật (bằng {@code javax.crypto.Mac} viết tay)
   * rồi so với {@code vnp_SecureHash} đính kèm. Nếu implementation hash
   * khác dạng query gửi đi thì test này đỏ — đây chính là lỗi đã làm hỏng
   * toàn bộ tính năng thanh toán ở wave trước.
   */
  @Test
  void chuKyTrongQueryDungDungChuoiDaGuiLenGateway() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("vnp_TxnRef", "0123456789abcdef0123456789abcdef");
    params.put("vnp_Amount", "5000000");
    params.put("vnp_Command", "pay_web");
    params.put("vnp_CreateDate", "20260101120000");
    params.put("vnp_CurrCode", "VND");
    params.put("vnp_IpAddr", "203.0.113.7");
    params.put("vnp_Locale", "vn");
    params.put("vnp_OrderInfo", "Báo cáo Holland");
    params.put("vnp_ReturnUrl", "https://app.example/return");
    params.put("vnp_IpnUrl", "https://app.example/ipn");

    String query = signatureServiceApiCu.signedQueryString(params);
    int marker = query.lastIndexOf("&vnp_SecureHash=");
    assertThat(marker).isGreaterThan(0);
    String sentToGateway = query.substring(0, marker);
    String hashInQuery = query.substring(marker + "&vnp_SecureHash=".length());

    assertThat(hashInQuery).hasSize(64);
    assertThat(hmacSha256Hex(SECRET, sentToGateway)).isEqualTo(hashInQuery);
  }

  /**
   * IPN về: tham số đã được container GIẢI MÃ, nhưng phải dựng lại được đúng
   * chuỗi đã băm (URL-encode lại) để {@code verify} khớp.
   *
   * <p>Mô phỏng đúng luồng thật: gateway gửi {@code vnp_OrderInfo} có khoảng
   * trắng và dấu; container giải mã thành {@code "Báo cáo Holland"};
   * {@code verify()} encode lại rồi so chữ ký.
   */
  @Test
  void verifyBamLaiDungChuoiDaGui() {
    Map<String, String> received = new LinkedHashMap<>();
    received.put("vnp_TxnRef", "0123456789abcdef0123456789abcdef");
    received.put("vnp_Amount", "5000000");
    received.put("vnp_TransactionStatus", "01");
    received.put("vnp_OrderInfo", "Báo cáo Holland");

    String canonical =
        "vnp_Amount=5000000"
            + "&vnp_OrderInfo=B%C3%A1o+c%C3%A1o+Holland"
            + "&vnp_TransactionStatus=01"
            + "&vnp_TxnRef=0123456789abcdef0123456789abcdef";
    received.put("vnp_SecureHash", hmacSha256Hex(SECRET, canonical));

    assertThat(signatureServiceApiCu.verify(received)).isTrue();

    // Chữ ký tính trên dạng thô KHÔNG được chấp nhận.
    received.put("vnp_SecureHash", hmacSha256Hex(SECRET, "vnp_Amount=5000000&vnp_OrderInfo=Báo cáo Holland&vnp_TransactionStatus=01&vnp_TxnRef=0123456789abcdef0123456789abcdef"));
    assertThat(signatureServiceApiCu.verify(received)).isFalse();
  }

  /**
   * Thuật toán chữ ký nằm trong CẤU HÌNH để đổi được mà không cần build lại.
   *
   * <p>API cũ {@code paymentgateway/paymenturl.htm} dùng HMAC-SHA256 (64 hex ký
   * tự); API mới {@code paymentv2/vpcpay.html} dùng HMAC-SHA512 (128 hex ký tự).
   */
  @Test
  void sha256ChoHash64KyTu() {
    // Fixture API CŨ + SHA256 ghim rõ ràng (xem {@link #configApiCu}).
    VnpayConfig sha256 =
        new VnpayConfig(
            true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
            "https://sandbox.vnpayment.vn/paymentgateway/paymenturl.htm", "HmacSHA256", "",
            "pay_web", "", "");
    VnpaySignatureService service = new VnpaySignatureService(sha256);

    Map<String, String> params = new LinkedHashMap<>();
    params.put("a", "1");

    assertThat(service.sign(params)).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  void sha512ChoHash128KyTu() {
    // Fixture API 2.1.0 + SHA512 ghim rõ ràng (thuật toán chỉ định tay).
    VnpayConfig sha512 =
        new VnpayConfig(
            true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
            VnpayConfig.DEFAULT_BASE_URL, "SHA512", "SHA512",
            "pay", "2.1.0", "other");
    VnpaySignatureService service = new VnpaySignatureService(sha512);

    Map<String, String> params = new LinkedHashMap<>();
    params.put("a", "1");

    assertThat(service.sign(params)).hasSize(128).matches("[0-9a-f]{128}");
  }

  /** Tên ngắn {@code SHA512} và tên đầy đủ {@code HmacSHA512} cho cùng kết quả. */
  @Test
  void tenTatNganVaTenDayDuChoCungKetQua() {
    assertThat(VnpaySignatureService.normalizeAlgorithm("SHA512")).isEqualTo("HmacSHA512");
    assertThat(VnpaySignatureService.normalizeAlgorithm("sha512")).isEqualTo("HmacSHA512");
    assertThat(VnpaySignatureService.normalizeAlgorithm("HmacSHA512")).isEqualTo("HmacSHA512");
    assertThat(VnpaySignatureService.normalizeAlgorithm("SHA256")).isEqualTo("HmacSHA256");
    assertThat(VnpaySignatureService.normalizeAlgorithm("")).isEqualTo("HmacSHA256");
    assertThat(VnpaySignatureService.normalizeAlgorithm(null)).isEqualTo("HmacSHA256");
  }

  // ── Ruling C: vnp_SecureHashType KHÔNG được ký ─────────────────────────────

  /**
   * {@code vnp_SecureHashType} KHÔNG được tham gia tính chữ ký.
   *
   * <p>Vì sao: IPN của API 2.1.0 có thể kèm {@code vnp_SecureHashType}, còn
   * mẫu chính thức của VNPay loại nó khỏi {@code fields} trước khi băm. Nếu ta
   * băm kèm nó mà VNPay không băm kèm ⇒ IPN bị từ chối ({@code RspCode=97}) ⇒
   * đơn kẹt PENDING ⇒ người chơi mất tiền.
   *
   * <p>Khẳng định bằng CÁCH BẰNG: cùng bộ tham số, có/không có
   * {@code vnp_SecureHashType} ⇒ chữ ký phải GIỐNG HỆT.
   */
  @Test
  void secureHashTypeKhongDuocBam() {
    Map<String, String> coHashType = new LinkedHashMap<>();
    coHashType.put("vnp_TxnRef", "0123456789abcdef0123456789abcdef");
    coHashType.put("vnp_Amount", "5000000");
    coHashType.put("vnp_SecureHashType", "SHA256");

    Map<String, String> khongHashType = new LinkedHashMap<>();
    khongHashType.put("vnp_TxnRef", "0123456789abcdef0123456789abcdef");
    khongHashType.put("vnp_Amount", "5000000");

    assertThat(signatureServiceApiCu.sign(coHashType))
        .isEqualTo(signatureServiceApiCu.sign(khongHashType));
    // Và chuỗi băm phải là đúng canonical không có vnp_SecureHashType.
    assertThat(signatureServiceApiCu.sign(coHashType))
        .isEqualTo(
            hmacSha256Hex(SECRET, "vnp_Amount=5000000&vnp_TxnRef=0123456789abcdef0123456789abcdef"));
  }

  /** Tham số kiểu hash cũng không được đưa vào QUERY gửi lên gateway. */
  @Test
  void signedQueryStringKhongChuaSecureHashType() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("vnp_TxnRef", "0123456789abcdef0123456789abcdef");
    params.put("vnp_SecureHashType", "SHA256");

    assertThat(signatureServiceApiCu.signedQueryString(params)).doesNotContain("vnp_SecureHashType");
  }

  // ── API 2.1.0: HMAC-SHA512 ────────────────────────────────────────────────

  /**
   * API 2.1.0 ký bằng HMAC-SHA512 và chữ ký phải khớp ĐÚNG chuỗi query gửi
   * lên gateway (bất biến cốt lõi của VNPay).
   *
   * <p>Cấu hình ở đây giống production: {@code hash-algorithm} RỖNG ⇒ tự chọn
   * theo phiên bản API ⇒ SHA512.
   */
  @Test
  void chuKyApi21Sha512DungDungChuoiDaGuiLenGateway() {
    VnpayConfig api21 = new VnpayConfig(
        true, "TMNCODE", SECRET, "https://app.example/return", "https://app.example/ipn",
        VnpayConfig.DEFAULT_BASE_URL, "", "", "pay", "2.1.0", "other");
    VnpaySignatureService service = new VnpaySignatureService(api21);

    Map<String, String> params = new LinkedHashMap<>();
    params.put("vnp_TxnRef", "0123456789abcdef0123456789abcdef");
    params.put("vnp_Amount", "5000000");
    params.put("vnp_Command", "pay");
    params.put("vnp_Version", "2.1.0");
    params.put("vnp_OrderType", "other");
    params.put("vnp_IpAddr", "203.0.113.7");

    String query = service.signedQueryString(params);
    int marker = query.lastIndexOf("&vnp_SecureHash=");
    assertThat(marker).isGreaterThan(0);
    String sentToGateway = query.substring(0, marker);
    String hashInQuery = query.substring(marker + "&vnp_SecureHash=".length());

    // SHA512 -> 128 hex ky tu.
    assertThat(hashInQuery).hasSize(128).matches("[0-9a-f]{128}");
    assertThat(hmacSha512Hex(SECRET, sentToGateway)).isEqualTo(hashInQuery);
  }
}
