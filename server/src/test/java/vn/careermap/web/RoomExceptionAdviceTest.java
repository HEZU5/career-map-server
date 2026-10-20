package vn.careermap.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import vn.careermap.exception.NotYourTurnException;

/**
 * 7 lớp exception gắn {@code @ResponseStatus} ở MỨC LỚP phải trả body
 * {@code {status, error, message}} y hệt {@link ResponseStatusExceptionAdvice}
 * xử lý {@code ResponseStatusException}.
 *
 * <p>Trước đây chúng KHÔNG kế thừa {@code ResponseStatusException} nên không
 * được advice bắt → rơi xuống {@code ResponseStatusExceptionResolver} →
 * {@code sendError()} → body lỗi mặc định của Spring KHÔNG có field
 * {@code message} → client chỉ thấy "Server trả 409." thay vì lý do cụ thể.
 *
 * <p>HTTP status đọc từ chính annotation {@code @ResponseStatus} của từng lớp
 * (404 / 409 / 409 / 410 / 409 / 409 / 409) — test ghim đúng từng mã để chắc
 * chắn advice không hardcode sai.
 *
 * <p>Dùng {@code @WebMvcTest} — không database, không service thật.
 */
@WebMvcTest(controllers = FailingTestController.class)
class RoomExceptionAdviceTest {

  @Autowired MockMvc mvc;

  @Test
  void khongTimThayPhongTraVeLyDoCuThe() throws Exception {
    mvc.perform(get("/test/room-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.error").value("Not Found"))
        .andExpect(jsonPath("$.message").value("Không tìm thấy phòng R12345"));
  }

  @Test
  void phongDaDayTraVeLyDoCuThe() throws Exception {
    mvc.perform(get("/test/room-full"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(jsonPath("$.message").value("Phòng R12345 đã đầy"));
  }

  @Test
  void phongDaBatDauTraVeLyDoCuThe() throws Exception {
    mvc.perform(get("/test/room-already-started"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(jsonPath("$.message").value("Phòng R12345 đã bắt đầu"));
  }

  @Test
  void phongDaDongTraVeLyDoCuThe() throws Exception {
    mvc.perform(get("/test/room-closed"))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.status").value(410))
        .andExpect(jsonPath("$.error").value("Gone"))
        .andExpect(jsonPath("$.message").value("Phòng đã đóng"));
  }

  @Test
  void nguoiChoiDaRoiPhongTraVeLyDoCuThe() throws Exception {
    mvc.perform(get("/test/player-left"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(jsonPath("$.message").value("Người chơi đã rời phòng: guest-R12345-1"));
  }

  @Test
  void luotDangBanTraVeLyDoCuThe() throws Exception {
    mvc.perform(get("/test/turn-busy"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(jsonPath("$.message").value("Lượt trong phòng R12345 đang bận"));
  }

  @Test
  void chuaDenLuotCuaBanTraVeLyDoCuThe() throws Exception {
    mvc.perform(get("/test/not-your-turn"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(jsonPath("$.message").value("Chưa đến lượt của bạn trong phòng R12345"));
  }

  /**
   * KHÔNG xung đột với {@code ConflictAdvice}: xung đột optimistic-lock vẫn do
   * advice kia bắt (body text thuần {@code NotYourTurnException.MESSAGE}), còn
   * handler 7 lớp ở trên không "cướp" exception này.
   */
  @Test
  void xungDotOptimisticLockVanDoConflictAdviceXuLy() throws Exception {
    mvc.perform(get("/test/conflict"))
        .andExpect(status().isConflict())
        .andExpect(content().string(NotYourTurnException.MESSAGE));
  }
}
