package vn.careermap.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.careermap.service.RoomService;
import vn.careermap.web.RoomController;

/**
 * Xác nhận CORS tập trung (CorsConfig) chỉ cho đúng origin của Firebase Hosting
 * và dev localhost — không mở "*".
 */
@WebMvcTest(RoomController.class)
class CorsConfigTest {

  private static final String FIREBASE_ORIGIN =
      "https://career-map-2026-b08f1.web.app";

  @Autowired MockMvc mvc;

  @MockitoBean RoomService roomService;

  @Test
  void allowsFirebaseHostingPreflight() throws Exception {
    mvc.perform(
            options("/api/rooms")
                .header(HttpHeaders.ORIGIN, FIREBASE_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FIREBASE_ORIGIN));
  }

  @Test
  void allowsLocalhostDevPreflightOnAnyPort() throws Exception {
    mvc.perform(
            options("/api/rooms")
                .header(HttpHeaders.ORIGIN, "http://localhost:53210")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:53210"));
  }

  @Test
  void rejectsUnknownOrigin() throws Exception {
    mvc.perform(
            options("/api/rooms")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
        .andExpect(status().isForbidden());
  }
}
