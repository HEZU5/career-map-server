package vn.careermap.service;

import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.careermap.domain.Player;
import vn.careermap.domain.RiasecCategory;
import vn.careermap.domain.Room;
import vn.careermap.domain.Score;
import vn.careermap.domain.TileType;
import vn.careermap.exception.NotYourTurnException;
import vn.careermap.exception.PlayerLeftException;
import vn.careermap.exception.TurnBusyException;
import vn.careermap.repo.PlayerRepository;
import vn.careermap.web.dto.AnswerRequest;
import vn.careermap.web.dto.AnswerResponse;
import vn.careermap.web.dto.DiceResponse;
import vn.careermap.web.dto.GameSnapshot;
import vn.careermap.web.dto.RiasecDescription;
import vn.careermap.web.dto.RollRequest;
import vn.careermap.web.dto.ScoreResponse;
import vn.careermap.web.dto.TileResponse;
import vn.careermap.web.dto.WinnerRequest;
import vn.careermap.web.dto.WinnerResponse;

@Service
public class GameService {
  private static final int WIN_ANSWERS = 12;

  /** Vòng cờ 53 ô khớp 1:1 với `Board game_new.jpg` (thứ tự đi clockwise theo
   *  HƯỚNG ĐÃ CỐ ĐỊNH -1 của client):
   *  START(0) → 52 → 51 → ... → 43 → 42(góc dưới-phải) → 41 → ... → 34
   *  → 33(góc trên-phải) → 32 → ... → 13 → 12(góc trên-trái) → 11 → ... → 4
   *  → 3(góc dưới-trái) → 2 → 1 → START. Cạnh dưới chạy từ PHẢI SANG TRÁI
   *  đúng mũi tên `<<<<` — quân đi ngược index, không đổi mảng.
   *  24 ô RIASEC (mỗi nhóm 4), 10 CƠ HỘI, 7 THỬ THÁCH (gồm 4 góc),
   *  11 ô ⭐ trang trí, 1 START. Không còn ô FINISH. */
  private static final List<TileType> PATH = List.of(
      // Cạnh dưới + góc dưới-trái (0..3)
      TileType.START, TileType.CHANCE, TileType.R, TileType.CHALLENGE,
      // Cạnh trái (4..11)
      TileType.I, TileType.CHANCE, TileType.A, TileType.STAR,
      TileType.S, TileType.CHANCE, TileType.E, TileType.STAR,
      // Góc trên-trái + cạnh trên (12..32)
      TileType.CHALLENGE, TileType.C, TileType.CHANCE, TileType.R,
      TileType.STAR, TileType.I, TileType.CHALLENGE, TileType.A,
      TileType.STAR, TileType.S, TileType.CHANCE, TileType.E,
      TileType.STAR, TileType.C, TileType.CHALLENGE, TileType.R,
      TileType.STAR, TileType.I, TileType.CHANCE, TileType.A,
      TileType.STAR,
      // Góc trên-phải + cạnh phải (33..41)
      TileType.CHALLENGE, TileType.S, TileType.CHANCE, TileType.E,
      TileType.STAR, TileType.C, TileType.CHANCE, TileType.R,
      TileType.STAR,
      // Góc dưới-phải + cuối cạnh dưới (42..52)
      TileType.CHALLENGE, TileType.I, TileType.CHANCE, TileType.A,
      TileType.STAR, TileType.S, TileType.CHALLENGE, TileType.E,
      TileType.STAR, TileType.C, TileType.CHANCE);

  private final PlayerRepository playerRepository;
  private final GameBroadcaster broadcaster;
  private final GameSnapshotBuilder snapshotBuilder;
  private final SecureRandom dice = new SecureRandom();

  public GameService(
      PlayerRepository playerRepository,
      GameBroadcaster broadcaster,
      GameSnapshotBuilder snapshotBuilder) {
    this.playerRepository = playerRepository;
    this.broadcaster = broadcaster;
    this.snapshotBuilder = snapshotBuilder;
  }

  @Transactional
  public DiceResponse rollDice(RollRequest request) {
    Player player = resolvePlayer(request.playerId());
    if (player.isLeft()) {
      throw new PlayerLeftException(player.getPlayerKey());
    }
    Room room = player.getRoom();
    boolean inActiveRoom = room != null && room.isActive();
    // MỌI lần tung đều theo lượt, kể cả giai đoạn quyết định thứ tự: mỗi
    // người lần lượt tung một xúc xắc nên không còn cảnh nhiều người chen
    // nhau tung cùng lúc (server từ chối người không tới lượt).
    if (inActiveRoom) {
      ensureTurn(room, player);
      // Lượt đang bận: đã tung xong nhưng chưa trả lời/giải quyết ô đích →
      // chặn tung tiếp để không bỏ qua bước bắt buộc của người chơi.
      if (!room.isOrderPhase()) {
        ensureTurnNotBusy(room);
      }
    }

    int value = dice.nextInt(6) + 1;

    // Giai đoạn quyết định thứ tự lượt: tung KHÔNG di chuyển, chỉ ghi điểm
    // của từng người; ai cao nhất sẽ đi trước khi giai đoạn khép lại.
    if (inActiveRoom && room.isOrderPhase()) {
      if (player.isOrderRolled()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Bạn đã tung xúc xắc quyết định thứ tự rồi.");
      }
      room.recordRoll(player.getPlayerKey(), value);
      room.rollOrder(player, value);
      broadcaster.gameUpdated(room.getCode(), snapshotBuilder.build(room));
      return new DiceResponse(value, player.getCurrentPosition());
    }

    // Di chuyển theo số ô theo HƯỚNG CỐ ĐỊNH (client `BoardDefinition.direction`
    // = -1): quân đi NGƯỢC index (START → 52 → 51 → ... → 1), đúng mũi tên
    // `<<<<` cạnh dưới. Đi ngược qua ô 0 (raw < 0) là hoàn thành thêm 1 vòng
    // cờ — vị trí quay về trong [0, 52] và completedLaps tăng tương ứng.
    int pos = player.getCurrentPosition();
    int raw = pos - value;
    int next = Math.floorMod(raw, PATH.size());
    player.setCurrentPosition(next);
    if (raw < 0) {
      player.addCompletedLaps(1);
    }

    if (room != null && room.isActive()) {
      room.recordRoll(player.getPlayerKey(), value);
      boolean needsAnswer = needsAnswerAt(next);
      room.setPendingAnswer(needsAnswer);
      if (!needsAnswer) {
        room.advanceTurn();
      }
      broadcaster.gameUpdated(room.getCode(), snapshotBuilder.build(room));
    }
    return new DiceResponse(value, next);
  }

  @Transactional(readOnly = true)
  public TileResponse tile(int position) {
    if (position < 0 || position >= PATH.size()) {
      throw new IllegalArgumentException("Invalid board position");
    }
    TileType type = PATH.get(position);
    String question = type.isRiasec()
        ? "Câu hỏi " + type.name() + " - Hoạt động trải nghiệm"
        : null;
    return new TileResponse(position, type, question);
  }

  @Transactional
  public AnswerResponse answer(AnswerRequest request) {
    Player player = resolvePlayer(request.playerId());
    if (player.isLeft()) {
      throw new PlayerLeftException(player.getPlayerKey());
    }
    // Nhóm nhận điểm: client gửi targetCategory (ánh xạ đáp án của lá bài),
    // mặc định là nhóm của ô. Điểm Holland: points (1 hoặc 2 theo tài liệu).
    String target = request.targetCategory() == null || request.targetCategory().isBlank()
        ? request.category()
        : request.targetCategory();
    RiasecCategory category = RiasecCategory.valueOf(request.category());
    RiasecCategory targetCategory = RiasecCategory.valueOf(target);
    int points = request.points() != null ? request.points() : 2;
    if (points > 0) {
      Score score = player.scoreOf(targetCategory);
      score.addPoints(points);
    }
    int winPoints = request.winPoints() != null ? request.winPoints() : 0;
    if (winPoints > 0) {
      player.addWinPoints(winPoints);
    }
    player.incrementTotalAnswered();

    Room room = player.getRoom();
    if (room != null && room.isActive()) {
      ensureTurn(room, player);
      room.setPendingAnswer(false);
      room.setLastDice(null);
      room.advanceTurn();
      broadcaster.gameUpdated(room.getCode(), snapshotBuilder.build(room));
    }
    return new AnswerResponse(
        true, points, targetCategory.name(), player.getTotalAnswered(), winPoints);
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

  private boolean needsAnswerAt(int position) {
    TileType type = PATH.get(position);
    // START và ⭐ trang trí: không rút thẻ — server tự đổi lượt ngay.
    return type != TileType.START && type != TileType.STAR;
  }

  private void ensureTurn(Room room, Player player) {
    List<Player> players = room.getPlayers();
    int index = -1;
    for (int i = 0; i < players.size(); i++) {
      if (players.get(i).getPlayerKey().equals(player.getPlayerKey())) {
        index = i;
        break;
      }
    }
    if (index != room.getActivePlayerIndex()) {
      throw new NotYourTurnException(room.getCode());
    }
  }

  /** Chặn tung khi lượt hiện tại đang chờ trả lời ô đích. */
  private void ensureTurnNotBusy(Room room) {
    if (room.isPendingAnswer()) {
      throw new TurnBusyException(room.getCode());
    }
  }

  private Player resolvePlayer(String playerKey) {
    String resolved = playerKey == null || playerKey.isBlank() ? "local-player" : playerKey.trim();
    return playerRepository
        .findByPlayerKey(resolved)
        .orElseGet(() -> playerRepository.save(Player.create(resolved, "Player " + resolved)));
  }
}