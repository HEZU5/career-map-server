package vn.careermap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Ghim cấu hình lỗi của Spring: body lỗi phải CÓ field {@code message}.
 *
 * <p>Spring Boot mặc định {@code server.error.include-message=never} — body
 * lỗi chỉ có {@code timestamp/status/error/path}. Client (Flutter) đọc
 * {@code message} để hiện câu báo tiếng Việt cho người chơi, nên nếu khoá này
 * bị đổi về {@code never}/{@code on_param} thì mọi lỗi lại quay về
 * "Server trả 403." — đúng lỗi Task 11 fix1 (finding I3).
 *
 * <p>Đọc thẳng {@code application.properties} trên classpath (y như lúc deploy).
 * Thuần JUnit 5 + AssertJ: không dựng Spring context, không web server, không
 * database.
 */
class ErrorMessageConfigTest {

  @Test
  void bodyLoiPhaiKemMessageChoClient() throws Exception {
    Properties properties = new Properties();
    try (InputStream in =
        ErrorMessageConfigTest.class.getClassLoader().getResourceAsStream("application.properties")) {
      assertThat(in).as("application.properties phải nằm trên classpath").isNotNull();
      properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    assertThat(properties.getProperty("server.error.include-message")).isEqualTo("always");
  }
}
