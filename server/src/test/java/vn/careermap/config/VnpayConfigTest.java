package vn.careermap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Cấu hình VNPay phải đủ CẢ BỐN giá trị thì mới tạo được đơn: mã merchant
 * (TmnCode), khoá bí mật (HashSecret), URL trả về (ReturnUrl), URL IPN.
 *
 * <p>Thiếu {@code ReturnUrl} → người chơi thanh toán xong không được đưa về
 * app. Thiếu {@code IpnUrl} → server không nhận IPN → đơn kẹt PENDING rồi
 * hết hạn, dù người chơi đã trả tiền. Vì vậy phải gate trước khi ghi đơn.
 *
 * <p>Thuần JUnit 5 + AssertJ — không Spring context, không DB.
 */
class VnpayConfigTest {

  private static VnpayConfig config(String tmnCode, String hashSecret, String returnUrl,
      String ipnUrl) {
    return new VnpayConfig(true, tmnCode, hashSecret, returnUrl, ipnUrl);
  }

  @Test
  void dayDuBonGiaTriThiKhongThieuBienNao() {
    VnpayConfig config =
        config("TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn");

    assertThat(config.isConfigured()).isTrue();
    assertThat(config.missingSettings()).isEmpty();
  }

  @Test
  void thieuMaMerchantThiBaoThieuBienDo() {
    VnpayConfig config = config("", "secret", "https://app.example/return",
        "https://app.example/ipn");

    assertThat(config.isConfigured()).isFalse();
    assertThat(config.missingSettings()).containsExactly(VnpayConfig.ENV_TMN_CODE);
  }

  @Test
  void thieuKhoaBiMatThiBaoThieuBienDo() {
    VnpayConfig config = config("TMNCODE", "", "https://app.example/return",
        "https://app.example/ipn");

    assertThat(config.isConfigured()).isFalse();
    assertThat(config.missingSettings()).containsExactly(VnpayConfig.ENV_HASH_SECRET);
  }

  @Test
  void thieuUrlTraVeThiBaoThieuBienDo() {
    VnpayConfig config = config("TMNCODE", "secret", "", "https://app.example/ipn");

    assertThat(config.isConfigured()).isFalse();
    assertThat(config.missingSettings()).containsExactly(VnpayConfig.ENV_RETURN_URL);
  }

  @Test
  void thieuUrlIpnThiBaoThieuBienDo() {
    VnpayConfig config = config("TMNCODE", "secret", "https://app.example/return", "");

    assertThat(config.isConfigured()).isFalse();
    assertThat(config.missingSettings()).containsExactly(VnpayConfig.ENV_IPN_URL);
  }

  @Test
  void thieuNhieuBienThiBaoDayDuTheoThuTu() {
    VnpayConfig config = config("", "", "", "");

    assertThat(config.isConfigured()).isFalse();
    assertThat(config.missingSettings())
        .containsExactly(
            VnpayConfig.ENV_TMN_CODE,
            VnpayConfig.ENV_HASH_SECRET,
            VnpayConfig.ENV_RETURN_URL,
            VnpayConfig.ENV_IPN_URL);
  }

  @Test
  void giaTriChiToanKhoangTrangCungTinhLaThieu() {
    VnpayConfig config =
        config("TMNCODE", "secret", "   ", "\t\n");

    assertThat(config.isConfigured()).isFalse();
    assertThat(config.missingSettings())
        .containsExactly(VnpayConfig.ENV_RETURN_URL, VnpayConfig.ENV_IPN_URL);
  }

  @Test
  void danhSachThieuBienKhongTheThayDoiTuBenNgoai() {
    VnpayConfig config = config("", "secret", "https://app.example/return",
        "https://app.example/ipn");

    List<String> missing = config.missingSettings();
    assertThat(missing).isUnmodifiable();
  }
}
