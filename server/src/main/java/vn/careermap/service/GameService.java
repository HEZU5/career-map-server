package vn.careermap.service;

import java.time.Instant;
import java.util.ArrayList;
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
import vn.careermap.domain.BonusEffect;
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
import vn.careermap.web.dto.BonusEffectRequest;
import vn.careermap.web.dto.BonusEffectResponse;
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
  private final CardEffectRegistry cardEffects;

  public GameService(
      PlayerRepository playerRepository,
      GameBroadcaster broadcaster,
      GameSnapshotBuilder snapshotBuilder,
      TurnCoordinator turns,
      GameRulesConfig rules,
      CardEffectRegistry cardEffects) {
    this.playerRepository = playerRepository;
    this.broadcaster = broadcaster;
    this.snapshotBuilder = snapshotBuilder;
    this.turns = turns;
    this.rules = rules;
    this.cardEffects = cardEffects;
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
    // Kiểm tra đủ điều kiện SAU khi rollAndMove đã cộng completedLaps — đúng lúc
    // đi qua ô START thì completedLaps vừa tăng lên 1. Kiểm tra TRƯỚC sẽ không
    // bao giờ bắt được "vừa đi qua START".
    //
    // maybeAutoFinish là ĐƯỜNG DÙNG CHUNG với TurnScheduler (server tự tung khi
    // client offline) — xem javadoc của nó để biết vì sao phải gộp.
    GameSnapshot finished = maybeAutoFinish(room, player, roll.moved());
    if (finished != null) {
      // Ván kết thúc NGAY: người vừa đi qua START là người thắng.
      return new DiceResponse(
          roll.value(),
          roll.position(),
          roll.moved(),
          roll.auto(),
          room.getTurnId(),
          true,
          room.getWinnerKey(),
          room.getPendingCardId());
    }
    return new DiceResponse(
        roll.value(),
        roll.position(),
        roll.moved(),
        roll.auto(),
        room.getTurnId(),
        false,
        null,
        room.getPendingCardId());
  }

  /**
   * Tự kết thúc ván khi người chơi vừa đi qua ô START và đủ điều kiện thắng.
   *
   * <p>ĐƯỜNG DÙNG CHUNG cho CẢ HAI cách kết thúc ván:
   *
   * <ul>
   *   <li>{@link #rollDice(RollRequest)} — người chơi bấm tay, client tự tung,
   *       máy trọng tài tự tung.
   *   <li>{@code TurnScheduler.sweepExpiredTurns()} — SERVER tự tung khi đồng
   *       hồ hết hạn (client offline/lag).
   * </ul>
   *
   * <p>Vì sao phải gộp: trước đây đường của server tự tung gọi thẳng
   * {@code TurnCoordinator.expireTurn} nên LUẬT TỰ KẾT THÚC KHÔNG CHẠY —
   * người chơi đi qua ô START đủ điều kiện mà ván vẫn cứ tiếp tục.
   *
   * @param moved quân có THẬT SỰ di chuyển không. Lượt xếp thứ tự đầu ván
   *     {@code moved=false} ⇒ không thể vừa đi qua ô START ⇒ không kết thúc.
   * @return ảnh chụp ván đã kết thúc, hoặc {@code null} nếu ván chưa kết thúc
   */
  @Transactional
  public GameSnapshot maybeAutoFinish(Room room, Player player, boolean moved) {
    if (!moved) {
      return null;
    }
    if (!isEligibleToWin(player)) {
      return null;
    }
    return finishWithWinner(room, player.getPlayerKey());
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
      return new DiceResponse(value, next, true, false, 0, true, player.getPlayerKey(), null);
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
    // Điểm chiến thắng: server TỰ quyết, KHÔNG đọc `winPoints` của client.
    // Trước đây trần điểm lấy từ `questionId` do CLIENT gửi ⇒ client tự
    // chọn được nguồn của trần (khai lá 3 điểm ở bất kỳ ô nào). Đường cũ chỉ
    // còn để tương thích cho tới khi client chuyển sang /game/bonus-effect.
    // Xem {@link #serverWinPoints(Room, Player)}.
    int winPoints = serverWinPoints(room, player);
    if (winPoints > 0) {
      player.addWinPoints(winPoints);
    }
    player.incrementTotalAnswered();

    closeAnswerTurn(room);

    broadcaster.gameUpdated(room.getCode(), snapshotBuilder.build(room));
    return new AnswerResponse(true, points, category.name(), player.getTotalAnswered(), winPoints);
  }

  /**
   * Số Điểm Chiến thắng mà server TỰ quyết cho lượt trả lời.
   *
   * <p>KHÔNG đọc {@code request.winPoints()} — client không còn chỗ để tự
   * quyết số điểm (kể cả "bỏ qua thẻ" gửi 0: Task 4 sẽ bỏ nút đó).
   *
   * <p>Trước đây trần điểm lấy từ {@code questionId} do CLIENT gửi ⇒ client
   * tự chọn nguồn của trần: khai lá 3 điểm ở bất kỳ ô nào, kể cả ô RIASEC
   * vốn theo luật là 0 điểm. Nay:
   *
   * <ul>
   *   <li>Lượt rút thẻ ⇒ điểm của LÁ SERVER ĐÃ RÚT ({@code
   *       Room#getPendingCardId()}), không phải lá client khai. Lá giả của ô ⭐
   *       ({@code SAO}) cũng đi qua đường này ⇒ 1 điểm.
   *   <li>Đứng trên ô ⭐ ⇒ 1 điểm theo luật bàn.
   *   <li>Còn lại (câu hỏi RIASEC) ⇒ 0.
   * </ul>
   */
  private int serverWinPoints(Room room, Player player) {
    String pendingCardId = room.getPendingCardId();
    if (pendingCardId != null) {
      CardEffectRegistry.CardEffectSpec spec = cardEffects.effectOf(pendingCardId);
      if (spec != null) {
        return (int) Math.min(spec.awardWinPoints(), Integer.MAX_VALUE);
      }
    }
    if (TurnCoordinator.pathTile(player.getCurrentPosition()) == TileType.STAR) {
      return 1;
    }
    return 0;
  }

  /** Đóng thẻ và chuyển sang lượt kế tiếp — dùng chung cho trả lời và hết giờ. */
  void closeAnswerTurn(Room room) {
    room.setPendingAnswer(false);
    room.setLastDice(null);
    room.setPendingCardId(null);
    room.clearTurn();
    room.advanceTurn();
    turns.startNextTurn(room, Instant.now());
  }

  // ── Hiệu ứng lá Cơ hội / Thử thách ───────────────────────────────────────

  /**
   * Áp hiệu ứng của lá Cơ hội / Thử thách — SERVER là nguồn sự thật.
   *
   * <p><b>CHẶN Ở CỬA (không dùng điều kiện).</b> Trước đây các trạng thái
   * "không ở phòng đang chơi" chỉ bị BỎ QUA ⇒ người chơi gọi endpoint này
   * liên tục ở ván một người / phòng đã kết thúc là cộng điểm vô hạn. Nay:
   *
   * <ul>
   *   <li>Không có phòng / phòng chưa bắt đầu / phòng đã kết thúc ⇒ <b>403</b>.
   *   <li>Không phải lượt của mình, hoặc lượt không ở pha chờ trả lời ⇒
   *       <b>409</b> (tái dùng {@link NotYourTurnException} / {@link
   *       TurnBusyException} y hệt {@link #answer(AnswerRequest)}).
   * </ul>
   *
   * <p><b>LÁ DO SERVER RÚT.</b> Trước đây client tự rút lá ở máy rồi tự khai
   * {@code cardId} lên ⇒ người chơi tự chọn lá đẹp nhất (MOVE_3, EXTRA_ROLL
   * miễn phí). Nay server tự rút lá khi người chơi rơi vào ô thẻ và lưu mã lá
   * vào phòng ({@link Room#getPendingCardId()}). Client gửi {@code cardId} để
   * đối chiếu thì LỆCH ⇒ 400; không gửi ⇒ server dùng lá đã cấp.
   *
   * <p><b>CỜ {@code skipped}.</b> Client chỉ BÁO CÁO ("không hoàn thành"), server
   * QUYẾT ĐỊNH: không áp hiệu ứng, không cộng điểm, nhưng VẪN đóng lượt để ván
   * không kẹt — xem {@link #applySkipped(Room, Player)}.
   *
   * <p>Lá lạ → 400. Lựa chọn không hợp lệ với lá đó → 400. Người được chọn
   * không ở trong phòng (hoặc đã rời phòng) → 403.
   */
  @Transactional
  public BonusEffectResponse applyBonusEffect(BonusEffectRequest request) {
    Player player = requirePlayer(request.playerId());
    Room room = requirePlayableRoom(player);
    requireAnswerTurn(room, player);
    if (isSkipped(request)) {
      return applySkipped(room, player);
    }
    String cardId = resolveDrawnCardId(room, request.cardId());
    CardEffectRegistry.CardEffectSpec spec = cardEffects.effectOf(cardId);
    if (spec == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lá bài không hợp lệ: " + cardId);
    }
    String choice = normalize(request.choice());
    requireValidChoice(spec, choice);

    List<String> awarded = new ArrayList<>();
    long winPoints = 0;
    Integer from = null;
    Integer to = null;
    String message;

    // Rút lá = đã trả lời 1 câu ở ô đích: đếm vào `totalAnswered` như đường cũ
    // (`/game/answer`) để ngưỡng thắng (số câu đã trả lời) không bị nặng hơn.
    // LÁ DI CHUYỂN thì KHÔNG cộng: người chơi không trả lời câu nào, chỉ đi
    // thêm vài ô ⇒ ngưỡng thắng phải đếm "câu đã trả lời", không phải "lá đã
    // rút".
    if (!isMoveEffect(spec, choice)) {
      player.incrementTotalAnswered();
    }

    switch (spec.effect()) {
      case NONE -> {
        winPoints = award(player, spec.winPoints());
        awarded.add(player.getPlayerKey());
        closeTurn(room);
        message = "Bạn nhận +" + winPoints + " Điểm Chiến thắng.";
      }
      case EXTRA_ROLL -> {
        winPoints = award(player, spec.winPoints());
        awarded.add(player.getPlayerKey());
        // Lượt MỚI cho chính người này (được tung tiếp ngay) — không chuyển lượt.
        reopenRollTurn(room, player);
        message = "Bạn nhận +" + winPoints + " Điểm Chiến thắng và được chơi thêm một lượt ngay!";
      }
      case MOVE_2, MOVE_3 -> {
        int steps = spec.effect() == BonusEffect.MOVE_2 ? 2 : 3;
        MoveResult move = moveForward(player, room, steps);
        from = move.from();
        to = move.to();
        message = move.message();
      }
      case PICK_PLAYER_PLUS1 -> {
        Player target = requireTargetInRoom(room, request.targetPlayerKey());
        winPoints = award(player, spec.winPoints());
        awarded.add(player.getPlayerKey());
        if (!target.getPlayerKey().equals(player.getPlayerKey())) {
          award(target, spec.winPoints());
          awarded.add(target.getPlayerKey());
        }
        closeTurn(room);
        message =
            "Bạn và người bạn đồng hành cùng nhận +" + spec.winPoints() + " Điểm Chiến thắng.";
      }
      case ALL_PLAYERS_PLUS1 -> {
        winPoints = award(player, spec.winPoints());
        awarded.add(player.getPlayerKey());
        for (Player other : room.getPlayers()) {
          if (other.isLeft() || other.getPlayerKey().equals(player.getPlayerKey())) {
            continue;
          }
          award(other, spec.winPoints());
          awarded.add(other.getPlayerKey());
        }
        closeTurn(room);
        message = "Tất cả người chơi cùng nhận +" + spec.winPoints() + " Điểm Chiến thắng.";
      }
      case PLUS3_OR_MOVE_2 -> {
        if (CardEffectRegistry.CHOICE_MOVE_2.equals(choice)) {
          MoveResult move = moveForward(player, room, 2);
          from = move.from();
          to = move.to();
          message = move.message();
        } else {
          winPoints = award(player, spec.winPoints());
          awarded.add(player.getPlayerKey());
          closeTurn(room);
          message = "Bạn nhận +" + winPoints + " Điểm Chiến thắng.";
        }
      }
      default ->
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "Hiệu ứng không hợp lệ: " + spec.effect());
    }

    // Đi qua ô START BẰNG HIỆU ỨNG cũng phải được xét thắng — cùng đường với
    // lúc tung xúc xắc (xem {@link #maybeAutoFinish}).
    if (to != null) {
      maybeAutoFinish(room, player, true);
    }
    if (!room.isFinished()) {
      broadcaster.gameUpdated(room.getCode(), snapshotBuilder.build(room));
    }
    return new BonusEffectResponse(
        spec.effect().name(), winPoints, from, to, List.copyOf(awarded), message);
  }

  /**
   * Người chơi có BÁO CÁO bỏ qua lá không.
   *
   * <p>Client chỉ BÁO CÁO; server QUYẾT ĐỊNH hậu qua (xem {@link
   * #applySkipped(Room, Player)}). {@code null} = không bỏ qua.
   */
  private boolean isSkipped(BonusEffectRequest request) {
    return Boolean.TRUE.equals(request.skipped());
  }

  /**
   * BỎ QUA lá: không áp hiệu ứng, không cộng điểm — nhưng VẪN đóng lượt.
   *
   * <p>Vì sao phải đóng lượt: nếu giữ nguyên lượt chờ trả lời thì ván KẸT ở ô
   * đó cho tới khi hết giờ — mọi người khác phải đợi. Bỏ qua = người chơi
   * chịu mất điểm của lá đó để ván đi tiếp ngay.
   *
   * <p>Ràng buộc: chỉ có nghĩa khi lượt RÚT THẺ hợp lệ ({@code pendingCardId}
   * khác rỗng). Lượt ở ô RIASEC (không có lá) ⇒ 409, y hệt đường áp hiệu ứng.
   */
  private BonusEffectResponse applySkipped(Room room, Player player) {
    String drawn = room.getPendingCardId();
    if (drawn == null || drawn.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Lượt này không phải lượt rút thẻ nên không có lá để bỏ qua.");
    }
    // KHÔNG áp hiệu ứng, KHÔNG cộng điểm, KHÔNG tính vào số câu đã trả lời —
    // nhưng lượt đóng và sang người kế tiếp.
    closeTurn(room);
    broadcaster.gameUpdated(room.getCode(), snapshotBuilder.build(room));
    return new BonusEffectResponse(
        BonusEffectResponse.EFFECT_SKIPPED,
        0,
        null,
        null,
        List.of(),
        "Bạn đã bỏ qua lá bài này.");
  }

  /**
   * Phải có phòng ĐANG CHƠI thì mới được rút lá.
   *
   * <p>Chặn ở cửa, không dùng điều kiện: nếu chỉ "bỏ qua" các trạng thái
   * này thì endpoint vẫn cộng điểm cho người chơi đang không ở ván.
   */
  private Room requirePlayableRoom(Player player) {
    Room room = player.getRoom();
    if (room == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có phòng đang chơi.");
    }
    if (!room.isActive()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ván chưa bắt đầu.");
    }
    if (room.isFinished()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ván đã kết thúc.");
    }
    return room;
  }

  /**
   * Lá để áp hiệu ứng = LÁ SERVER ĐÃ RÚT cho lượt này.
   *
   * <p>Client có thể gửi {@code cardId} để đối chiếu — lệch với lá đã rút thì
   * 400 (đây là lỗ hổng "client tự chọn lá đẹp nhất" của bản cũ). Không gửi
   * thì server dùng lá đã cấp.
   *
   * <p>Lượt không phải lượt rút thẻ (ô RIASEC / ô ⭐) ⇒ không có lá để áp ⇒
   * 409.
   */
  private String resolveDrawnCardId(Room room, String requestedCardId) {
    String drawn = room.getPendingCardId();
    if (drawn == null || drawn.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Lượt này không phải lượt rút thẻ nên không có lá để áp.");
    }
    String requested = normalize(requestedCardId);
    if (requested != null && !requested.equals(drawn)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Lá bài không khớp với lá đã được cấp cho lượt này.");
    }
    return drawn;
  }

  /** Lá này có làm người chơi DỜI QUÂN không (MOVE_2 / MOVE_3 / CƠ HỘI 10 chọn MOVE_2)? */
  private boolean isMoveEffect(CardEffectRegistry.CardEffectSpec spec, String choice) {
    return switch (spec.effect()) {
      case MOVE_2, MOVE_3 -> true;
      case PLUS3_OR_MOVE_2 -> CardEffectRegistry.CHOICE_MOVE_2.equals(choice);
      default -> false;
    };
  }

  /**
   * Người chơi theo key — KHÔNG tự tạo dòng {@code players} mới.
   *
   * <p>Endpoint này công khai: nếu tạo dòng mới cho key lạ thì bảng
   * {@code players} phình vô hạn (Player không ở phòng nào thì không janitor
   * nào dọn) — cùng lý do {@link #checkWinner(WinnerRequest)} đã sửa.
   */
  private Player requirePlayer(String playerKey) {
    String key = playerKey == null ? "" : playerKey.trim();
    if (key.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu mã người chơi.");
    }
    Player player =
        playerRepository
            .findByPlayerKey(key)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Không tìm thấy người chơi " + key + "."));
    if (player.isLeft()) {
      throw new PlayerLeftException(player.getPlayerKey());
    }
    return player;
  }

  /**
   * Lựa chọn của người chơi phải nằm trong {@code allowedChoices} của lá.
   *
   * <p>Lá không cần chọn thì client KHÔNG được gửi {@code choice} (gửi bừa là
   * sai luật ⇒ 400).
   */
  private void requireValidChoice(CardEffectRegistry.CardEffectSpec spec, String choice) {
    if (spec.requiresChoice()) {
      if (choice == null || !spec.allowedChoices().contains(choice)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Lá bài này cần chọn: " + String.join(" hoặc ", spec.allowedChoices()) + ".");
      }
      return;
    }
    if (choice != null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Lá bài này không có lựa chọn để chọn.");
    }
  }

  /** Chỉ người đang ở lượt trả lời mới được áp hiệu ứng lá bài. */
  private void requireAnswerTurn(Room room, Player player) {
    if (room.getTurnPhase() != TurnPhase.WAITING_ANSWER) {
      throw new TurnBusyException(room.getCode());
    }
    if (room.getTurnPlayerKey() == null
        || !room.getTurnPlayerKey().equals(player.getPlayerKey())) {
      throw new NotYourTurnException(room.getCode());
    }
  }

  /** Đóng lượt trả lời và chuyển lượt cho người kế tiếp. */
  private void closeTurn(Room room) {
    closeAnswerTurn(room);
  }

  /**
   * Mở lượt tung MỚI cho chính người vừa rút lá (lá "được chơi thêm 1 lượt").
   *
   * <p>Không chuyển lượt: người đó được tung tiếp ngay, còn những người khác
   * chờ.
   */
  private void reopenRollTurn(Room room, Player player) {
    room.setPendingAnswer(false);
    room.setLastDice(null);
    room.setPendingCardId(null);
    room.clearTurn();
    room.beginTurn(
        player, TurnPhase.WAITING_ROLL, Instant.now().plusSeconds(rules.turnRollS), false);
  }

  /**
   * Người được chọn cho lá "chọn 1 người chơi" — phải còn ở trong phòng.
   *
   * <p>Người đã rời phòng vẫn nằm trong {@code room.getPlayers()} nên phải loại
   * riêng, nếu không ai cũng "chia" điểm cho người đã bỏ đi.
   */
  private Player requireTargetInRoom(Room room, String targetKey) {
    String key = targetKey == null ? "" : targetKey.trim();
    if (key.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Chưa chọn người chơi cho lá bài này.");
    }
    Player target = room.findPlayer(key);
    if (target == null || target.isLeft()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Người chơi được chọn không ở trong phòng.");
    }
    return target;
  }

  /**
   * Tiến quân N ô theo CHIỀU ĐI của bàn ({@code position - N}, {@code
   * Math.floorMod}) — y hệt {@link TurnCoordinator#rollAndMove} nên không lệch
   * luật.
   *
   * <p>Vượt qua ô START thì cộng 1 vòng: nếu không, người chơi đi qua START bằng
   * hiệu ứng sẽ không bao giờ đủ điều kiện thắng.
   *
   * <p>Đích là ô thẻ ⇒ mở lượt trả lời cho chính người này; không phải ⇒ đóng
   * lượt và chuyển lượt.
   */
  private MoveResult moveForward(Player player, Room room, int steps) {
    int from = player.getCurrentPosition();
    int raw = from - steps;
    int to = Math.floorMod(raw, TurnCoordinator.pathSize());
    player.setCurrentPosition(to);
    if (raw < 0) {
      player.addCompletedLaps(1);
    }
    boolean needsAnswer = turns.needsAnswerAt(to);
    if (needsAnswer) {
      room.setPendingAnswer(true);
      // Rơi vào ô THẻ ⇒ server rút lá MỚI cho lượt này (xem
      // {@link TurnCoordinator#rollAndMove} — cùng quy tắc).
      room.setPendingCardId(cardEffects.drawCardFor(TurnCoordinator.pathTile(to)));
      room.beginTurn(
          player,
          TurnPhase.WAITING_ANSWER,
          Instant.now().plusSeconds(rules.answerTimeoutS),
          false);
    } else {
      closeAnswerTurn(room);
    }
    String message =
        needsAnswer
            ? "Bạn tiến thêm " + steps + " ô và được rút thẻ mới."
            : "Bạn tiến thêm " + steps + " ô.";
    return new MoveResult(from, to, message);
  }

  /** Cộng Điểm Chiến thắng — server tự tính, KHÔNG nhận số điểm từ client. */
  private long award(Player player, long points) {
    if (points <= 0) {
      return 0;
    }
    player.addWinPoints((int) Math.min(points, Integer.MAX_VALUE));
    return points;
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** Kết quả dời quân: vị trí trước, vị trí sau và câu thông báo. */
  private record MoveResult(int from, int to, String message) {}

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
   *
   * <p>IDEMPOTENT: nếu ván đã kết thúc rồi thì trả về ảnh chụp hiện tại và
   * KHÔNG làm gì thêm. Không có chốt này thì chủ phòng bấm "Kết thúc" đúng
   * lúc người chơi đi qua ô START ⇒ hai lần ghi trạng thái ⇒ HAI sự kiện
   * {@code FINISHED} và HAI lần cộng điểm thưởng.
   */
  private GameSnapshot finishWithWinner(Room room, String winnerKey) {
    if (room.isFinished()) {
      return snapshotBuilder.build(room);
    }
    room.setStatus(RoomStatus.FINISHED);
    room.setFinishedAt(Instant.now());
    room.setWinnerKey(winnerKey);
    // Điểm thưởng cho NGƯỜI THẮNG — nằm ở TRONG hàm này nên CẢ HAI đường
    // kết thúc (tự kết thúc khi đi qua START, và chủ phòng bấm nút) đều
    // thưởng như nhau: không phụ thuộc cách ván kết thúc.
    awardWinBonus(room, winnerKey);
    // Chụp điểm RIASEC ngay lúc này: sau khi đã chốt người thắng, trước khi
    // broadcast. 30 phút sau RoomJanitor xoá phòng (xoá Room → cascade Player →
    // cascade Score) nên điểm gốc biến mất; đơn thanh toán 50.000đ được tạo
    // sau đó, đọc bảng scores lúc đó chỉ ra số 0.
    room.captureRiasecSnapshot();
    room.clearTurn();
    room.setPendingAnswer(false);
    room.setPendingCardId(null);
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
   * Cộng điểm chiến thắng cho người thắng của ván (tra theo key trong phòng).
   *
   * <p>Gọi từ {@link #finishWithWinner(Room, String)} ⇒ điểm thưởng áp cho cả
   * hai đường kết thúc. Không có người thắng (ván kết thúc không ai đủ ngưỡng)
   * thì không ai được thưởng.
   */
  private void awardWinBonus(Room room, String winnerKey) {
    if (winnerKey == null) {
      return;
    }
    Player winner = room.findPlayer(winnerKey);
    if (winner == null) {
      return;
    }
    awardWinBonus(winner);
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