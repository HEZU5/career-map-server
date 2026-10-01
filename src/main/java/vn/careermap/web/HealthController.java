package vn.careermap.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health check cho Render — CỐ TÌNH không chạm database để không bị fail khi
 * Neon scale-to-zero (cold start làm truy vấn DB chậm vài giây).
 *
 * <p>Render gọi {@code GET /health} (xem {@code healthCheckPath} trong
 * render.yaml) và coi 2xx là service khoẻ.
 */
@RestController
public class HealthController {

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "ok");
  }
}
