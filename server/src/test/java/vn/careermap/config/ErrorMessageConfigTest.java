package vn.careermap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Ghim cấu hình lỗi của Spring: KHÔNG được rò rỉ message của exception nội
 * bộ vào body lỗi 500.
 *
 * <p>Spring Boot mặc định {@code server.error.include-message=never}. Vòng
 * fix1 của Task 11 đã bật lên {@code always} để client đọc được câu tiếng
 * Việt trong field {@code message} — nhưng khoá đó áp cho MỌI lỗi, kể cả
 * lỗi 500 do exception không lường trước, nên lộ JDBC URL + user DB, tên
 * bảng/constraint SQL, tên biến môi trường.
 *
 * <p>Vòng fix2 bỏ khoá toàn cục: chỉ {@code ResponseStatusException} (lỗi do
 * code chủ động ném, luôn kèm câu tiếng Việt) mới có field {@code message},
 * xem {@code ResponseStatusExceptionAdvice}. Lỗi 500 giữ body mặc định của
 * Spring — KHÔNG có {@code message}.
 *
 * <p>Đọc thẳng {@code application.properties} trên classpath (y như lúc deploy).
 * Thuần JUnit 5 + AssertJ: không dựng Spring context, không web server, không
 * database.
 */
class ErrorMessageConfigTest {

  @Test
  void bodyLoi500KhongDuocLeMessageNoiBo() throws Exception {
    // KHÔNG được bật "always" — đó là khoá làm lộ chi tiết hạ tầng.
    assertThat(loadProperties().getProperty("server.error.include-message"))
        .as("server.error.include-message phải KHÔNG bật 'always' (lộ chi tiết hạ tầng)")
        .isNotEqualTo("always");
  }

  @Test
  void serverErrorIncludeMessageKhongBaoLoiRieng() throws Exception {
    // Chốt rõ ràng giá trị đang dùng (mặc định của Boot cũng là "never").
    assertThat(loadProperties().getProperty("server.error.include-message")).isEqualTo("never");
  }

  private static Properties loadProperties() throws Exception {
    Properties properties = new Properties();
    try (InputStream in =
        ErrorMessageConfigTest.class.getClassLoader().getResourceAsStream("application.properties")) {
      assertThat(in).as("application.properties phải nằm trên classpath").isNotNull();
      properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    return properties;
  }
}
