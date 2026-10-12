package vn.careermap.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.config.GameRulesSnapshot;
import vn.careermap.domain.RiasecCategory;
import vn.careermap.service.GameService;
import vn.careermap.web.dto.AnswerRequest;
import vn.careermap.web.dto.AnswerResponse;
import vn.careermap.web.dto.DiceResponse;
import vn.careermap.web.dto.FinishRequest;
import vn.careermap.web.dto.GameSnapshot;
import vn.careermap.web.dto.RiasecDescription;
import vn.careermap.web.dto.RollRequest;
import vn.careermap.web.dto.ScoreResponse;
import vn.careermap.web.dto.TileResponse;
import vn.careermap.web.dto.WinnerRequest;
import vn.careermap.web.dto.WinnerResponse;

@RestController
@RequestMapping("/api")
// CORS được cấu hình TẬP TRUNG ở CorsConfig (không dùng @CrossOrigin "*" nữa)
// để bản deploy chỉ cho đúng origin Firebase Hosting + dev localhost.
public class GameController {
  private final GameService gameService;
  private final GameRulesConfig gameRules;

  public GameController(GameService gameService, GameRulesConfig gameRules) {
    this.gameService = gameService;
    this.gameRules = gameRules;
  }

  @PostMapping("/game/roll-dice")
  public DiceResponse rollDice(@RequestBody RollRequest request) {
    return gameService.rollDice(request);
  }

  @GetMapping("/game/tile/{position}")
  public TileResponse tile(@PathVariable int position) {
    return gameService.tile(position);
  }

  @PostMapping("/game/answer")
  public AnswerResponse answer(@RequestBody AnswerRequest request) {
    return gameService.answer(request);
  }

  @GetMapping("/game/score/{playerId}")
  public ScoreResponse score(@PathVariable String playerId) {
    return gameService.score(playerId);
  }

  @PostMapping("/game/check-winner")
  public WinnerResponse checkWinner(@RequestBody WinnerRequest request) {
    return gameService.checkWinner(request);
  }

  @GetMapping("/riasec/{category}/description")
  public RiasecDescription description(@PathVariable RiasecCategory category) {
    return gameService.description(category);
  }

  /**
   * Nguồn cấu hình duy nhất cho client.
   *
   * <p>Client KHÔNG hard-code 15 giây hay 2 giây: nó đọc từ đây để đổi giá trị
   * ở {@link vn.careermap.config.GameRulesConfig} là áp dụng cho cả hai vế mà
   * không phải sửa hai nơi.
   */
  @GetMapping("/game/config")
  public GameRulesSnapshot config() {
    return gameRules.snapshot();
  }

  /**
   * Kết thúc ván — client vẫn là bên quyết định thắng thua (không đổi luật
   * chơi), endpoint này chỉ ghi trạng thái để phòng được giữ cho bảng kết quả
   * rồi mới dọn theo {@code FINISHED_ROOM_TTL_MIN}.
   */
  @PostMapping("/game/finish")
  public GameSnapshot finish(@RequestBody FinishRequest request) {
    return gameService.finish(request);
  }
}