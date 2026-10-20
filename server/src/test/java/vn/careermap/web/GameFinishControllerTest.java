package vn.careermap.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.service.GameService;
import vn.careermap.web.dto.FinishRequest;
import vn.careermap.web.dto.GameSnapshot;

/**
 * {@code POST /api/game/finish} — hợp đồng HTTP của endpoint kết thúc ván.
 *
 * <p>Trước đây toàn bộ {@code GameFinishTest} gọi service TRỰC TIẾP ⇒ không
 * test nào bắt được việc client gửi thừa field {@code winnerKey}. Nếu ai đó
 * bật strict deserialization thì request đó thành 400 ⇒ ván không kết thúc
 * được ⇒ không ai thắng ⇒ không ai trả tiền.
 */
@WebMvcTest(GameController.class)
class GameFinishControllerTest {

  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, false, 5L);

  @Autowired MockMvc mvc;

  @MockitoBean GameService gameService;

  @TestConfiguration
  static class ServiceConfig {
    @Bean
    GameRulesConfig rules() {
      return RULES;
    }
  }

  private static GameSnapshot finishedSnapshot() {
    return new GameSnapshot(
        "H12345",
        true,
        0,
        false,
        false,
        null,
        0L,
        null,
        List.of(),
        0L,
        "NONE",
        null,
        null,
        false,
        false,
        Instant.now(),
        "FINISHED",
        "host-H12345",
        null,
        "host-H12345",
        Instant.now(),
        "host-H12345",
        1);
  }

  /** Client gửi đúng body này: KHÔNG có {@code winnerKey}. */
  @Test
  void chuPhongKetThucDuoc() throws Exception {
    when(gameService.finish(any(FinishRequest.class))).thenReturn(finishedSnapshot());

    mvc.perform(
            post("/api/game/finish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"playerId\":\"host-H12345\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FINISHED"));
  }

  /** Khách gọi thì server từ chối (403) — kể cả khi ván đã kết thúc. */
  @Test
  void khachKhongKetThucDuoc() throws Exception {
    when(gameService.finish(any(FinishRequest.class)))
        .thenThrow(
            new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Chỉ chủ phòng mới kết thúc được ván"));

    mvc.perform(
            post("/api/game/finish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"playerId\":\"guest-H12345-1\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Chỉ chủ phòng mới kết thúc được ván"));
  }

  /**
   * Body client gửi phải deserialize được và KHÔNG mang field {@code winnerKey}
   * (server tự chấm người thắng — không nhận từ client).
   */
  @Test
  void requestKhongConFieldWinnerKey() throws Exception {
    assertThat(FinishRequest.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .containsExactly("playerId");
  }
}
