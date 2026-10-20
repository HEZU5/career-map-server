package vn.careermap.service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    // Kiểm tra đủ điều kiện SAU khi rollAndMove đã cộng completedLaps — đúng lúc
    // đi qua ô START thì completedLaps vừa tăng lên 1. Kiểm tra TRƯỚC sẽ không
    // bao giờ bắt được "vừa đi qua START".
    boolean duDieuKien = isEligibleToWin(player);
    if (duDieuKien) {
      awardWinBonus(player);
    }
    broadcaster.gameUpdated(room.getCode(), snapshotBuilder.build(room));
    if (duDieuKien) {
      // Ván kết thúc NGAY: người vừa đi qua START là người thắng.
      String winnerKey = player.getPlayerKey();
      finishWithWinner(room, winnerKey);
      return new DiceResponse(
          roll.value(),
          roll.position(),
          roll.moved(),
          roll.auto(),
          room.getTurnId(),
          true,
          winnerKey);
    }
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
    // Không có phòng để broadcast ⇒ trạng thái kết thúc truyền qua response.
    if (isEligibleToWin(player)) {
      awardWinBonus(player);
      return new DiceResponse(value, next, true, false, 0, true, player.getPlayerKey());
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
    log.info("[FINISH] phong={} ket-thuc-boi={}", room.getCode(), player.getName());
    return finishWithWinner(room, pickWinner(room));
  }

  /**
   * Chốt kết thúc ván với người thắng đã chọn.
   *
   * <p>Dùng chung cho HAI đường kết thúc:
   *
   * <ul>
   *   <li>Chủ phòng bấm nút kết thúc ⇒ người thắng do {@link #pickWinner(Room)}
   *       chấm.
   *   <li>Ván tự kết thúc khi người chơi đủ điều kiện đi qua ô START ⇒ người
   *       thắng là người vừa đi qua START.
   * </ul>
   *
   * <p>Thứ tự thao tác giữ nguyên như {@code finish()} cũ: chốt trạng thái →
   * chụp điểm RIASEC (trước khi {@code RoomJanitor} dọn phòng) → đóng lượt →
   * bắn sự kiện {@code FINISHED} cho mọi client trong phòng.
   */
  private GameSnapshot finishWithWinner(Room room, String winnerKey) {
    room.setStatus(RoomStatus.FINISHED);
    room.setFinishedAt(Instant.now());
    room.setWinnerKey(winnerKey);
    // Chụp điểm RIASEC ngay lúc này: sau khi đã chốt người thắng, trước khi
    // broadcast. 30 phút sau RoomJanitor xoá phòng (xoá Room → cascade Player →
    // cascade Score) nên điểm gốc biến mất; đơn thanh toán 50.000đ được tạo
    // sau đó, đọc bảng scores lúc đó chỉ ra số 0.
    room.captureRiasecSnapshot();
    room.clearTurn();
    room.setPendingAnswer(false);
    log.info("[FINISH] phong={} nguoi-thang={}", room.getCode(), room.getWinnerKey());
    GameSnapshot snapshot = snapshotBuilder.build(room);
    broadcaster.gameFinished(room.getCode(), snapshot, room.getWinnerKey());
    return snapshot;
  }

  /**
   * Cộng điểm chiến thắng cho người vừa đủ điều kiện.
   *
   * <p>Điểm thưởng đọc từ {@link GameRulesConfig#getWinBonusPoints()} (biến môi
   * trường {@code WIN_BONUS_POINTS}) — SERVER tự cộng, không nhận điểm từ client.
   */
  private void awardWinBonus(Player player) {
    long bonus = rules.getWinBonusPoints();
    if (bonus <= 0) {
      return;
    }
    player.addWinPoints((int) Math.min(bonus, Integer.MAX_VALUE));
  }

  /**
   * Người thắng = người ĐẦU TIÊN trong phòng đủ điều kiện; không ai đủ thì
   * {@code null} (ván kết thúc không có thắng).
   *
   * <p>Thứ tự lấy từ {@link Room#getPlayers()} — {@code @OrderBy("playOrder ASC,
   * joinOrder ASC")} nên đã là thứ tự đi thật (xếp theo xúc xắc, hoà thì người
   * vào phòng trước); không cần sort lại.
   *
   * <p>Người đã rời phòng bị loại: {@code RoomService.leave} chỉ set
   * {@code left = true} mà KHÔNG gỡ khỏi {@code room.getPlayers()}, nên họ vẫn
   * nằm trong danh sách và vẫn giữ nguyên số vòng/số câu đã đạt. Nếu không loại,
   * người đã đóng app lúc giữa ván sẽ ôm quyền mở mã QR thanh toán trong khi
   * người còn lại dù đủ điều kiện cũng không thấy gì.
   */
  private String pickWinner(Room room) {
    for (Player candidate : room.getPlayers()) {
      if (isEligibleToWin(candidate)) {
        return candidate.getPlayerKey();
      }
    }
    return null;
  }

  /**
   * Đủ điều kiện thắng chưa — MỘT nguồn sự thật duy nhất, dùng chung cho
   * {@link #pickWinner(Room)} và {@link #checkWinner(WinnerRequest)}.
   *
   * <p>Ngưỡng đọc từ {@link GameRulesConfig} ({@code game.win-min-laps} /
   * {@code game.win-min-answers}, override được bằng biến môi trường lúc
   * deploy). Trước đây {@code checkWinner} dùng hằng hardcode {@code 1} vòng /
   * {@code 12} câu nên hai đường trả lời ngược nhau khi ai đó đổi ngưỡng.
   */
  private boolean isEligibleToWin(Player player) {
    return !player.isLeft()
        && player.getCompletedLaps() >= rules.getWinMinLaps()
        && player.getTotalAnswered() >= rules.getWinMinAnswers();
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

  /**
   * Hỏi phía client "người này đủ điều kiện kết thúc chưa".
   *
   * <p>Dùng CHUNG {@link #isEligibleToWin(Player)} với {@link #pickWinner(Room)}
   * để hai đường không thể trả lời khác nhau: đổi {@code game.win-min-*} thì cả
   * hai cùng đổi. Trước đây hàm này hardcode {@code 1} vòng / {@code 12} câu
   * trong khi {@code finish()} đọc ngưỡng cấu hình.
   *
   * <p>KHÔNG tạo dòng {@code players} cho key lạ: endpoint này công khai, ai
   * cũng gọi được với bất kỳ chuỗi nào. Trước đây {@code resolvePlayer} tạo
   * Player mới ⇒ bảng {@code players} phình vô hạn (Player không ở phòng nào
   * thì không janitor nào dọn). Nay key không tồn tại ⇒ trả
   * {@code eligibleToFinish = false}.
   */
  @Transactional(readOnly = true)
  public WinnerResponse checkWinner(WinnerRequest request) {
    String key = request.playerId() == null ? "" : request.playerId().trim();
    Optional<Player> found = key.isEmpty() ? Optional.empty() : playerRepository.findByPlayerKey(key);
    if (found.isEmpty()) {
      return new WinnerResponse(false, 0, 0);
    }
    Player player = found.get();
    return new WinnerResponse(
        isEligibleToWin(player), player.getTotalAnswered(), player.getCurrentPosition());
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