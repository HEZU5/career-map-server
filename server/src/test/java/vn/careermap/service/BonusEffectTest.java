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
 * <p>Trước đây client tự khai {@code winPoints} rồi gửi lên, server tin theo ⇒
 * ai cũng tự khai điểm tuỳ ý. Nay server tự tra bảng hiệu ứng theo {@code
 * cardId}, tự tính điểm và tự áp hiệu ứng thật (tiến thêm ô, chơi thêm lượt,
 * cộng cho cả bàn...).
 *
 * <p>Thuần JUnit + AssertJ + Mockito, không Spring context, không database.
 */
class BonusEffectTest {
  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, false, 5L);

  /** key -> player, đóng vai "database" cho {@code resolvePlayer}. */
  private final Map<String, Player> byKey = new HashMap<>();

  private GameService service() {
    PlayerRepository players = mock(PlayerRepository.class);
    when(players.findByPlayerKey(anyString()))
        .thenAnswer(call -> Optional.ofNullable(byKey.get(call.<String>getArgument(0))));
    when(players.save(any(Player.class))).thenAnswer(call -> call.getArgument(0));
    // TurnCoordinator THẬT: cần `needsAnswerAt` đúng luật bàn (mọi ô trừ START
    // và ⭐ đều phải rút thẻ) — mock sẽ trả false cho mọi ô.
    TurnCoordinator turns = new TurnCoordinator(RULES, (room, player, now) -> false);
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

  /** Đưa phòng vào đúng trạng thái lúc vừa rơi vào ô thẻ: chờ trả lời. */
  private void openAnswerTurn(Room room, Player player) {
    room.setPendingAnswer(true);
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
  }

  // ── NONE: chỉ cộng điểm, đóng lượt ───────────────────────────────────────

  @Test
  void coHoi01ChiCongDiem() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 01", null, null));

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
  }

  @Test
  void thuThach01ChiCongDiem() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "THỬ THÁCH 01", null, null));

    assertThat(res.effect()).isEqualTo("NONE");
    assertThat(res.winPoints()).isEqualTo(2);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(2);
  }

  @Test
  void thuThach04Cong3Diem() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "THỬ THÁCH 04", null, null));

    assertThat(res.winPoints()).isEqualTo(3);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(3);
  }

  // ── MOVE_2 / MOVE_3: đi theo chiều đi, KHÔNG cộng điểm ───────────────────

  @Test
  void coHoi03DiThem2O() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    p1.setCurrentPosition(10);
    openAnswerTurn(room, p1);

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 03", null, null));

    assertThat(res.effect()).isEqualTo("MOVE_2");
    assertThat(res.winPoints()).isZero();
    assertThat(res.fromPosition()).isEqualTo(10);
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
  void coHoi09DiThem3O() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    p1.setCurrentPosition(3);
    openAnswerTurn(room, p1);

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 09", null, null));

    assertThat(res.effect()).isEqualTo("MOVE_3");
    assertThat(res.winPoints()).isZero();
    assertThat(res.fromPosition()).isEqualTo(3);
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
    Room room = room("p1", player("p1", 1), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    openAnswerTurn(room, p1);

    service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 09", null, null));

    assertThat(p1.getCompletedLaps()).isEqualTo(1);
    assertThat(p1.getCurrentPosition()).isEqualTo(51);
  }

  // ── EXTRA_ROLL: chơi thêm một lượt ngay ──────────────────────────────────

  @Test
  void coHoi02ChoThemMotLuot() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 02", null, null));

    assertThat(res.effect()).isEqualTo("EXTRA_ROLL");
    assertThat(res.winPoints()).isEqualTo(2);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(2);
    // Lượt mới mở cho CHÍNH người này (được tung tiếp ngay), không sang người kế tiếp.
    assertThat(room.getTurnPlayerKey()).isEqualTo("p1");
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.WAITING_ROLL);
    assertThat(room.isPendingAnswer()).isFalse();
  }

  // ── PICK_PLAYER_PLUS1: chọn 1 người, cả hai cùng +1 ─────────────────────

  @Test
  void coHoi04ChonMotNguoiVaCaHaiNhanPlus1() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 04", null, "p2"));

    assertThat(res.effect()).isEqualTo("PICK_PLAYER_PLUS1");
    assertThat(res.winPoints()).isEqualTo(1);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(1);
    assertThat(room.findPlayer("p2").getWinPoints()).isEqualTo(1);
    assertThat(res.awardedPlayerKeys()).containsExactly("p1", "p2");
  }

  @Test
  void coHoi04ChonChinhMinhThiChiCongMotLan() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 04", null, "p1"));

    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(1);
    assertThat(res.awardedPlayerKeys()).containsExactly("p1");
  }

  @Test
  void coHoi04ChonNguoiKhongThuocPhong() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    assertThatThrownBy(
            () -> service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 04", null, "ghost")))
        .satisfies(BonusEffectTest::assertForbidden);

    // Người bị chặn KHÔNG được cộng điểm, và không sinh dòng player mới.
    assertThat(room.findPlayer("p1").getWinPoints()).isZero();
    assertThat(byKey).doesNotContainKey("ghost");
  }

  @Test
  void coHoi04ChonNguoiDaRoiPhong() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    room.findPlayer("p2").setLeft(true);
    openAnswerTurn(room, room.findPlayer("p1"));

    assertThatThrownBy(
            () -> service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 04", null, "p2")))
        .satisfies(BonusEffectTest::assertForbidden);
  }

  @Test
  void coHoi04ChuaChonNguoiThiBaoLoi() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    assertThatThrownBy(
            () -> service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 04", null, null)))
        .satisfies(BonusEffectTest::assertBadRequest);
  }

  // ── ALL_PLAYERS_PLUS1: cả bàn cùng +1 ───────────────────────────────────

  @Test
  void coHoi08TatCaNguoiNhanPlus1() {
    Room room = room("p1", player("p1", 10), player("p2", 10), player("p3", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 08", null, null));

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
    openAnswerTurn(room, room.findPlayer("p1"));

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 08", null, null));

    assertThat(res.awardedPlayerKeys()).containsExactly("p1");
    assertThat(room.findPlayer("p2").getWinPoints()).isZero();
  }

  // ── PLUS3_OR_MOVE_2: chọn 1 trong 2 ──────────────────────────────────────

  @Test
  void coHoi10ChonMotTrongHai() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    // Choice sai → 400, không được cộng điểm.
    assertThatThrownBy(
            () ->
                service()
                    .applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 10", "PLUS_5", null)))
        .satisfies(BonusEffectTest::assertBadRequest);
    assertThat(room.findPlayer("p1").getWinPoints()).isZero();

    // Choice hợp lệ → +3 điểm.
    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 10", "PLUS_3", null));
    assertThat(res.effect()).isEqualTo("PLUS3_OR_MOVE_2");
    assertThat(res.winPoints()).isEqualTo(3);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(3);
  }

  @Test
  void coHoi10ChonDiThem2O() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    p1.setCurrentPosition(10);
    openAnswerTurn(room, p1);

    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 10", "MOVE_2", null));

    assertThat(res.winPoints()).isZero();
    assertThat(res.fromPosition()).isEqualTo(10);
    assertThat(res.toPosition()).isEqualTo(8);
    assertThat(p1.getCurrentPosition()).isEqualTo(8);
    assertThat(p1.getWinPoints()).isZero();
  }

  @Test
  void coHoi10ChuaChonThiBaoLoi() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    assertThatThrownBy(
            () -> service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 10", null, null)))
        .satisfies(BonusEffectTest::assertBadRequest);
  }

  @Test
  void rutLaTinhVaoSoCauDaTraLoi() {
    // Rút lá = đã trả lời 1 câu ở ô đích: ngưỡng thắng (số câu) không được
    // nặng hơn đường cũ (`/game/answer`).
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 01", null, null));

    assertThat(room.findPlayer("p1").getTotalAnswered()).isEqualTo(1);
  }

  // ── Ràng buộc: lá lạ, lá thiếu dấu, client tự khai điểm ─────────────────

  @Test
  void cardIdKhongBietThiBaoLoi() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    assertThatThrownBy(
            () ->
                service()
                    .applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 99", null, null)))
        .satisfies(BonusEffectTest::assertBadRequest);
    assertThat(room.findPlayer("p1").getWinPoints()).isZero();
  }

  @Test
  void cardIdKhongDungDauKhongDoiChieu() {
    // "CO HOI 01" (không dấu) KHÔNG phải "CƠ HỘI 01" — lá sai phải bị từ chối.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    assertThatThrownBy(
            () -> service().applyBonusEffect(new BonusEffectRequest("p1", "CO HOI 01", null, null)))
        .satisfies(BonusEffectTest::assertBadRequest);
  }

  @Test
  void clientKhongTheTuKhaiDiem() {
    // Request KHÔNG có field winPoints ⇒ client không có chỗ để khai điểm.
    assertThat(BonusEffectRequest.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .containsExactly("playerId", "cardId", "choice", "targetPlayerKey");

    // ... và server vẫn tự tính đúng theo bảng.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));
    BonusEffectResponse res =
        service().applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 05", null, null));

    assertThat(res.winPoints()).isEqualTo(3);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(3);
  }

  @Test
  void goiHaiLanKhongCongDiemHaiLan() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));
    GameService service = service();

    service.applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 01", null, null));
    // Lượt đã đóng ⇒ gọi lại bị chặn, không farm được điểm.
    assertThatThrownBy(
            () -> service.applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 01", null, null)))
        .isInstanceOf(TurnBusyException.class);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(2);
  }

  @Test
  void khongPhaiLuotCuaMinhThiKhongDuocApHieuUng() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    assertThatThrownBy(
            () -> service().applyBonusEffect(new BonusEffectRequest("p2", "CƠ HỘI 01", null, null)))
        .isInstanceOf(NotYourTurnException.class);
    assertThat(room.findPlayer("p2").getWinPoints()).isZero();
  }

  @Test
  void nguoiChoiKhongTonTaiThiBaoLoi() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    assertThatThrownBy(
            () -> service().applyBonusEffect(new BonusEffectRequest("ghost", "CƠ HỘI 01", null, null)))
        .satisfies(BonusEffectTest::assertBadRequest);
  }

  // ── Đường CŨ (/game/answer): server cũng không được tin lời client ───────

  @Test
  void duongCuKhongTinDiemClientKhai() {
    // Client khai 999 điểm cho lá "CƠ HỘI 01" ⇒ server cắt còn đúng số trong
    // bảng lá (2). Đây là lỗ hổng cũ: trước đây server tin nguyên lời client.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    AnswerResponse res =
        service().answer(new AnswerRequest("p1", "CƠ HỘI 01", "A", "R", null, 0, 999));

    assertThat(res.winPoints()).isEqualTo(2);
    assertThat(room.findPlayer("p1").getWinPoints()).isEqualTo(2);
  }

  @Test
  void duongCuBoQuaTheVanKhongNhanDiem() {
    // Nút "không hoàn thành" gửi winPoints = 0 ⇒ vẫn phải là 0 (đường cũ giữ
    // nguyên hành vi này, chỉ bị chặn ở trần trên).
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openAnswerTurn(room, room.findPlayer("p1"));

    AnswerResponse res =
        service().answer(new AnswerRequest("p1", "THỬ THÁCH 04", "A", "R", null, 0, 0));

    assertThat(res.winPoints()).isZero();
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
