package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.domain.RoomStatus;
import vn.careermap.domain.TurnPhase;
import vn.careermap.repo.PlayerRepository;
import vn.careermap.web.dto.DiceResponse;
import vn.careermap.web.dto.RollRequest;

/**
 * VÒNG cờ chỉ tính từ lần CHẠM ô START thứ 2 trở đi.
 *
 * <p>Luật cũ: cộng vòng khi {@code raw = pos - value < 0}. Vì quân bắt đầu ván
 * đứng ở ô START ({@code pos = 0}) nên lần tung ĐẦU TIÊN luôn có {@code raw < 0}
 * ⇒ vòng cộng ngay ở lần tung đầu ⇒ điều kiện "đủ 1 vòng" vô nghĩa (chỉ cần
 * tung một lần là đủ). Ngược lại, quân đáp ĐÚNG ô START ({@code raw == 0}) thì
 * lại KHÔNG cộng — tức quân vừa hoàn thành một vòng đầy đủ thì bộ đếm không
 * nhích.
 *
 * <p>Luật mới: một bước đi chỉ hoàn thành vòng khi quân CHẠM tới ô START
 * ({@code pos - value <= 0}) VÀ quân KHÔNG đứng ở ô START trước bước đi
 * ({@code pos != 0}). Nói cách khác: lần RỜI START đầu tiên không tính, tính từ
 * lần quay lại chạm START — tức "lần thứ 2".
 *
 * <p>File này kiểm tra hành vi THẬT của bộ đếm {@code completedLaps} (qua
 * {@code rollForTurn} và {@code rollSolo}), không chỉ kiểm tra hàm công thức.
 *
 * <p>Thuần JUnit + Mockito, không Spring context, không database.
 */
class CompletedLapsTest {

  /** Ngưỡng câu hỏi MẶC ĐỊNH mới = 15 câu. */
  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 15, false, 5L);

  /** key -> player, đóng vai "database" cho {@code resolvePlayer}. */
  private final Map<String, Player> byKey = new HashMap<>();

  /**
   * TurnCoordinator THẬT, chỉ chặn xúc xắc cho ra giá trị cố định.
   *
   * <p>Dùng {@code spy} + stub {@code rollValue()} (package-private) để mọi
   * đường đi thật của server vẫn chạy: chỉ có con xúc xắc là biết trước.
   */
  private TurnCoordinator turns(int diceValue) {
    TurnCoordinator turns =
        spy(
            new TurnCoordinator(
                RULES, (room, player, now) -> false, new CardEffectRegistry()));
    doReturn(diceValue).when(turns).rollValue();
    return turns;
  }

  private GameService service(TurnCoordinator turns) {
    PlayerRepository players = mock(PlayerRepository.class);
    when(players.findByPlayerKey(anyString()))
        .thenAnswer(call -> Optional.ofNullable(byKey.get(call.<String>getArgument(0))));
    when(players.save(any(Player.class))).thenAnswer(call -> call.getArgument(0));
    return new GameService(
        players,
        mock(GameBroadcaster.class),
        new GameSnapshotBuilder(RULES),
        turns,
        RULES,
        new CardEffectRegistry());
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

  private Player player(String key, int position) {
    Player p = Player.create(key, key);
    p.setCurrentPosition(position);
    return p;
  }

  private Player player(String key, int position, int answers) {
    Player p = player(key, position);
    for (int i = 0; i < answers; i++) {
      p.incrementTotalAnswered();
    }
    return p;
  }

  /** Mở lượt tung cho {@code roller} như một ván đang chơi thật. */
  private void moLuot(Room room, Player roller) {
    room.beginTurn(roller, TurnPhase.WAITING_ROLL, Instant.now().plusSeconds(60), false);
  }

  // ── Bộ đếm vòng trong phòng (rollForTurn → rollAndMove) ───────────────────

  @Test
  void quanDungOStartThiTungKhongCongVong() {
    // Quân đứng ở ô START (ô 0) ⇒ đây là lần RỜI START ĐẦU TIÊN ⇒ chưa có
    // vòng nào. Luật cũ cộng vòng ở đây ⇒ ván thắng được ngay lần tung đầu.
    Room room = room("p1", player("p1", 0));
    Player p = room.findPlayer("p1");
    moLuot(room, p);
    TurnCoordinator turns = turns(3);

    turns.rollForTurn(room, p, room.getTurnId(), false, "manual");

    assertThat(p.getCompletedLaps()).isZero();
    assertThat(p.getCurrentPosition()).isEqualTo(50);
  }

  @Test
  void quanDungOCellMotThiTungMotDapDungOStartThiCongMotVong() {
    // Ô 1 - 1 = 0 ⇒ đáp ĐÚNG ô START ⇒ đây mới là lần chạm START thứ 2.
    Room room = room("p1", player("p1", 1));
    Player p = room.findPlayer("p1");
    moLuot(room, p);
    TurnCoordinator turns = turns(1);

    turns.rollForTurn(room, p, room.getTurnId(), false, "manual");

    assertThat(p.getCompletedLaps()).isEqualTo(1);
    assertThat(p.getCurrentPosition()).isZero();
  }

  @Test
  void quanDungOCellHaiThiTungBaDiQuaStartThiCongMotVong() {
    // Ô 2 - 3 = -1 ⇒ đi XUYÊN QUA ô START ⇒ cũng tính 1 vòng.
    Room room = room("p1", player("p1", 2));
    Player p = room.findPlayer("p1");
    moLuot(room, p);
    TurnCoordinator turns = turns(3);

    turns.rollForTurn(room, p, room.getTurnId(), false, "manual");

    assertThat(p.getCompletedLaps()).isEqualTo(1);
    // 2 - 3 = -1 ⇒ floorMod(-1, 53) = 52 (quân đi ngược chiều mũi tên).
    assertThat(p.getCurrentPosition()).isEqualTo(52);
  }

  @Test
  void vongMoiChuaXongThiKhongCongThemVong() {
    // Đã về tới START (vòng 1) rồi rời START ⇒ vòng 2 CHƯA xong.
    Room room = room("p1", player("p1", 1));
    Player p = room.findPlayer("p1");
    TurnCoordinator turns = turns(1);

    moLuot(room, p);
    turns.rollForTurn(room, p, room.getTurnId(), false, "manual");
    assertThat(p.getCompletedLaps()).isEqualTo(1);

    moLuot(room, p);
    turns.rollForTurn(room, p, room.getTurnId(), false, "manual");
    assertThat(p.getCompletedLaps()).isEqualTo(1);
    assertThat(p.getCurrentPosition()).isEqualTo(52);
  }

  @Test
  void khongChamStartThiKhongCongVong() {
    // Ô 52 - 3 = 49 ⇒ không chạm START ⇒ không cộng vòng.
    Room room = room("p1", player("p1", 52));
    Player p = room.findPlayer("p1");
    moLuot(room, p);
    TurnCoordinator turns = turns(3);

    turns.rollForTurn(room, p, room.getTurnId(), false, "manual");

    assertThat(p.getCompletedLaps()).isZero();
  }

  // ── Bộ đếm vòng ở ván một người (GameService.rollSolo) ───────────────────

  @Test
  void vanSoloRoiStartLanDauKhongCongVong() {
    Player p = player("solo", 0);
    byKey.put("solo", p);
    GameService service = service(turns(3));

    DiceResponse res = service.rollDice(new RollRequest("solo", null, false, "manual"));

    assertThat(p.getCompletedLaps()).isZero();
    assertThat(res.gameFinished()).isFalse();
  }

  @Test
  void vanSoloDiQuaStartThiCongMotVong() {
    // Ô 2 - 3 = -1 ⇒ đi xuyên qua START ⇒ cộng 1 vòng.
    Player p = player("solo", 2);
    byKey.put("solo", p);
    GameService service = service(turns(3));

    service.rollDice(new RollRequest("solo", null, false, "manual"));

    assertThat(p.getCompletedLaps()).isEqualTo(1);
  }

  // ── Điều kiện thắng tổng thể ────────────────────────────────────────────

  @Test
  void du15CauNhungChuaChamStartLan2ThiVanChuaKetThuc() {
    // ĐỦ 15 câu nhưng CHƯA chạm ô START lần thứ 2 ⇒ chưa đủ điều kiện thắng.
    Player p = player("owner", 0, 15);
    Room room = room("owner", p);
    moLuot(room, p);
    GameService service = service(turns(3));

    DiceResponse res =
        service.rollDice(new RollRequest("owner", room.getTurnId(), false, "manual"));

    assertThat(p.getCompletedLaps()).isZero();
    assertThat(room.getStatus()).isEqualTo(RoomStatus.PLAYING);
    assertThat(room.getWinnerKey()).isNull();
    assertThat(res.gameFinished()).isFalse();
  }

  @Test
  void chamStartLan2VaDu15CauThiVanKetThuc() {
    // Ô 1 - 1 = 0 ⇒ đáp đúng ô START (lần thứ 2) + đủ 15 câu ⇒ ván tự kết thúc.
    Player p = player("owner", 1, 15);
    Room room = room("owner", p);
    moLuot(room, p);
    GameService service = service(turns(1));

    DiceResponse res =
        service.rollDice(new RollRequest("owner", room.getTurnId(), false, "manual"));

    assertThat(p.getCompletedLaps()).isEqualTo(1);
    assertThat(room.getStatus()).isEqualTo(RoomStatus.FINISHED);
    assertThat(room.getWinnerKey()).isEqualTo("owner");
    assertThat(res.gameFinished()).isTrue();
    assertThat(res.winnerKey()).isEqualTo("owner");
  }
}
