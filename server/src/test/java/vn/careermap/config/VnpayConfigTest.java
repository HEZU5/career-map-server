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

  /**
   * Mặc định là API 2.1.0: {@code paymentv2/vpcpay.html} + {@code pay} +
   * {@code vnp_Version=2.1.0} + {@code vnp_OrderType=other}.
   *
   * <p>Vì sao đổi: endpoint cũ {@code paymentgateway/paymenturl.htm} đã bị
   * VNPay thu hồi (HTTP 404 trên sandbox) nên không được làm mặc định.
   */
  @Test
  void macDinhLaApi21() {
    VnpayConfig config = config("TMNCODE", "secret", "https://app.example/return",
        "https://app.example/ipn");

    assertThat(config.getBaseUrl())
        .isEqualTo("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
    assertThat(config.getCommand()).isEqualTo("pay");
    assertThat(config.getVersion()).isEqualTo("2.1.0");
    assertThat(config.getOrderType()).isEqualTo("other");
    assertThat(config.isApi21()).isTrue();
    // Ctor rút gọn cũng tự chọn thuật toán theo phiên bản ⇒ fixture test khớp
    // production (trước đây ctor này ghim SHA256 ⇒ config "2.1.0 nhưng ký
    // SHA256" ⇒ gateway chắc chắn từ chối với "Sai chữ ký").
    assertThat(config.getHashAlgorithm()).isEqualTo("HmacSHA512");
    // API 2.1.0 báo thành công bằng vnp_TransactionStatus=00.
    assertThat(config.successTransactionStatus()).isEqualTo("00");
    // API 2.1.0 KHÔNG được gửi vnp_SecureHashType.
    assertThat(config.allowsSecureHashType()).isFalse();
  }

  /**
   * Ruling B: thuật toán chữ ký MẶC ĐỊNH THEO PHIÊN BẢN API — API 2.1.0 dùng
   * HMAC-SHA512 (hash 128 hex), API cũ dùng HMAC-SHA256 (64 hex).
   *
   * <p>Cấu hình ở đây giống HỆT cấu hình production: base/command/version/
   * order-type lấy mặc định (2.1.0) và {@code hash-algorithm} để RỖNG ⇒
   * {@code VnpayConfig} tự chọn SHA512.
   */
  @Test
  void macDinhApi21DungSha512() {
    VnpayConfig config = new VnpayConfig(
        true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
        VnpayConfig.DEFAULT_BASE_URL, "", "",
        VnpayConfig.DEFAULT_COMMAND, VnpayConfig.DEFAULT_VERSION, VnpayConfig.DEFAULT_ORDER_TYPE);

    assertThat(config.isApi21()).isTrue();
    assertThat(config.getHashAlgorithm()).isEqualTo("HmacSHA512");
    assertThat(config.getHashAlgorithm()).isEqualTo(VnpayConfig.DEFAULT_HASH_ALGORITHM_API21);
  }

  /**
   * API cũ ({@code pay_web}) vẫn phải dùng HMAC-SHA256 — không hardcode
   * SHA512 để còn quay lại được API cũ.
   */
  @Test
  void apiCuVanDungSha256() {
    VnpayConfig config = new VnpayConfig(
        true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
        VnpayConfig.DEFAULT_BASE_URL, "", "", "pay_web", "", "");

    assertThat(config.isApi21()).isFalse();
    assertThat(config.getHashAlgorithm()).isEqualTo("HmacSHA256");
    assertThat(config.getHashAlgorithm()).isEqualTo(VnpayConfig.DEFAULT_HASH_ALGORITHM_LEGACY);
  }

  /** Thuật toán chỉ định tay luôn thắng mặc định theo phiên bản. */
  @Test
  void hashAlgorithmChiDinhTayDuocGiuNguyen() {
    VnpayConfig config = new VnpayConfig(
        true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
        VnpayConfig.DEFAULT_BASE_URL, "SHA256", "",
        VnpayConfig.DEFAULT_COMMAND, VnpayConfig.DEFAULT_VERSION, VnpayConfig.DEFAULT_ORDER_TYPE);

    assertThat(config.getHashAlgorithm()).isEqualTo("SHA256");
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

  /**
   * API 2.1.0 mà {@code vnp_Version}/{@code vnp_OrderType} RỖNG thì phải tự
   * điền MẶC ĐỊNH — y hệt {@code command}/{@code base-url}.
   *
   * <p>Vì sao: Render có thể có biến {@code VNPAY_VERSION=} hoặc
   * {@code VNPAY_ORDER_TYPE=} RỖNG. Không có fallback thì {@code vnp_Version}
   * không được gửi ⇒ gateway trả "Dữ liệu gửi sang không đúng định dạng" ⇒
   * đơn bị từ chối. Không được phụ thuộc vào việc người vận hành có đặt rỗng
   * hay không.
   */
  @Test
  void api21VersionHayOrderTypeRongThiTuDienMacDinh() {
    VnpayConfig rong = new VnpayConfig(
        true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
        VnpayConfig.DEFAULT_BASE_URL, "", "", "pay", "", "");

    assertThat(rong.getVersion()).isEqualTo("2.1.0");
    assertThat(rong.getOrderType()).isEqualTo("other");
    assertThat(rong.isApi21()).isTrue();
    assertThat(rong.isConfigured()).isTrue();
    assertThat(rong.missingSettings()).isEmpty();
  }

  /**
   * API CŨ ({@code pay_web}) thì KHÔNG được tự điền version/order-type.
   *
   * <p>Vì sao: API cũ không có hai tham số này. Điền {@code 2.1.0}/{@code other}
   * vào thì {@code isApi21()} thành true ⇒ đổi luôn thuật toán ký (SHA512) và
   * mã thành công IPN ({@code 00}) ⇒ HỎNG đường quay lại API cũ.
   */
  @Test
  void apiCuKhongTuDienVersionHayOrderType() {
    VnpayConfig apiCu = new VnpayConfig(
        true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
        VnpayConfig.DEFAULT_BASE_URL, "", "", "pay_web", "", "");

    assertThat(apiCu.getVersion()).isEmpty();
    assertThat(apiCu.getOrderType()).isEmpty();
    assertThat(apiCu.isApi21()).isFalse();
    assertThat(apiCu.getHashAlgorithm()).isEqualTo("HmacSHA256");
    assertThat(apiCu.successTransactionStatus()).isEqualTo("01");
  }

  /** API 2.1.0 bắt buộc có {@code vnp_Version} + {@code vnp_OrderType}. */
  @Test
  void api21CoVersionVaOrderTypeThiKhongThieuBienNao() {
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

  /**
   * Đường đi của PRODUCTION: {@code vnpay.hash-algorithm} RỖNG (biến môi trường
   * {@code VNPAY_HASH_ALGORITHM} không được đặt) ⇒ Spring đọc được chuỗi rỗng ⇒
   * {@link VnpayConfig} tự chọn HMAC-SHA512 theo phiên bản API 2.1.0.
   *
   * <p>Đây là bài test bảo vệ đúng đường đi của production — nếu ai đó ghim
   * {@code HmacSHA256} vào {@code @Value} thì test này đỏ.
   */
  @Test
  void springKhiHashAlgorithmRongThiTuChonSha512() {
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
          "vnpay.hash-algorithm=");

      ctx.register(VnpayConfig.class);
      ctx.refresh();

      VnpayConfig bean = ctx.getBean(VnpayConfig.class);

      assertThat(bean.getHashAlgorithm()).isEqualTo("HmacSHA512");
      assertThat(bean.isApi21()).isTrue();
    }
  }
}
