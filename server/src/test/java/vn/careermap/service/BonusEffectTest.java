package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.domain.RoomStatus;
import vn.careermap.domain.TurnPhase;
import vn.careermap.exception.NotYourTurnException;
import vn.careermap.exception.TurnBusyException;
import vn.careermap.repo.PlayerRepository;
import vn.careermap.web.dto.AnswerRequest;
import vn.careermap.web.dto.AnswerResponse;
import vn.careermap.web.dto.BonusEffectRequest;
import vn.careermap.web.dto.BonusEffectResponse;

/**
 * Server là NGUỒN SỰ THẬT cho hiệu ứng 20 lá Cơ hội / Thử thách.
 *
 * <p>Ba lớp chống khai thác được khoá ở đây:
 *
 * <ol>
 *   <li><b>Cửa</b> — không có phòng đang chơi (ván một người, phòng đã xong,
 *       phòng chưa bắt đầu) thì KHÔNG được cộng điểm (403).
 *   <li><b>Lượt</b> — không phải lượt của mình / lượt không ở pha chờ trả lời
 *       thì 409.
 *   <li><b>Lá</b> — lá là do SERVER rút và cấp, không phải lá client tự khai
 *       (lệch ⇒ 400).
 * </ol>
 *
 * <p>Thuần JUnit + AssertJ + Mockito, không Spring context, không database.
 */
class BonusEffectTest {
  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 15, false, 5L);

  /** key -> player, đóng vai "database" cho {@code resolvePlayer}. */
  private final Map<String, Player> byKey = new HashMap<>();

  private GameService service() {
    PlayerRepository players = mock(PlayerRepository.class);
    when(players.findByPlayerKey(anyString()))
        .thenAnswer(call -> Optional.ofNullable(byKey.get(call.<String>getArgument(0))));
    when(players.save(any(Player.class))).thenAnswer(call -> call.getArgument(0));
    // TurnCoordinator THẬT: cần `needsAnswerAt` đúng luật bàn (mọi ô trừ START
    // và ⭐ đều phải rút thẻ) — mock sẽ trả false cho mọi ô.
    TurnCoordinator turns =
        new TurnCoordinator(RULES, (room, player, now) -> false, new CardEffectRegistry());
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
    for (Player p : members) {
      room.addPlayer(p);
      byKey.put(p.getPlayerKey(), p);
    }
    room.setActive(true);
    room.setStatus(RoomStatus.PLAYING);
    return room;
  }

  private Player player(String key, int position) {
    Player p = Player.create(key, key);
    p.setCurrentPosition(position);
    return p;
  }

  /** Người chơi chơi MỘT MÌNH — không ở phòng nào. */
  private Player soloPlayer(String key) {
    Player p = player(key, 10);
    byKey.put(key, p);
    return p;
  }

  /**
   * Lượt trả lời ở ô KHÔNG phải ô thẻ (ô RIASEC / ô ⭐): không có lá để rút.
   */
  private void openAnswerTurn(Room room, Player player) {
    room.setPendingAnswer(true);
    room.setPendingCardId(null);
    room.beginTurn(player, TurnPhase.WAITING_ANSWER, Instant.now().plusSeconds(60), false);
  }

  /**
   * Lượt trả lời ở ô THẺ: server đã rút sẵn lá {@code cardId} và lưu vào phòng.
   *
   * <p>Mô phỏng đúng trạng thái thật sau khi {@code TurnCoordinator.rollAndMove}
   * rút lá.
   */
  private void openCardTurn(Room room, Player player, String cardId) {
    room.setPendingAnswer(true);
    room.setPendingCardId(cardId);
    room.beginTurn(player, TurnPhase.WAITING_ANSWER, Instant.now().plusSeconds(60), false);
  }

  private static void assertBadRequest(Throwable thrown) {
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(((ResponseStatusException) thrown).getReason()).isNotBlank();
  }

  private static void assertForbidden(Throwable thrown) {
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(((ResponseStatusException) thrown).getReason()).isNotBlank();
  }

  private static void assertConflict(Throwable thrown) {
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(((ResponseStatusException) thrown).getReason()).isNotBlank();
  }

  // ── CỬA: không có phòng đang chơi thì không được cộng điểm ─────────────

  @Test
  void khongCoPhongKhongDuocCongDiem() {
    // Ván một người: trước đây endpoint bỎ QUA mọi chốt lượt ⇒ gọi 5 lần là
    // cộng 3 + 6 + 9 + 12 + 15 điểm. Nay chặn ở cửa.
    Player solo = soloPlayer("solo1");

    assertThatThrownBy(
            () ->
                service()
                    .applyBonusEffect(new BonusEffectRequest("solo1", "CƠ HỘI 05", null, null, null)))
        .satisfies(BonusEffectTest::assertForbidden);

    assertThat(solo.getWinPoints()).isZero();
    assertThat(solo.getTotalAnswered()).isZero();
  }

  @Test
  void phongDaKetThucKhongDuocCongDiem() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    room.setStatus(RoomStatus.FINISHED);
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 05");

    assertThatThrownBy(
            () ->
                service()
                    .applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 05", null, null, null)))
        .satisfies(BonusEffectTest::assertForbidden);

    assertThat(room.findPlayer("p1").getWinPoints()).isZero();
  }

  @Test
  void phongChuaBatDauKhongDuocCongDiem() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    room.setActive(false);
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 05");

    assertThatThrownBy(
            () ->
                service()
                    .applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 05", null, null, null)))
        .satisfies(BonusEffectTest::assertForbidden);

    assertThat(room.findPlayer("p1").getWinPoints()).isZero();
  }

  @Test
  void khongPhaiLuotCuaMinhKhongDuocCongDiem() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 05");

    assertThatThrownBy(
            () ->
                service()
                    .applyBonusEffect(new BonusEffectRequest("p2", "CƠ HỘI 05", null, null, null)))
        .isInstanceOf(NotYourTurnException.class);

    assertThat(room.findPlayer("p2").getWinPoints()).isZero();
    assertThat(room.findPlayer("p1").getWinPoints()).isZero();
  }

  @Test
  void khongPhaiLuotRutTheKhongDuocApHieuUng() {
    // Lượt chờ trả lời nhưng rơi vào ô RIASEC ⇒ không có lá để áp hiệu ứng.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    assertThatThrownBy(
            () ->
                service()
                    .applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 05", null, null, null)))
        .satisfies(
            thrown -> {
              assertThat(thrown).isInstanceOf(ResponseStatusException.class);
              assertThat(((ResponseStatusException) thrown).getStatusCode())
                  .isEqualTo(HttpStatus.CONFLICT);
            });

    assertThat(room.findPlayer("p1").getWinPoints()).isZero();
  }

  // ── LÁ: server rút, client không tự chọn ─────────────────────────────────

  @Test
  void clientKhongTheTuKhaiCardId() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 01");

    // Client khai lá đẹp hơn (CƠ HỘI 05 = 3 điểm) trong khi server đã cấp
    // "CƠ HỘI 01" (2 điểm) ⇒ 400, không cộng điểm.
    assertThatThrownBy(
            () ->
                service()
                    .applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 05", null, null, null)))
        .satisfies(BonusEffectTest::assertBadRequest);

    assertThat(room.findPlayer("p1").getWinPoints()).isZero();
  }

  @Test
  void clientGuiCardIdKhongDungCardIdDaRut() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 05");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 05", null, null, null));

    assertThat(res.winPoints()).isEqualTo(3);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(3);
  }

  @Test
  void bonusEffectKhongGuiCardIdThiDungLaDaRut() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 05");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", null, null, null, null));

    assertThat(res.winPoints()).isEqualTo(3);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(3);
  }

  // ── NONE: chỉ cộng điểm, đóng lượt ───────────────────────────────────────

  @Test
  void coHoi01ChiCongDiem() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 01");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 01", null, null, null));

    assertThat(res.effect()).isEqualTo("NONE");
    assertThat(res.winPoints()).isEqualTo(2);
    assertThat(res.awardedPlayerKeys()).containsExactly("p1");
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(2);
    // Không di chuyển → không có from/to.
    assertThat(res.fromPosition()).isNull();
    assertThat(res.toPosition()).isNull();
    // Lượt đã đóng, sang người kế tiếp.
    assertThat(room.getTurnPlayerKey()).isEqualTo("p2");
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.WAITING_ROLL);
    assertThat(room.isPendingAnswer()).isFalse();
    // Lá đã dùng xong ⇒ không còn lá treo trên phòng.
    assertThat(room.getPendingCardId()).isNull();
  }

  @Test
  void thuThach01ChiCongDiem() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "THỬ THÁCH 01");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "THỬ THÁCH 01", null, null, null));

    assertThat(res.effect()).isEqualTo("NONE");
    assertThat(res.winPoints()).isEqualTo(2);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(2);
  }

  @Test
  void thuThach04Cong3Diem() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "THỬ THÁCH 04");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "THỬ THÁCH 04", null, null, null));

    assertThat(res.winPoints()).isEqualTo(3);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(3);
  }

  // ── MOVE_2 / MOVE_3: đi theo chiều đi, KHÔNG cộng điểm ───────────────────

  @Test
  void coHoi03DiThem2O() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    p1.setCurrentPosition(6);
    openCardTurn(room, p1, "CƠ HỘI 03");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 03", null, null, null));

    assertThat(res.effect()).isEqualTo("MOVE_2");
    assertThat(res.winPoints()).isZero();
    assertThat(res.fromPosition()).isEqualTo(6);
    assertThat(res.toPosition()).isEqualTo(8);
    assertThat(p1.getCurrentPosition()).isEqualTo(8);
    // Lá này thay bằng việc đi ⇒ KHÔNG cộng điểm.
    assertThat(p1.getWinPoints()).isZero();
    // Đích là ô thẻ (ô 8 = S) → mở lượt trả lời cho CHÍNH người này.
    assertThat(room.getTurnPlayerKey()).isEqualTo("p1");
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.WAITING_ANSWER);
    assertThat(room.isPendingAnswer()).isTrue();
  }

  @Test
  void moveForwardXoaLastDice() {
    // Lá dời quân ⇒ mặt xúc xắc CŨ phải bị xoá (khớp `closeAnswerTurn`),
    // nếu không client vẫn hiện mặt xúc xắc của lượt trước.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    p1.setCurrentPosition(6);
    openCardTurn(room, p1, "CƠ HỘI 03");
    room.setLastDice(5);

    service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 03", null, null, null));

    assertThat(room.getLastDice()).isNull();
  }

  @Test
  void coHoi09DiThem3O() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    p1.setCurrentPosition(50);
    openCardTurn(room, p1, "CƠ HỘI 09");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 09", null, null, null));

    assertThat(res.effect()).isEqualTo("MOVE_3");
    assertThat(res.winPoints()).isZero();
    assertThat(res.fromPosition()).isEqualTo(50);
    assertThat(res.toPosition()).isEqualTo(0); // về đúng ô START
    assertThat(p1.getCurrentPosition()).isEqualTo(0);
    // Đích là START (không phải ô thẻ) → đóng lượt, sang người kế tiếp.
    assertThat(room.getTurnPlayerKey()).isEqualTo("p2");
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.WAITING_ROLL);
  }

  @Test
  void quaStartBangHieuUngThiCongVong() {
    // 1 - 3 = -2 ⇒ vượt qua START ⇒ phải cộng 1 vòng, nếu không người chơi
    // đi qua START bằng hiệu ứng sẽ không bao giờ đủ điều kiện thắng.
    Room room = room("p1", player("p1", 50), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    openCardTurn(room, p1, "CƠ HỘI 09");

    service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 09", null, null, null));

    assertThat(p1.getCompletedLaps()).isEqualTo(1);
    assertThat(p1.getCurrentPosition()).isZero();
  }

  @Test
  void dapDungOStartBangLaPhaiCongMotVong() {
    // Ô 3 - 3 = 0 ⇒ quân đáp ĐÚNG ô START ⇒ vẫn phải cộng 1 vòng.
    // Luật cũ (`raw < 0`) chỉ cộng vòng khi đi XUYÊN qua START ⇒ đáp đúng ô
    // START bằng lá thì MẤT vòng ⇒ người chơi đã đủ 15 câu vẫn phải đi thêm
    // một vòng nữa mới thắng.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    p1.setCurrentPosition(50);
    openCardTurn(room, p1, "CƠ HỘI 09");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 09", null, null, null));

    assertThat(res.toPosition()).isEqualTo(0); // đáp đúng ô START
    assertThat(p1.getCurrentPosition()).isZero();
    assertThat(p1.getCompletedLaps()).isEqualTo(1);
  }

  @Test
  void dapDungOStartBangLaPhaiCongMotVongMove2() {
    // Ô 2 - 2 = 0 ⇒ đáp ĐÚNG ô START bằng lá MOVE_2 ⇒ cũng phải cộng 1 vòng.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    p1.setCurrentPosition(51);
    openCardTurn(room, p1, "CƠ HỘI 03");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 03", null, null, null));

    assertThat(res.toPosition()).isEqualTo(0); // đáp đúng ô START
    assertThat(p1.getCompletedLaps()).isEqualTo(1);
  }

  @Test
  void du15CauVaDapDungOStartBangLaPhaiKetThucVan() {
    // ĐỦ 15 câu + đáp đúng ô START bằng lá ⇒ ván phải KẾT THÚC. Trước đây
    // (luật `raw < 0`) lá dời quân không cộng vòng ⇒ ván cứ tiếp tục, người
    // chơi phải đi thêm một vòng nữa mới thắng.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    p1.setCurrentPosition(50);
    for (int i = 0; i < 15; i++) {
      p1.incrementTotalAnswered();
    }
    openCardTurn(room, p1, "CƠ HỘI 09");

    service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 09", null, null, null));

    assertThat(p1.getCompletedLaps()).isEqualTo(1);
    assertThat(room.getStatus()).isEqualTo(RoomStatus.FINISHED);
    assertThat(room.getWinnerKey()).isEqualTo("p1");
  }

  // ── EXTRA_ROLL: chơi thêm một lượt ngay ──────────────────────────────────

  @Test
  void coHoi02ChoThemMotLuot() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 02");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 02", null, null, null));

    assertThat(res.effect()).isEqualTo("EXTRA_ROLL");
    assertThat(res.winPoints()).isEqualTo(2);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(2);
    // Lượt mới mở cho CHÍNH người này (được tung tiếp ngay), không sang người kế tiếp.
    assertThat(room.getTurnPlayerKey()).isEqualTo("p1");
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.WAITING_ROLL);
    assertThat(room.isPendingAnswer()).isFalse();
    assertThat(room.getPendingCardId()).isNull();
  }

  // ── PICK_PLAYER_PLUS1: chọn 1 người, cả hai cùng +1 ─────────────────────

  @Test
  void coHoi04ChonMotNguoiVaCaHaiNhanPlus1() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 04");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 04", null, "p2", null));

    assertThat(res.effect()).isEqualTo("PICK_PLAYER_PLUS1");
    assertThat(res.winPoints()).isEqualTo(1);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(1);
    assertThat(room.findPlayer("p2").getWinPoints()).isEqualTo(1);
    assertThat(res.awardedPlayerKeys()).containsExactly("p1", "p2");
  }

  @Test
  void coHoi04ChonChinhMinhThiChiCongMotLan() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 04");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 04", null, "p1", null));

    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(1);
    assertThat(res.awardedPlayerKeys()).containsExactly("p1");
  }

  @Test
  void coHoi04ChonChinhMinhThiThongBaoChiMotNguoi() {
    // Chọn CHÍNH MÌNH ⇒ chỉ MỘT người nhận ⇒ câu thông báo không được nói
    // "cùng nhận" (trước đây luôn nói vậy ⇒ người chơi tưởng có 2 người nhận).
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 04");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 04", null, "p1", null));

    assertThat(res.message()).contains("Bạn nhận +1 Điểm Chiến thắng.");
    assertThat(res.message()).doesNotContain("đồng hành");
    assertThat(res.message()).doesNotContain("cùng nhận");
  }

  @Test
  void coHoi04ChonNguoiKhacThiThongBaoHaiNguoi() {
    // Chọn NGƯỜI KHÁC ⇒ HAI người cùng nhận ⇒ câu thông báo phải nói đúng.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 04");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 04", null, "p2", null));

    assertThat(res.message()).contains("Bạn và người bạn đồng hành cùng nhận +1 Điểm Chiến thắng.");
  }

  @Test
  void coHoi04ChonNguoiKhongThuocPhong() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 04");

    assertThatThrownBy(
            () -> service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 04", null, "ghost", null)))
        .satisfies(BonusEffectTest::assertForbidden);

    // Người bị chặn KHÔNG được cộng điểm, và không sinh dòng player mới.
    assertThat(room.findPlayer("p1").getWinPoints()).isZero();
    assertThat(byKey).doesNotContainKey("ghost");
  }

  @Test
  void coHoi04ChonNguoiDaRoiPhong() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    room.findPlayer("p2").setLeft(true);
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 04");

    assertThatThrownBy(
            () -> service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 04", null, "p2", null)))
        .satisfies(BonusEffectTest::assertForbidden);
  }

  @Test
  void coHoi04ChuaChonNguoiThiBaoLoi() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 04");

    assertThatThrownBy(
            () -> service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 04", null, null, null)))
        .satisfies(BonusEffectTest::assertBadRequest);
  }

  // ── ALL_PLAYERS_PLUS1: cả bàn cùng +1 ───────────────────────────────────

  @Test
  void coHoi08TatCaNguoiNhanPlus1() {
    Room room = room("p1", player("p1", 10), player("p2", 10), player("p3", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 08");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 08", null, null, null));

    assertThat(res.effect()).isEqualTo("ALL_PLAYERS_PLUS1");
    assertThat(res.winPoints()).isEqualTo(1);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(1);
    assertThat(room.findPlayer("p2").getWinPoints()).isEqualTo(1);
    assertThat(room.findPlayer("p3").getWinPoints()).isEqualTo(1);
    assertThat(res.awardedPlayerKeys()).containsExactly("p1", "p2", "p3");
  }

  @Test
  void coHoi08KhongCongChoNguoiDaRoiPhong() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    room.findPlayer("p2").setLeft(true);
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 08");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 08", null, null, null));

    assertThat(res.awardedPlayerKeys()).containsExactly("p1");
    assertThat(room.findPlayer("p2").getWinPoints()).isZero();
  }

  // ── PLUS3_OR_MOVE_2: chọn 1 trong 2 ──────────────────────────────────────

  @Test
  void coHoi10ChonMotTrongHai() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 10");

    // Choice sai → 400, không được cộng điểm.
    assertThatThrownBy(
            () ->
                service()
                    .applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 10", "PLUS_5", null, null)))
        .satisfies(BonusEffectTest::assertBadRequest);
    assertThat(room.findPlayer("p1").getWinPoints()).isZero();

    // Choice hợp lệ → +3 điểm.
    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 10", "PLUS_3", null, null));
    assertThat(res.effect()).isEqualTo("PLUS3_OR_MOVE_2");
    assertThat(res.winPoints()).isEqualTo(3);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(3);
  }

  @Test
  void coHoi10ChonDiThem2O() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    p1.setCurrentPosition(6);
    openCardTurn(room, p1, "CƠ HỘI 10");

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 10", "MOVE_2", null, null));

    assertThat(res.winPoints()).isZero();
    assertThat(res.fromPosition()).isEqualTo(6);
    assertThat(res.toPosition()).isEqualTo(8);
    assertThat(p1.getCurrentPosition()).isEqualTo(8);
    assertThat(p1.getWinPoints()).isZero();
  }

  @Test
  void coHoi10ChuaChonThiBaoLoi() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 10");

    assertThatThrownBy(
            () -> service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 10", null, null, null)))
        .satisfies(BonusEffectTest::assertBadRequest);
  }

  // ── Ngưỡng thắng đếm CÂU ĐÃ TRẢ LỜI, không phải LÁ ĐÃ RÚT ───────────────

  @Test
  void rutLaTinhVaoSoCauDaTraLoi() {
    // Rút lá = đã trả lời 1 câu ở ô đích: ngưỡng thắng (số câu) không được
    // nặng hơn đường cũ (`/game/answer`).
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 01");

    service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 01", null, null, null));

    assertThat(room.findPlayer("p1").getTotalAnswered()).isEqualTo(1);
  }

  @Test
  void moveKhongCongTotalAnswered() {
    // Lá MOVE_2: người chơi KHÔNG trả lời câu nào, chỉ đi thêm 2 ô ⇒ không
    // được tính vào "số câu đã trả lời" (ngưỡng thắng là totalAnswered >= 15).
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 03");

    service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 03", null, null, null));

    assertThat(room.findPlayer("p1").getTotalAnswered()).isZero();
  }

  @Test
  void move3KhongCongTotalAnswered() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 09");

    service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 09", null, null, null));

    assertThat(room.findPlayer("p1").getTotalAnswered()).isZero();
  }

  @Test
  void coHoi10ChonMove2KhongCongTotalAnswered() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 10");

    service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 10", "MOVE_2", null, null));

    assertThat(room.findPlayer("p1").getTotalAnswered()).isZero();
  }

  // ── Ràng buộc: lá lạ, lá thiếu dấu, client tự khai điểm ─────────────────

  @Test
  void cardIdKhongBietThiBaoLoi() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 01");

    assertThatThrownBy(
            () ->
                service()
                    .applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 99", null, null, null)))
        .satisfies(BonusEffectTest::assertBadRequest);
    assertThat(room.findPlayer("p1").getWinPoints()).isZero();
  }

  @Test
  void cardIdKhongDungDauKhongDoiChieu() {
    // "CO HOI 01" (không dấu) KHÔNG phải "CƠ HỘI 01" — lá sai phải bị từ chối.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 01");

    assertThatThrownBy(
            () ->
                service().applyBonusEffect(new BonusEffectRequest("p1", "CO HOI 01", null, null, null)))
        .satisfies(BonusEffectTest::assertBadRequest);
  }

  @Test
  void clientKhongTheTuKhaiDiem() {
    // Request KHÔNG có field winPoints ⇒ client không có chỗ để khai điểm.
    // (Cờ `skipped` là BÁO CÁO, không phải khai điểm — server tự quyết.)
    assertThat(BonusEffectRequest.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .containsExactly("playerId", "cardId", "choice", "targetPlayerKey", "skipped");

    // ... và server vẫn tự tính đúng theo bảng.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 05");
    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 05", null, null, null));

    assertThat(res.winPoints()).isEqualTo(3);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(3);
  }

  @Test
  void goiHaiLanKhongCongDiemHaiLan() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 01");
    GameService service = service();

    service.applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 01", null, null, null));
    // Lượt đã đóng ⇒ gọi lại bị chặn, không farm được điểm.
    assertThatThrownBy(
            () -> service.applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 01", null, null, null)))
        .isInstanceOf(TurnBusyException.class);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(2);
  }

  @Test
  void nguoiChoiKhongTonTaiThiBaoLoi() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 01");

    assertThatThrownBy(
            () -> service().applyBonusEffect(new BonusEffectRequest("ghost", "CƠ HỘI 01", null, null, null)))
        .satisfies(BonusEffectTest::assertBadRequest);
  }

  // ── Đường CŨ (/game/answer): server KHÔNG tin lời client ─────────────────

  @Test
  void duongCuAnswerBiTuChoiKhiPhongDangChoLaBai() {
    // Đường CŨ chỉ dành cho câu hỏi RIASEC. Khi phòng đang chờ xử lý LÁ BÀI
    // thì mọi điểm lá phải do /api/game/bonus-effect quyết — nếu không, đường
    // cũ là "đường thứ hai" cộng điểm lá (và cộng totalAnswered cho lá MOVE).
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "CƠ HỘI 05");

    assertThatThrownBy(
            () -> service().answer(new AnswerRequest("p1", "CƠ HỘI 05", "A", "R", null, 0, 999)))
        .satisfies(BonusEffectTest::assertConflict);

    // KHÔNG cộng điểm, KHÔNG cộng câu đã trả lời, lượt KHÔNG bị đóng.
    assertThat(room.findPlayer("p1").getWinPoints()).isZero();
    assertThat(room.findPlayer("p1").getTotalAnswered()).isZero();
    assertThat(room.getTurnPlayerKey()).isEqualTo("p1");
  }

  @Test
  void duongCuKhongTinDiemClientKhai() {
    // Lượt KHÔNG phải ô thẻ (ô RIASEC) ⇒ không có lá để rút ⇒ client khai 999
    // cũng không được cộng điểm nào.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    AnswerResponse res =
        service().answer(new AnswerRequest("p1", "CƠ HỘI 01", "A", "R", null, 0, 999));

    assertThat(res.winPoints()).isZero();
    assertThat(room.findPlayer("p1").getWinPoints()).isZero();
  }

  @Test
  void duongCuBoQuaTheVanKhongNhanDiem() {
    // Nút "không hoàn thành" gửi winPoints = 0. Lượt này không phải ô thẻ ⇒
    // không có lá ⇒ 0 điểm.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    AnswerResponse res =
        service().answer(new AnswerRequest("p1", "THỬ THÁCH 04", "A", "R", null, 0, 0));

    assertThat(res.winPoints()).isZero();
    assertThat(room.findPlayer("p1").getWinPoints()).isZero();
  }

  @Test
  void duongCuKhongTuQuyetDiemChoLaDaRut() {
    // Lượt RÚT LÁ: đường cũ ĐÃ ĐÓNG (409) — điểm lá chỉ qua
    // /api/game/bonus-effect ⇒ không còn đường thứ hai cộng điểm.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "THỬ THÁCH 04");

    assertThatThrownBy(
            () -> service().answer(new AnswerRequest("p1", "THỬ THÁCH 04", "A", "R", null, 0, 0)))
        .satisfies(BonusEffectTest::assertConflict);

    assertThat(room.findPlayer("p1").getWinPoints()).isZero();
  }

  @Test
  void duongCuCauHoiRiasecKhongNhanDiemChienThang() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    AnswerResponse res = service().answer(new AnswerRequest("p1", "R01", "A", "R", null, 2, 999));

    assertThat(res.winPoints()).isZero();
    assertThat(room.findPlayer("p1").getWinPoints()).isZero();
  }

  @Test
  void duongCuOsaoChiChoMotDiem() {
    // Ô ⭐ (ô 7) theo luật bàn là +1 điểm, không hơn.
    Room room = room("p1", player("p1", 7), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    AnswerResponse res = service().answer(new AnswerRequest("p1", "BONUS", "A", "R", null, 0, 5));

    assertThat(res.winPoints()).isEqualTo(1);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(1);
  }

  // ── Bảng 20 lá: đủ, đúng id (có dấu), đúng điểm ─────────────────────────

  @Test
  void bangHieuUngCoDu20La() {
    CardEffectRegistry registry = new CardEffectRegistry();

    assertThat(registry.cardIds()).hasSize(20);
    assertThat(registry.cardIds())
        .contains(
            "CƠ HỘI 01",
            "CƠ HỘI 02",
            "CƠ HỘI 03",
            "CƠ HỘI 04",
            "CƠ HỘI 05",
            "CƠ HỘI 06",
            "CƠ HỘI 07",
            "CƠ HỘI 08",
            "CƠ HỘI 09",
            "CƠ HỘI 10",
            "THỬ THÁCH 01",
            "THỬ THÁCH 02",
            "THỬ THÁCH 03",
            "THỬ THÁCH 04",
            "THỬ THÁCH 05",
            "THỬ THÁCH 06",
            "THỬ THÁCH 07",
            "THỬ THÁCH 08",
            "THỬ THÁCH 09",
            "THỬ THÁCH 10");
  }

  @Test
  void bangHieuUngKhopDiemVaHieuUng() {
    CardEffectRegistry registry = new CardEffectRegistry();

    assertThat(registry.effectOf("CƠ HỘI 01").effect().name()).isEqualTo("NONE");
    assertThat(registry.effectOf("CƠ HỘI 01").winPoints()).isEqualTo(2);
    assertThat(registry.effectOf("CƠ HỘI 02").effect().name()).isEqualTo("EXTRA_ROLL");
    assertThat(registry.effectOf("CƠ HỘI 03").effect().name()).isEqualTo("MOVE_2");
    assertThat(registry.effectOf("CƠ HỘI 04").effect().name()).isEqualTo("PICK_PLAYER_PLUS1");
    assertThat(registry.effectOf("CƠ HỘI 04").winPoints()).isEqualTo(1);
    assertThat(registry.effectOf("CƠ HỘI 05").winPoints()).isEqualTo(3);
    assertThat(registry.effectOf("CƠ HỘI 08").effect().name()).isEqualTo("ALL_PLAYERS_PLUS1");
    assertThat(registry.effectOf("CƠ HỘI 09").effect().name()).isEqualTo("MOVE_3");
    assertThat(registry.effectOf("CƠ HỘI 10").effect().name()).isEqualTo("PLUS3_OR_MOVE_2");
    assertThat(registry.effectOf("CƠ HỘI 10").allowedChoices()).containsExactlyInAnyOrder("PLUS_3", "MOVE_2");
    assertThat(registry.effectOf("THỬ THÁCH 01").winPoints()).isEqualTo(2);
    assertThat(registry.effectOf("THỬ THÁCH 04").winPoints()).isEqualTo(3);
    assertThat(registry.effectOf("THỬ THÁCH 07").winPoints()).isEqualTo(3);
    assertThat(registry.effectOf("THỬ THÁCH 10").winPoints()).isEqualTo(3);
    assertThat(registry.effectOf("KHONG TON TAI")).isNull();
  }
}
