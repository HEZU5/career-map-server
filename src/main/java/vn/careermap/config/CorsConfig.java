package vn.careermap.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cấu hình CORS TẬP TRUNG cho toàn bộ REST API ({@code /api/**}).
 *
 * <p>Danh sách origin lấy từ property {@code app.cors.allowed-origins}
 * (map từ env {@code CORS_ALLOWED_ORIGINS}, ngăn cách bằng dấu phẩy). Mặc định
 * đã bao gồm:
 *
 * <ul>
 *   <li>Firebase Hosting của app ({@code web.app} + {@code firebaseapp.com})
 *   <li>{@code localhost}/{@code 127.0.0.1} mọi cổng cho {@code flutter run
 *       -d chrome/edge}
 * </ul>
 *
 * <p>Cố tình KHÔNG dùng {@code "*"} để bản deploy chỉ phục vụ đúng tên miền.
 * Dùng {@code allowedOriginPatterns} để hỗ trợ wildcard cổng cho dev.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

  private final String[] allowedOrigins;

  public CorsConfig(
      @Value("${app.cors.allowed-origins}") String[] allowedOrigins) {
    this.allowedOrigins = allowedOrigins;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOriginPatterns(allowedOrigins)
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .maxAge(3600);

    // /health PHẢI có CORS: client web gọi endpoint này để đánh thức Render
    // (free plan ngủ sau ~15 phút, cold start 30–90s) trước khi gọi API thật.
    // Không khai báo ở đây thì trình duyệt chặn bằng CORS và việc đánh thức
    // không bao giờ thành công (PowerShell/curl không gửi Origin nên vẫn thấy
    // HTTP 200 — dễ bị tưởng là đã ổn).
    registry
        .addMapping("/health")
        .allowedOriginPatterns(allowedOrigins)
        .allowedMethods("GET", "OPTIONS")
        .allowedHeaders("*")
        .maxAge(3600);
  }
}
