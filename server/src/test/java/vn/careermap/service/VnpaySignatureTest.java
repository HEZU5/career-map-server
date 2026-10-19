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
}
