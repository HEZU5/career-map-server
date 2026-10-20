package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.domain.RoomStatus;
import vn.careermap.domain.TurnPhase;
import vn.careermap.repo.PlayerRepository;
import vn.careermap.web.dto.DiceResponse;
import vn.careermap.web.dto.FinishRequest;
import vn.careermap.web.dto.GameSnapshot;
import vn.careermap.web.dto.RollRequest;

/**
 * Ván TỰ kết thúc khi người chơi đủ điều kiện mà đi qua ô START.
 *
 * <p>Luật: người chơi đã trả lời đủ {@code winMinAnswers} câu và vừa đi qua ô
 * START (hoàn thành 1 vòng) ⇒ ván kết thúc NGAY, người đó là người thắng và
 * được cộng {@code winBonusPoints} điểm chiến thắng.
 *
 * <p>Điều kiện đủ điều kiện là MỘT nguồn sự thật duy nhất
 * ({@code GameService.isEligibleToWin}) — không đổi ở task này.
 *
 * <p>Thuần JUnit + Mockito, không Spring context, không database.
 */
class GameAutoFinishTest {
  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, false, 5L);

  /** Thưởng khác mặc định — chứng minh server đọc điểm thưởng từ cấu hình. */
  private static final GameRulesConfig BONUS_7 =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, false, 7L);

  /** key -> player, đóng vai "database" cho {@code resolvePlayer}. */
  private final Map<String, Player> byKey = new HashMap<>();

  private GameBroadcaster broadcaster;
  private TurnCoordinator turns;

  private GameService service(GameRulesConfig rules) {
    PlayerRepository players = mock(PlayerRepository.class);
    when(players.findByPlayerKey(anyString()))
        .thenAnswer(call -> Optional.ofNullable(byKey.get(call.<String>getArgument(0))));
    when(players.save(any(Player.class))).thenAnswer(call -> call.getArgument(0));
    broadcaster = mock(GameBroadcaster.class);
    turns = mock(TurnCoordinator.class);
    return new GameService(players, broadcaster, new GameSnapshotBuilder(rules), turns, rules);
  }

  private Room room(String ownerKey, Player... members) {
    Room room = Room.create("H12345", "Chủ phòng", false, ownerKey);
    room.setActive(true);
    room.setStatus(RoomStatus.PLAYING);
    for (Player p : members) {
      room.addPlayer(p);
      byKey.put(p.getPlayerKey(), p);
    }
    return room;
  }

  private Player player(String key, int laps, int answers) {
    Player p = Player.create(key, key);
    p.addCompletedLaps(laps);
    for (int i = 0; i < answers; i++) {
      p.incrementTotalAnswered();
    }
    return p;
  }

  /**
   * Tung qua ô START — mô phỏng {@code TurnCoordinator.rollAndMove}: cộng
   * {@code completedLaps} TRƯỚC khi trả về cho {@code GameService}.
   *
   * <p>Đây là thứ tự then chốt: nếu {@code GameService} kiểm tra đủ điều kiện
   * TRƯỚC lần tung thì {@code completedLaps >= 1} chưa bao giờ đạt đúng lúc đi
   * qua START ⇒ ván không bao giờ tự kết thúc.
   */
  private void stubRollQuaStart() {
    when(turns.rollForTurn(any(), any(), anyLong(), anyBoolean(), anyString()))
        .thenAnswer(
            call -> {
              Player roller = call.getArgument(1);
              roller.setCurrentPosition(0);
              roller.addCompletedLaps(1);
              return new TurnCoordinator.TurnRoll(3, 0, true, false, "manual");
            });
  }

  /** Mở lượt cho {@code roller} như một ván đang chơi thật. */
  private void moLuot(Room room, Player roller) {
    room.beginTurn(roller, TurnPhase.WAITING_ROLL, Instant.now().plusSeconds(15), false);
  }

  // ── Ván trong phòng ───────────────────────────────────────────────────────

  @Test
  void nguoiDuDieuKienQuaStartThiVanKetThuc() {
    Player p = player("owner", 0, 12);
    Room room = room("owner", p);
    moLuot(room, p);
    GameService gameService = service(RULES);
    stubRollQuaStart();

    DiceResponse res =
        gameService.rollDice(new RollRequest("owner", room.getTurnId(), false, "manual"));

    assertThat(room.getStatus()).isEqualTo(RoomStatus.FINISHED);
    assertThat(room.getWinnerKey()).isEqualTo("owner");
    assertThat(room.getFinishedAt()).isNotNull();
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.NONE);
    assertThat(room.isPendingAnswer()).isFalse();
    assertThat(res.gameFinished()).isTrue();
    assertThat(res.winnerKey()).isEqualTo("owner");
    verify(broadcaster).gameFinished(eq("H12345"), any(GameSnapshot.class), eq("owner"));
  }

  @Test
  void nguoiChuaDuDieuKienQuaStartThiVanChuaKetThuc() {
    // Thiếu 1 câu nữa mới đủ ngưỡng ⇒ ván phải chạy tiếp.
    Player p = player("owner", 0, 11);
    Room room = room("owner", p);
    moLuot(room, p);
    GameService gameService = service(RULES);
    stubRollQuaStart();

    DiceResponse res =
        gameService.rollDice(new RollRequest("owner", room.getTurnId(), false, "manual"));

    assertThat(room.getStatus()).isEqualTo(RoomStatus.PLAYING);
    assertThat(room.getWinnerKey()).isNull();
    assertThat(room.getFinishedAt()).isNull();
    assertThat(res.gameFinished()).isFalse();
    assertThat(res.winnerKey()).isNull();
    verify(broadcaster, never()).gameFinished(anyString(), any(), anyString());
  }

  @Test
  void nguoiChuaQuaStartThiVanChuaKetThuc() {
    // Đủ 12 câu nhưng CHƯA đi qua ô START (0 vòng) ⇒ chưa đủ điều kiện thắng.
    Player p = player("owner", 0, 12);
    Room room = room("owner", p);
    moLuot(room, p);
    GameService gameService = service(RULES);
    when(turns.rollForTurn(any(), any(), anyLong(), anyBoolean(), anyString()))
        .thenReturn(new TurnCoordinator.TurnRoll(3, 30, true, false, "manual"));

    DiceResponse res =
        gameService.rollDice(new RollRequest("owner", room.getTurnId(), false, "manual"));

    assertThat(room.getStatus()).isEqualTo(RoomStatus.PLAYING);
    assertThat(res.gameFinished()).isFalse();
  }

  @Test
  void nguoiThangNhanThemDiemChienThang() {
    Player p = player("owner", 0, 12);
    Room room = room("owner", p);
    moLuot(room, p);
    GameService gameService = service(RULES);
    stubRollQuaStart();

    gameService.rollDice(new RollRequest("owner", room.getTurnId(), false, "manual"));

    assertThat(p.getWinPoints()).isEqualTo(5);
    assertThat((long) p.getWinPoints()).isEqualTo(RULES.getWinBonusPoints());
  }

  @Test
  void diemChienThangDocTuCauHinhKhongHardcode() {
    Player p = player("owner", 0, 12);
    Room room = room("owner", p);
    moLuot(room, p);
    GameService gameService = service(BONUS_7);
    stubRollQuaStart();

    gameService.rollDice(new RollRequest("owner", room.getTurnId(), false, "manual"));

    assertThat(p.getWinPoints()).isEqualTo(7);
  }

  @Test
  void nguoiVuaTungLaNguoiThang() {
    // Người khác đủ điều kiện từ trước: người VỪA đi qua START thắng, vì ván
    // kết thúc ngay tại thời điểm đó.
    Player khac = player("guest-1", 1, 12);
    Player roller = player("owner", 0, 12);
    Room room = room("owner", khac, roller);
    moLuot(room, roller);
    GameService gameService = service(RULES);
    stubRollQuaStart();

    gameService.rollDice(new RollRequest("owner", room.getTurnId(), false, "manual"));

    assertThat(room.getWinnerKey()).isEqualTo("owner");
    assertThat(roller.getWinPoints()).isEqualTo(5);
    assertThat(khac.getWinPoints()).isEqualTo(0);
  }

  // ── Ván một người (không có phòng) ───────────────────────────────────────

  @Test
  void vanSoloCungKetThucKhiDuDieuKien() {
    // Không có phòng ⇒ không có chỗ broadcast ⇒ trạng thái kết thúc phải đi
    // kèm response.
    Player p = player("solo", 0, 12);
    p.setCurrentPosition(2); // đứng sát ô START: 2 - 3 = -1 ⇒ đi qua START
    byKey.put("solo", p);
    GameService gameService = service(RULES);
    when(turns.rollValue()).thenReturn(3);

    DiceResponse res = gameService.rollDice(new RollRequest("solo", null, false, "manual"));

    assertThat(p.getCompletedLaps()).isEqualTo(1);
    assertThat(res.gameFinished()).isTrue();
    assertThat(res.winnerKey()).isEqualTo("solo");
    assertThat(p.getWinPoints()).isEqualTo(5);
    verify(broadcaster, never()).gameFinished(anyString(), any(), anyString());
  }

  @Test
  void vanSoloKhongDuDieuKienThiKhongKetThuc() {
    Player p = player("solo", 0, 11);
    p.setCurrentPosition(2);
    byKey.put("solo", p);
    GameService gameService = service(RULES);
    when(turns.rollValue()).thenReturn(3);

    DiceResponse res = gameService.rollDice(new RollRequest("solo", null, false, "manual"));

    assertThat(p.getCompletedLaps()).isEqualTo(1);
    assertThat(res.gameFinished()).isFalse();
    assertThat(res.winnerKey()).isNull();
    assertThat(p.getWinPoints()).isEqualTo(0);
  }

  // ── Kết thúc idempotent: hai đường kết thúc không được trùng nhau ────────

  /**
   * Gọi chốt kết thúc HAI lần ⇒ chỉ kết thúc MỘT lần, chỉ cộng điểm MỘT lần.
   *
   * <p>Đây là chốt chặn race: chủ phòng bấm "Kết thúc" đúng lúc người chơi đi
   * qua ô START ⇒ hai lần ghi trạng thái ⇒ hai sự kiện FINISHED và hai lần cộng
   * điểm thưởng.
   */
  @Test
  void goiFinishHaiLanChiKetThucMotLanVaCongDiemMotLan() {
    Player p = player("owner", 1, 12);
    Room room = room("owner", p);
    GameService gameService = service(RULES);

    gameService.maybeAutoFinish(room, p, true);
    gameService.maybeAutoFinish(room, p, true);

    assertThat(room.getStatus()).isEqualTo(RoomStatus.FINISHED);
    assertThat(room.getWinnerKey()).isEqualTo("owner");
    assertThat(p.getWinPoints()).isEqualTo(5);
    verify(broadcaster, times(1)).gameFinished(eq("H12345"), any(GameSnapshot.class), eq("owner"));
  }

  @Test
  void chuPhongBamKetThucTrungLucVanhTuKetThucChiKetThucMotLan() {
    Player p = player("owner", 0, 12);
    Room room = room("owner", p);
    moLuot(room, p);
    GameService gameService = service(RULES);
    stubRollQuaStart();

    // Ván tự kết thúc (đi qua ô START)…
    gameService.rollDice(new RollRequest("owner", room.getTurnId(), false, "manual"));
    // …rồi chủ phòng bấm "Kết thúc" đúng khoảnh khắc đó.
    gameService.finish(new FinishRequest("owner"));

    assertThat(room.getStatus()).isEqualTo(RoomStatus.FINISHED);
    assertThat(room.getWinnerKey()).isEqualTo("owner");
    assertThat(p.getWinPoints()).isEqualTo(5);
    verify(broadcaster, times(1)).gameFinished(anyString(), any(), anyString());
  }

  // ── Điểm thưởng: cả hai đường kết thúc đều thưởng như nhau ─────────────

  @Test
  void chuPhongKetThucCungThuongDiemChoNguoiThang() {
    // Chủ phòng bấm "Kết thúc" ⇒ người đủ ngưỡng vẫn được thưởng, không phụ
    // thuộc cách ván kết thúc.
    Player nguoiThang = player("guest-1", 1, 12);
    Room room = room("owner", nguoiThang, player("owner", 0, 0));
    GameService gameService = service(RULES);

    gameService.finish(new FinishRequest("owner"));

    assertThat(room.getWinnerKey()).isEqualTo("guest-1");
    assertThat(nguoiThang.getWinPoints()).isEqualTo(5);
    verify(broadcaster).gameFinished(eq("H12345"), any(GameSnapshot.class), eq("guest-1"));
  }

  @Test
  void vanKetThucKhongCoNguoiThangThiKhongAiDuocThuong() {
    Player p = player("owner", 0, 11); // không ai đủ ngưỡng
    Room room = room("owner", p);
    GameService gameService = service(RULES);

    gameService.finish(new FinishRequest("owner"));

    assertThat(room.getStatus()).isEqualTo(RoomStatus.FINISHED);
    assertThat(room.getWinnerKey()).isNull();
    assertThat(p.getWinPoints()).isEqualTo(0);
    verify(broadcaster).gameFinished(eq("H12345"), any(GameSnapshot.class), eq((String) null));
  }

  // ── Thứ tự thao tác trong finishWithWinner ───────────────────────────────

  /**
   * Chụp điểm RIASEC phải chạy TRƯỚC {@code clearTurn()}.
   *
   * <p>Đảo thứ tự này phá hợp đồng chụp điểm: lượt đã đóng thì ảnh chụp mất
   * dữ liệu ván, đơn thanh toán đọc ra số 0.
   */
  @Test
  void thuTuThaoTacTrongFinishWithWinner() {
    Player p = player("owner", 1, 12);
    Room room = spy(Room.create("H12345", "Chủ phòng", false, "owner"));
    room.setActive(true);
    room.setStatus(RoomStatus.PLAYING);
    room.addPlayer(p); // gán phòng cho player
    byKey.put("owner", p);
    GameService gameService = service(RULES);

    gameService.finish(new FinishRequest("owner"));

    InOrder inOrder = inOrder(room);
    inOrder.verify(room).captureRiasecSnapshot();
    inOrder.verify(room).clearTurn();
  }

  /** Client nhận GAME (state ván) trước, rồi mới nhận FINISHED (bảng kết quả). */
  @Test
  void thuTuBroadcastGameUpdatedKeGameFinished() {
    Player p = player("owner", 0, 12);
    Room room = room("owner", p);
    moLuot(room, p);
    GameService gameService = service(RULES);
    stubRollQuaStart();

    gameService.rollDice(new RollRequest("owner", room.getTurnId(), false, "manual"));

    InOrder inOrder = inOrder(broadcaster);
    inOrder.verify(broadcaster).gameUpdated(eq("H12345"), any(GameSnapshot.class));
    inOrder.verify(broadcaster).gameFinished(eq("H12345"), any(GameSnapshot.class), eq("owner"));
  }
}
