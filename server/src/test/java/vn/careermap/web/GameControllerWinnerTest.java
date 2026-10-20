package vn.careermap.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.service.GameService;
import vn.careermap.web.dto.WinnerRequest;
import vn.careermap.web.dto.WinnerResponse;

/**
 * Controller màn kết quả: client hỏi server "đủ điều kiện chiến thắng chưa"
 * rồi mới rẽ nhánh hiển thị.
 *
 * <p>Thuần JUnit + Mockito, không Spring context, không database — chỉ kiểm tra
 * controller gọi đúng service và đóng gói đúng cấu hình cho client.
 */
class GameControllerWinnerTest {

  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, true, 5L);

  private GameService gameService() {
    GameService service = mock(GameService.class);
    when(service.checkWinner(any(WinnerRequest.class)))
        .thenReturn(new WinnerResponse(true, 12, 7));
    return service;
  }

  @Test
  void getCheckWinnerTraVeCungKetQuaVoiService() {
    GameController controller = new GameController(gameService(), RULES);

    WinnerResponse response = controller.checkWinnerByPlayerId("guest-1");

    assertThat(response.eligibleToFinish()).isTrue();
    assertThat(response.totalAnswered()).isEqualTo(12);
    assertThat(response.currentPosition()).isEqualTo(7);
  }

  @Test
  void postVaGetCheckWinnerDungMotNguonSuThat() {
    GameController controller = new GameController(gameService(), RULES);

    WinnerResponse post = controller.checkWinner(new WinnerRequest("guest-1"));
    WinnerResponse get = controller.checkWinnerByPlayerId("guest-1");

    assertThat(get).isEqualTo(post);
  }

  @Test
  void configChoClientBietVnpayDangBat() {
    GameController controller = new GameController(gameService(), RULES);

    // Client ẩn nút "Nhận báo cáo" khi cờ này false.
    assertThat(controller.config().vnpayEnabled()).isTrue();
  }
}
