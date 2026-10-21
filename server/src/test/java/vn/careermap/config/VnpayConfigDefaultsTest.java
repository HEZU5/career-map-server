package vn.careermap.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Bảo vệ MẶC ĐỊNH của nhóm {@code vnpay.*} đọc từ {@code application.properties}
 * THẬT — đúng như lúc chạy trên Render.
 *
 * <p><b>Vì sao test này tồn tại:</b> sự cố thanh toán 404 trên production là do
 * {@code application.properties} còn trỏ {@code vnpay.base-url} vào endpoint cũ
 * ({@code paymentgateway/paymenturl.htm}) và {@code vnpay.command} còn là
 * {@code pay_web}. Mọi test khác trong repo đều dựng config BẰNG TAY (truyền
 * literal vào constructor) hoặc set property thủ công ⇒ chúng XANH dù file cấu
 * hình đã sai. Test này nạp đúng file của main ⇒ đổi mặc định trong file là
 * test này đỏ ⇒ 404 không thể lặp lại trong im lặng.
 *
 * <p>Cùng kiểu với {@link GameRulesConfigDefaultsTest} (bảo vệ mặc định
 * {@code game.*}/{@code payment.*} của cùng kiểu).
 *
 * <p>Không dựng web server, không chạm database: chỉ đăng ký đúng
 * {@link VnpayConfig} — cùng kiểu test nhẹ như {@code HealthControllerTest}.
 */
class VnpayConfigDefaultsTest {

  /** Nạp đúng {@code application.properties} của main, y như lúc chạy thật trên Render. */
  private final ApplicationContextRunner taiPropertiesThat =
      new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

  @Test
  void applicationPropertiesChoDungGiaTriMacDinhVnpay() {
    taiPropertiesThat.withUserConfiguration(VnpayConfig.class).run(context -> {
      assertThat(context).hasNotFailed();
      VnpayConfig config = context.getBean(VnpayConfig.class);

      // Endpoint cũ (paymentgateway/paymenturl.htm) đã bị VNPay thu hồi ⇒ 404.
      assertThat(config.getBaseUrl())
          .isEqualTo("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
      // Bộ giao thức API 2.1.0: pay + vnp_Version + vnp_OrderType (bắt buộc).
      assertThat(config.getCommand()).isEqualTo("pay");
      assertThat(config.getVersion()).isEqualTo("2.1.0");
      assertThat(config.getOrderType()).isEqualTo("other");
      // vnpay.hash-algorithm để RỖNG => tự chọn theo phiên bản API => SHA512.
      assertThat(config.getHashAlgorithm()).isEqualTo("HmacSHA512");
      assertThat(config.isApi21()).isTrue();
    });
  }
}
