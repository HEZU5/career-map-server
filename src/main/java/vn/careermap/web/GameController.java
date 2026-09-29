package vn.careermap.web;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.careermap.domain.RiasecCategory;
import vn.careermap.service.GameService;
import vn.careermap.web.dto.AnswerRequest;
import vn.careermap.web.dto.AnswerResponse;
import vn.careermap.web.dto.DiceResponse;
import vn.careermap.web.dto.RiasecDescription;
import vn.careermap.web.dto.RollRequest;
import vn.careermap.web.dto.ScoreResponse;
import vn.careermap.web.dto.TileResponse;
import vn.careermap.web.dto.WinnerRequest;
import vn.careermap.web.dto.WinnerResponse;

@RestController
@RequestMapping("/api")
// Bật CORS cho bản web (flutter run -d chrome/edge): browser gửi POST
// roll/answer từ origin khác, nếu thiếu header này REST bị chặn → server
// không nhận nước đi → không broadcast → mất đồng bộ.
@CrossOrigin(origins = "*")
public class GameController {
  private final GameService gameService;

  public GameController(GameService gameService) {
    this.gameService = gameService;
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
}