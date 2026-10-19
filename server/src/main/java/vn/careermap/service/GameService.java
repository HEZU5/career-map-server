package vn.careermap.service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.domain.Player;
import vn.careermap.domain.RiasecCategory;
import vn.careermap.domain.Room;
import vn.careermap.domain.RoomStatus;
import vn.careermap.domain.TileType;
import vn.careermap.domain.TurnPhase;
import vn.careermap.exception.NotYourTurnException;
import vn.careermap.exception.PlayerLeftException;
import vn.careermap.exception.TurnBusyException;
import vn.careermap.repo.PlayerRepository;
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

@Service
public class GameService {
  private static final Logger log = LoggerFactory.getLogger(GameService.class);
  private static final int WIN_ANSWERS = 12;

  private final PlayerRepository playerRepository;
  private final GameBroadcaster broadcaster;
  private final GameSnapshotBuilder snapshotBuilder;
  private final TurnCoordinator turns;
  private final GameRulesConfig rules;

  public GameService(
      PlayerRepository playerRepository,
      GameBroadcaster broadcaster,
      GameSnapshotBuilder snapshotBuilder,
      TurnCoordinator turns,
      GameRulesConfig rules) {
    this.playerRepository = playerRepository;
    this.broadcaster = broadcaster;
    this.snapshotBuilder = snapshotBuilder;
    this.turns = turns;
    this.rules = rules;
  }

  /**
   * Tung xúc xắc cho lượt hiện tại.
   *
   * <p>Mọi lối vào — bấm tay, tự tung của máy người chơi, tự tung của máy trọng
   * tài, sweeper của server — đều qua đây. {@code expectedTurnId} là điều kiện
   * compare-and-set: lượt đã đổi thì 409, bên gọi im lặng bỏ qua.
   *
   * <p>Ván offline (không có phòng) giữ nguyên hành vi cũ: chỉ người này chơi,
   * không đồng hồ, không chặn.
   */
  @Transactional
  public DiceResponse rollDice(RollRequest request) {
    Player player = resolvePlayer(request.playerId());
    if (player.isLeft()) {
      throw new PlayerLeftException(player.getPlayerKey());
    }
    Room room = player.getRoom();
    if (room == null || !room.isActive()) {
      return rollSolo(player);
    }
    if (room.isFinished()) {
      throw new TurnBusyException(room.getCode());
    }
    if (request.expectedTurnId() != null && request.expectedTurnId() != room.getTurnId()) {
      throw new NotYourTurnException(room.getCode());
    }
    // Phòng vừa bắt đầu, chưa ai mở lượt → mở lượt cho người đến lượt rồi tung.
    if (room.getTurnPhase() == TurnPhase.NONE) {
      turns.startNextTurn(room, Instant.now());
    }

    TurnCoordinator.TurnRoll roll =
        turns.rollForTurn(
            room, player, room.getTurnId(), request.isAuto(),
            request.reason() == null ? "manual" : request.reason());
    if (roll == null) {
      throw new NotYourTurnException(room.getCode());
    }
    broadcaster.gameUpdated(room.getCode(), snapshotBuilder.build(room));
    return new DiceResponse(roll.value(), roll.position(), roll.moved(), roll.auto(), room.getTurnId());
  }

  /** Ván một người chơi: không có phòng, không đồng hồ, giữ luật đi cũ. */
  private DiceResponse rollSolo(Player player) {
    int value = turns.rollValue();
    int pos = player.getCurrentPosition();
    int raw = pos - value;
    int next = Math.floorMod(raw, TurnCoordinator.pathSize());
    player.setCurrentPosition(next);
    if (raw < 0) {
      player.addCompletedLaps(1);
    }
    return new DiceResponse(value, next, true, false, 0);
  }

  /**
   * Trả lời thẻ ở ô đích. Chỉ chấp nhận khi đang ở pha chờ trả lời và đúng lượt —
   * chặn việc gọi thẳng API để farm điểm mà không phải tung. Hết giờ thì không
   * cộng điểm, không phạt, chỉ chuyển lượt (xử lý ở {@link TurnCoordinator}).
   */
  @Transactional
  public AnswerResponse answer(AnswerRequest request) {
    Player player = resolvePlayer(request.playerId());
    if (player.isLeft()) {
      throw new PlayerLeftException(player.getPlayerKey());
    }
    Room room = player.getRoom();
    if (room == null || !room.isActive()) {
      player.incrementTotalAnswered();
      return new AnswerResponse(true, 0, "C", player.getTotalAnswered(), 0);
    }
    if (room.getTurnPhase() != TurnPhase.WAITING_ANSWER) {
      throw new TurnBusyException(room.getCode());
    }
    if (room.getTurnPlayerKey() == null
        || !room.getTurnPlayerKey().equals(player.getPlayerKey())) {
      throw new NotYourTurnException(room.getCode());
    }

    RiasecCategory category = RiasecCategory.valueOf(request.category());
    String target =
        request.targetCategory() == null || request.targetCategory().isBlank()
            ? request.category()
            : request.targetCategory();
    RiasecCategory targetCategory = RiasecCategory.valueOf(target);
    int points = request.points() != null ? request.points() : 2;
    if (points > 0) {
      player.scoreOf(targetCategory).addPoints(points);
    }
    int winPoints = request.winPoints() != null ? request.winPoints() : 0;
    if (winPoints > 0) {
      player.addWinPoints(winPoints);
    }
    player.incrementTotalAnswered();

    closeAnswerTurn(room);

    broadcaster.gameUpdated(room.getCode(), snapshotBuilder.build(room));
    return new AnswerResponse(true, points, category.name(), player.getTotalAnswered(), winPoints);
  }

  /** Đóng thẻ và chuyển sang lượt kế tiếp — dùng chung cho trả lời và hết giờ. */
  void closeAnswerTurn(Room room) {
    room.setPendingAnswer(false);
    room.setLastDice(null);
    room.clearTurn();
    room.advanceTurn();
    turns.startNextTurn(room, Instant.now());
  }

  /**
   * Kết thúc ván — chỉ chủ phòng, và người thắng do SERVER tự chấm.
   *
   * <p>Trước đây client gửi kèm {@code winnerKey} và server ghi thẳng vào
   * phòng: bất kỳ ai cũng tự phong mình là người thắng, kể cả người chưa đủ
   * điều kiện. Nay {@code winnerKey} không còn nằm trong request — server tự
   * chấm theo ngưỡng {@link GameRulesConfig#getWinMinLaps()} /
   * {@link GameRulesConfig#getWinMinAnswers()}.
   *
   * <p>Phòng vẫn được giữ lại cho bảng kết quả theo {@code FINISHED_ROOM_TTL_MIN}
   * rồi mới dọn.
   */
  @Transactional
  public GameSnapshot finish(FinishRequest request) {
    Player player = resolvePlayer(request.playerId());
    Room room = player.getRoom();
    if (room == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không ở phòng nào");
    }
    // Kiểm tra quyền TRƯỚC nhánh "đã kết thúc, trả snapshot": đặt sau thì người
    // còn lại trong phòng vẫn gọi được sau khi ván đã xong và nhận snapshot y
    // hệt lúc còn hợp lệ — tức API không còn là chốt chặn quyền nữa.
    if (!player.getPlayerKey().equals(room.effectiveHostKey())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Chỉ chủ phòng mới kết thúc được ván");
    }
    if (room.isFinished()) {
      return snapshotBuilder.build(room);
    }
    room.setStatus(RoomStatus.FINISHED);
    room.setFinishedAt(Instant.now());
    room.setWinnerKey(pickWinner(room));
    room.clearTurn();
    room.setPendingAnswer(false);
    log.info("[FINISH] phong={} ket-thuc-boi={} nguoi-thang={}",
        room.getCode(), player.getName(), room.getWinnerKey());
    GameSnapshot snapshot = snapshotBuilder.build(room);
    broadcaster.gameFinished(room.getCode(), snapshot, room.getWinnerKey());
    return snapshot;
  }

  /**
   * Người thắng = người ĐẦU TIÊN trong phòng đủ cả hai ngưỡng thắng; không ai
   * đủ thì {@code null} (ván kết thúc không có thắng).
   *
   * <p>Thứ tự lấy từ {@link Room#getPlayers()} — {@code @OrderBy("playOrder ASC,
   * joinOrder ASC")} nên đã là thứ tự đi thật (xếp theo xúc xắc, hoà thì người
   * vào phòng trước); không cần sort lại.
   */
  private String pickWinner(Room room) {
    for (Player candidate : room.getPlayers()) {
      if (candidate.getCompletedLaps() >= rules.getWinMinLaps()
          && candidate.getTotalAnswered() >= rules.getWinMinAnswers()) {
        return candidate.getPlayerKey();
      }
    }
    return null;
  }

  @Transactional(readOnly = true)
  public TileResponse tile(int position) {
    if (position < 0 || position >= TurnCoordinator.pathSize()) {
      throw new IllegalArgumentException("Invalid board position");
    }
    TileType type = TurnCoordinator.pathTile(position);
    String question =
        type.isRiasec() ? "Câu hỏi " + type.name() + " - Hoạt động trải nghiệm" : null;
    return new TileResponse(position, type, question);
  }

  @Transactional(readOnly = true)
  public ScoreResponse score(String playerId) {
    Player player = resolvePlayer(playerId);
    Map<RiasecCategory, Integer> byCategory = new EnumMap<>(RiasecCategory.class);
    for (RiasecCategory category : RiasecCategory.values()) {
      byCategory.put(category, player.scoreOf(category).getPoints());
    }
    return new ScoreResponse(
        player.getPlayerKey(), byCategory, player.getTotalAnswered(), player.getCurrentPosition());
  }

  @Transactional(readOnly = true)
  public WinnerResponse checkWinner(WinnerRequest request) {
    Player player = resolvePlayer(request.playerId());
    // Đã đi qua START (≥ 1 vòng) và trả lời đủ câu hỏi RIASEC.
    boolean eligible =
        player.getCompletedLaps() >= 1 && player.getTotalAnswered() >= WIN_ANSWERS;
    return new WinnerResponse(eligible, player.getTotalAnswered(), player.getCurrentPosition());
  }

  public RiasecDescription description(RiasecCategory category) {
    return switch (category) {
      case R -> new RiasecDescription("R", "Realistic", "Thực hành, thao tác và làm việc với công cụ.");
      case I -> new RiasecDescription("I", "Investigative", "Tìm hiểu, phân tích và nghiên cứu.");
      case A -> new RiasecDescription("A", "Artistic", "Sáng tạo, tưởng tượng và thể hiện ý tưởng.");
      case S -> new RiasecDescription("S", "Social", "Kết nối, hỗ trợ và làm việc với con người.");
      case E -> new RiasecDescription("E", "Enterprising", "Lãnh đạo, thuyết phục và tạo ảnh hưởng.");
      case C -> new RiasecDescription("C", "Conventional", "Tổ chức, quản lý và làm việc theo quy trình.");
    };
  }

  /** Ảnh chụp toàn bộ state ván để đẩy qua WebSocket. */
  public GameSnapshot snapshot(Room room) {
    return snapshotBuilder.build(room);
  }

  private Player resolvePlayer(String playerKey) {
    String resolved =
        playerKey == null || playerKey.isBlank() ? "local-player" : playerKey.trim();
    return playerRepository
        .findByPlayerKey(resolved)
        .orElseGet(() -> playerRepository.save(Player.create(resolved, "Player " + resolved)));
  }
}