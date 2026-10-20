package vn.careermap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

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

  // ── Bộ giao thức API (vnp_Command / vnp_Version / vnp_OrderType) ──────────

  /** Mặc định là API CŨ: {@code pay_web}, không gửi version/order-type. */
  @Test
  void macDinhLaApiCuPayWeb() {
    VnpayConfig config = config("TMNCODE", "secret", "https://app.example/return",
        "https://app.example/ipn");

    assertThat(config.getCommand()).isEqualTo("pay_web");
    assertThat(config.getVersion()).isEmpty();
    assertThat(config.getOrderType()).isEmpty();
    assertThat(config.isApi21()).isFalse();
    // API cũ báo thành công bằng vnp_TransactionStatus=01.
    assertThat(config.successTransactionStatus()).isEqualTo("01");
    // API cũ (2.0.x) được gửi vnp_SecureHashType.
    assertThat(config.allowsSecureHashType()).isTrue();
  }

  /** API 2.1.0: {@code vnp_Command=pay} + {@code vnp_Version=2.1.0}. */
  @Test
  void api21NhanRaTheoCommandHoacVersion() {
    VnpayConfig theoCommand = new VnpayConfig(
        true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
        VnpayConfig.DEFAULT_BASE_URL, "SHA512", "", "pay", "", "other");
    VnpayConfig theoVersion = new VnpayConfig(
        true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
        VnpayConfig.DEFAULT_BASE_URL, "SHA512", "", "", "2.1.0", "other");

    assertThat(theoCommand.isApi21()).isTrue();
    assertThat(theoVersion.isApi21()).isTrue();
    // API 2.1.0 báo thành công bằng vnp_TransactionStatus=00.
    assertThat(theoCommand.successTransactionStatus()).isEqualTo("00");
    assertThat(theoVersion.successTransactionStatus()).isEqualTo("00");
    // API 2.1.0 KHÔNG được gửi vnp_SecureHashType.
    assertThat(theoCommand.allowsSecureHashType()).isFalse();
    assertThat(theoVersion.allowsSecureHashType()).isFalse();
  }

  /** API 2.1.0 bắt buộc có {@code vnp_Version} + {@code vnp_OrderType}. */
  @Test
  void api21ThieuVersionHoacOrderTypeThiBaoThieuBienDo() {
    VnpayConfig thieu = new VnpayConfig(
        true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
        VnpayConfig.DEFAULT_BASE_URL, "SHA512", "", "pay", "", "");

    assertThat(thieu.isConfigured()).isFalse();
    assertThat(thieu.missingSettings())
        .containsExactly(VnpayConfig.ENV_VERSION, VnpayConfig.ENV_ORDER_TYPE);

    VnpayConfig du = new VnpayConfig(
        true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
        VnpayConfig.DEFAULT_BASE_URL, "SHA512", "", "pay", "2.1.0", "other");

    assertThat(du.isConfigured()).isTrue();
    assertThat(du.missingSettings()).isEmpty();
  }

  /**
   * Spring phải dùng ĐÚNG constructor có {@code @Value}.
   *
   * <p>{@link VnpayConfig} có nhiều constructor (một cho Spring, hai cho test).
   * Nếu Spring chọn nhầm constructor không có {@code @Value} thì mọi biến
   * môi trường VNPay đều rỗng ⇒ {@code isConfigured()} false ⇒ KHÔNG tạo được
   * đơn ⇒ tính năng thanh toán chết trong production mà test vẫn xanh.
   *
   * <p>Test này dựng context Spring thật (không DB) nên bắt được lỗi đó.
   */
  @Test
  void springDungConstructorCoValueVaDocDungBienMoiTruong() {
    try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
      TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
          ctx,
          "vnpay.enabled=true",
          "vnpay.tmn-code=TMNCODE",
          "vnpay.hash-secret=secret",
          "vnpay.return-url=https://app.example/return",
          "vnpay.ipn-url=https://app.example/ipn",
          "vnpay.command=pay",
          "vnpay.version=2.1.0",
          "vnpay.order-type=other",
          "vnpay.hash-algorithm=SHA512");

      ctx.register(VnpayConfig.class);
      ctx.refresh();

      VnpayConfig bean = ctx.getBean(VnpayConfig.class);

      assertThat(bean.isEnabled()).isTrue();
      assertThat(bean.getTmnCode()).isEqualTo("TMNCODE");
      assertThat(bean.getHashSecret()).isEqualTo("secret");
      assertThat(bean.getReturnUrl()).isEqualTo("https://app.example/return");
      assertThat(bean.getIpnUrl()).isEqualTo("https://app.example/ipn");
      assertThat(bean.getCommand()).isEqualTo("pay");
      assertThat(bean.getVersion()).isEqualTo("2.1.0");
      assertThat(bean.getOrderType()).isEqualTo("other");
      assertThat(bean.getHashAlgorithm()).isEqualTo("SHA512");
      assertThat(bean.isConfigured()).isTrue();
    }
  }
}
