package vn.careermap.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Ghim GIÁ TRỊ MẶC ĐỊNH của 4 khoá cấu hình mới, đi qua Spring chứ không truyền
 * literal vào constructor.
 *
 * <p>Lý do tách khỏi {@code GameRulesConfigTest}: test đó dựng {@code
 * GameRulesConfig} bằng constructor tay nên sửa {@code application.properties}
 * hoặc sửa phần default trong {@code @Value} thì nó vẫn xanh. Hai test dưới đây
 * cho Spring tự tạo bean, nên đổi mặc định ở đâu cũng phải thấy test đỏ.
 *
 * <p>Hai lớp bảo vệ khác nhau, mỗi lớp một nơi có thể gõ sai:
 *
 * <ul>
 *   <li>{@code application.properties} khai {@code payment.amount-vnd=${PAYMENT_AMOUNT_VND:50000}}
 *       — đổi mặc định ở đây là đổi giá trị thật của bản deploy.
 *   <li>Phần default trong {@code @Value("${payment.amount-vnd:50000}")} là lưới an toàn
 *       khi property vắng mặt; đổi nó sẽ không ai thấy cho tới khi hết {@code application.properties}.
 * </ul>
 *
 * <p>Không dựng web server, không chạm database: chỉ đăng ký đúng {@code GameRulesConfig}
 * — cùng kiểu test nhẹ như {@code CorsConfigTest}/{@code HealthControllerTest}.
 */
class GameRulesConfigDefaultsTest {

  /** Nạp đúng {@code application.properties} của main, y như lúc chạy thật trên Render. */
  private final ApplicationContextRunner taiPropertiesThat =
      new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

  /** Không nạp property nào — chỉ còn phần default viết trong {@code @Value}. */
  private final ApplicationContextRunner khongCoProperties = new ApplicationContextRunner();

  @Test
  void applicationPropertiesChoDungGiaTriMacDinh() {
    taiPropertiesThat.withUserConfiguration(GameRulesConfig.class).run(context -> {
      assertThat(context).hasNotFailed();
      GameRulesConfig rules = context.getBean(GameRulesConfig.class);

      assertThat(rules.getPaymentAmountVnd()).isEqualTo(50000L);
      assertThat(rules.getPaymentTtlMinutes()).isEqualTo(15L);
      assertThat(rules.getWinMinLaps()).isEqualTo(1);
      assertThat(rules.getWinMinAnswers()).isEqualTo(12);
    });
  }

  @Test
  void giaTriMacDinhVietTrongValueCungLaBang() {
    khongCoProperties.withUserConfiguration(GameRulesConfig.class).run(context -> {
      assertThat(context).hasNotFailed();
      GameRulesConfig rules = context.getBean(GameRulesConfig.class);

      assertThat(rules.getPaymentAmountVnd()).isEqualTo(50000L);
      assertThat(rules.getPaymentTtlMinutes()).isEqualTo(15L);
      assertThat(rules.getWinMinLaps()).isEqualTo(1);
      assertThat(rules.getWinMinAnswers()).isEqualTo(12);
    });
  }
}