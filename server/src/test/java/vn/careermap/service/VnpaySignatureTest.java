package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.careermap.config.VnpayConfig;

/**
 * Chữ ký VNPay là HMAC-SHA512 → KHÔNG: là HMAC-SHA256, khoá là HashSecret.
 *
 * <p>Vector cố định ở {@link #chuKyDungThuAToanSha256} tính bằng .NET
 * {@code HMACSHA256} nên là độc lập với implementation — nếu ai đó đổi nhầm
 * sang SHA512 (hex dài 128) hoặc quên bỏ {@code vnp_SecureHash}/giá trị rỏ thì
 * test đỏ.
 */
class VnpaySignatureTest {

  private final VnpayConfig config =
      new VnpayConfig(true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn");
  private final VnpaySignatureService signatureService = new VnpaySignatureService(config);

  @Test
  void chuKyDungThuAToanSha256() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("a", "1");
    params.put("b", "2");

    String hash = signatureService.sign(params);

    assertThat(hash).isEqualTo("604fe97c66c6393ff22e3cae366eee1131e351ebc736bf12f5d62e1755b7a233");
    assertThat(hash).hasSize(64);
  }

  @Test
  void boQuaSecureHashVaGiaTriRong() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("vnp_SecureHash", "chu-cu-cua-nguoi-khac");
    params.put("b", "2");
    params.put("a", "");
    params.put("c", null);
    params.put("d", "3");

    String hash = signatureService.sign(params);

    // Chỉ còn b=2 và d=3 mới tham gia ký.
    Map<String, String> chiCon = new LinkedHashMap<>();
    chiCon.put("b", "2");
    chiCon.put("d", "3");
    assertThat(hash).isEqualTo(signatureService.sign(chiCon));
  }

  /**
   * Thuật toán chữ ký nằm trong CẤU HÌNH để đổi được mà không cần build lại.
   *
   * <p>API cũ {@code paymentgateway/paymenturl.htm} dùng HMAC-SHA256 (64 hex ký
   * tự); API mới {@code paymentv2/vpcpay.html} dùng HMAC-SHA512 (128 hex ký tự).
   */
  @Test
  void sha256ChoHash64KyTu() {
    VnpayConfig sha256 =
        new VnpayConfig(
            true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
            VnpayConfig.DEFAULT_BASE_URL, "HmacSHA256", "");
    VnpaySignatureService service = new VnpaySignatureService(sha256);

    Map<String, String> params = new LinkedHashMap<>();
    params.put("a", "1");

    assertThat(service.sign(params)).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  void sha512ChoHash128KyTu() {
    VnpayConfig sha512 =
        new VnpayConfig(
            true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
            VnpayConfig.DEFAULT_BASE_URL, "SHA512", "SHA512");
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
}
