package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import vn.careermap.domain.TileType;
import vn.careermap.domain.TurnPhase;
import vn.careermap.exception.NotYourTurnException;
import vn.careermap.exception.TurnBusyException;
import vn.careermap.repo.PlayerRepository;
import vn.careermap.web.dto.AnswerRequest;
import vn.careermap.web.dto.BonusEffectRequest;
import vn.careermap.web.dto.BonusEffectResponse;

/**
 * TASK 3.5 — hai lỗ hổng luật chơi mà server phải TỰ quyết.
 *
 * <p><b>S1 — Ô ⭐ (SAO).</b> Trước đây {@code TurnCoordinator.needsAnswerAt()}
 * trả {@code false} cho ô ⭐ ⇒ server KHÔNG mở lượt trả lời ở ô sao ⇒ cả
 * {@code /game/answer} lẫn {@code /game/bonus-effect} đều trả 409 ⇒ client phải
 * TỰ cộng +1 ở máy ⇒ <b>bảng xếp hạng trên server sai</b>.
 *
 * <p>Nay: rơi vào ô ⭐ ⇒ server mở lượt trả lời và cấp một LÁ GIẢ {@code SAO}
 * (hiệu ứng NONE, 1 điểm) ⇒ điểm do server quyết, không phải client.
 *
 * <p><b>S2 — Cờ {@code skipped}.</b> Nút "Không hoàn thành" gửi {@code
 * winPoints: 0} mà đường sửa đã bỏ qua {@code winPoints} của client ⇒ nút bỏ qua
 * không còn tác dụng. Nay client <b>BÁO CÁO</b> {@code skipped = true}; server
 * <b>QUYẾT ĐỊNH</b>: không áp hiệu ứng, không cộng điểm, nhưng VẪN đóng lượt để
 * ván không kẹt.
 *
 * <p>Thuần JUnit + AssertJ + Mockito, không Spring context, không database.
 */
class StarAndSkipTest {
  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, false, 5L);

  /** key -> player, đóng vai "database" cho {@code resolvePlayer}. */
  private final Map<String, Player> byKey = new HashMap<>();

  /** TurnCoordinator với xúc xắc CỐ ĐỊNH để test không phụ thuộc ngẫu nhiên. */
  private static final class FixedDiceTurns extends TurnCoordinator {
    private final int diceValue;

    FixedDiceTurns(GameRulesConfig rules, int diceValue) {
      super(rules, (room, player, now) -> false, new CardEffectRegistry());
      this.diceValue = diceValue;
    }

    @Override
    int rollValue() {
      return diceValue;
    }
  }

  private TurnCoordinator turns(int diceValue) {
    return new FixedDiceTurns(RULES, diceValue);
  }

  private GameService service() {
    PlayerRepository players = mock(PlayerRepository.class);
    when(players.findByPlayerKey(anyString()))
        .thenAnswer(call -> Optional.ofNullable(byKey.get(call.<String>getArgument(0))));
    when(players.save(any(Player.class))).thenAnswer(call -> call.getArgument(0));
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

  /** Lượt trả lời ở ô KHÔNG phải ô thẻ (ô RIASEC): không có lá để rút. */
  private void openAnswerTurn(Room room, Player player) {
    room.setPendingAnswer(true);
    room.setPendingCardId(null);
    room.beginTurn(player, TurnPhase.WAITING_ANSWER, Instant.now().plusSeconds(60), false);
  }

  /** Lượt trả lời ở ô THẺ: server đã rút sẵn lá {@code cardId} và lưu vào phòng. */
  private void openCardTurn(Room room, Player player, String cardId) {
    room.setPendingAnswer(true);
    room.setPendingCardId(cardId);
    room.beginTurn(player, TurnPhase.WAITING_ANSWER, Instant.now().plusSeconds(60), false);
  }

  private static void assertConflict(Throwable thrown) {
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(((ResponseStatusException) thrown).getReason()).isNotBlank();
  }

  // ── S1: Ô ⭐ ─────────────────────────────────────────────────────────────

  @Test
  void roiVaoOsaoMoLuotTraLoi() {
    // Ô 8 (CHANCE) + xúc xắc 1 ⇒ rơi vào ô 7 = ⭐. Trước đây ô ⭐ KHÔNG mở
    // lượt trả lời ⇒ server không bao giờ biết người chơi dừng ở ô sao ⇒ +1
    // điểm chỉ có ở máy client ⇒ bảng xếp hạng trên server sai.
    Room room = room("p1", player("p1", 5), player("p2", 10));
    Player p1 = room.findPlayer("p1");

    room.beginTurn(p1, TurnPhase.WAITING_ROLL, Instant.now().plusSeconds(60), false);
    turns(2).rollForTurn(room, p1, room.getTurnId(), false, "manual");

    assertThat(p1.getCurrentPosition()).isEqualTo(7);
    assertThat(TurnCoordinator.pathTile(7)).isEqualTo(TileType.STAR);
    // Server mở lượt trả lời cho CHÍNH người vừa rơi vào ô sao.
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.WAITING_ANSWER);
    assertThat(room.getTurnPlayerKey()).isEqualTo("p1");
    assertThat(room.isPendingAnswer()).isTrue();
    // ... và cấp một LÁ GIẢ "SAO" (không phải 1 trong 20 lá Cơ hội/Thử thách).
    assertThat(room.getPendingCardId()).isEqualTo(CardEffectRegistry.STAR_CARD_ID);
  }

  @Test
  void oSaoCongDungMotDiem() {
    // Lá giả SAO: hiệu ứng NONE, 1 điểm — server tự tra bảng, không có chỗ cho
    // client khai điểm.
    Room room = room("p1", player("p1", 7), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    openCardTurn(room, p1, CardEffectRegistry.STAR_CARD_ID);

    BonusEffectResponse res =
        service()
            .applyBonusEffect(
                new BonusEffectRequest("p1", CardEffectRegistry.STAR_CARD_ID, null, null, null));

    assertThat(res.winPoints()).isEqualTo(1);
    assertThat(p1.getWinPoints()).isEqualTo(1);
    // Lượt đã đóng, sang người kế tiếp — ván không kẹt.
    assertThat(room.getTurnPlayerKey()).isEqualTo("p2");
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.WAITING_ROLL);
    assertThat(room.getPendingCardId()).isNull();
  }

  @Test
  void oSaoQuaDuongCuVanCongMotDiem() {
    // Đường CŨ (/game/answer) ĐÃ ĐÓNG khi phòng đang chờ xử lý lá bài ⇒ ô ⭐
    // (lá giả SAO) cũng phải đi qua /api/game/bonus-effect. Điểm ô sao do
    // server quyết, không có đường thứ hai.
    Room room = room("p1", player("p1", 7), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    openCardTurn(room, p1, CardEffectRegistry.STAR_CARD_ID);

    assertThatThrownBy(
            () -> service().answer(new AnswerRequest("p1", "BONUS", "A", "R", null, 0, 999)))
        .satisfies(StarAndSkipTest::assertConflict);

    assertThat(p1.getWinPoints()).isZero();
  }

  @Test
  void bangHieuUngCoLaSao() {
    CardEffectRegistry registry = new CardEffectRegistry();

    CardEffectRegistry.CardEffectSpec spec = registry.effectOf(CardEffectRegistry.STAR_CARD_ID);

    assertThat(spec).isNotNull();
    assertThat(spec.effect().name()).isEqualTo("NONE");
    assertThat(spec.winPoints()).isEqualTo(1);
    assertThat(spec.requiresChoice()).isFalse();
    // Lá giả của ô ⭐ KHÔNG nằm trong 20 lá Cơ hội/Thử thách.
    assertThat(registry.cardIds()).hasSize(20).doesNotContain(CardEffectRegistry.STAR_CARD_ID);
    // Rút theo loại ô: ô ⭐ ⇒ lá giả SAO; ô RIASEC / START ⇒ không có lá.
    assertThat(registry.drawCardFor(TileType.STAR)).isEqualTo(CardEffectRegistry.STAR_CARD_ID);
    assertThat(registry.drawCardFor(TileType.R)).isNull();
    assertThat(registry.drawCardFor(TileType.START)).isNull();
  }

  // ── S2: cờ skipped ───────────────────────────────────────────────────────

  @Test
  void skippedKhongCongDiemVaVanDongLuot() {
    // Nút "Không hoàn thành": KHÔNG áp hiệu ứng, KHÔNG cộng điểm, nhưng VẪN
    // đóng lượt (ván không kẹt) và chuyển sang người kế tiếp.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    openCardTurn(room, p1, "THỬ THÁCH 04");

    BonusEffectResponse res =
        service()
            .applyBonusEffect(new BonusEffectRequest("p1", "THỬ THÁCH 04", null, null, true));

    assertThat(res.effect()).isEqualTo("SKIPPED");
    assertThat(res.winPoints()).isZero();
    assertThat(res.awardedPlayerKeys()).isEmpty();
    assertThat(p1.getWinPoints()).isZero();
    // Lượt ĐÃ ĐÓNG ⇒ không kẹt ván.
    assertThat(room.getTurnPlayerKey()).isEqualTo("p2");
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.WAITING_ROLL);
    assertThat(room.isPendingAnswer()).isFalse();
    assertThat(room.getPendingCardId()).isNull();
  }

  @Test
  void skippedKhongPhaiLaLuanRutThe() {
    // skipped chỉ có ý nghĩa khi lượt RÚT THẺ hợp lệ. Lượt ở ô RIASEC (không
    // có lá) ⇒ 409, không cộng điểm, lượt vẫn treo.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    openAnswerTurn(room, p1);

    assertThatThrownBy(
            () ->
                service()
                    .applyBonusEffect(new BonusEffectRequest("p1", null, null, null, true)))
        .satisfies(StarAndSkipTest::assertConflict);

    assertThat(p1.getWinPoints()).isZero();
  }

  @Test
  void skippedKhongCongTotalAnswered() {
    // Bỏ qua = KHÔNG trả lời ⇒ không được tính vào "số câu đã trả lời"
    // (ngưỡng thắng là totalAnswered >= 12).
    Room room = room("p1", player("p1", 10), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    openCardTurn(room, p1, "THỬ THÁCH 04");

    service()
        .applyBonusEffect(new BonusEffectRequest("p1", "THỬ THÁCH 04", null, null, true));

    assertThat(p1.getTotalAnswered()).isZero();
  }

  @Test
  void skippedKhongPhaiLuotCuaMinh() {
    // Không phải lượt của mình ⇒ 409, không bỏ qua được lượt của người khác.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "THỬ THÁCH 04");

    assertThatThrownBy(
            () ->
                service()
                    .applyBonusEffect(new BonusEffectRequest("p2", "THỬ THÁCH 04", null, null, true)))
        .isInstanceOf(NotYourTurnException.class);

    assertThat(room.findPlayer("p1").getWinPoints()).isZero();
    assertThat(room.findPlayer("p2").getWinPoints()).isZero();
  }

  @Test
  void skippedHaiLanKhongCongDiemHaiLan() {
    // Lượt đã đóng ⇒ gọi lại bị chặn (409) ⇒ không farm được điểm.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    openCardTurn(room, room.findPlayer("p1"), "THỬ THÁCH 04");
    GameService service = service();

    service.applyBonusEffect(new BonusEffectRequest("p1", "THỬ THÁCH 04", null, null, true));

    assertThatThrownBy(
            () ->
                service.applyBonusEffect(
                    new BonusEffectRequest("p1", "THỬ THÁCH 04", null, null, true)))
        .isInstanceOf(TurnBusyException.class);
    assertThat(room.findPlayer("p1").getWinPoints()).isZero();
  }

  @Test
  void skippedKhongPhaiLaHieuUngCuaLa() {
    // skipped = KHÔNG áp hiệu ứng: lá "CƠ HỘI 10" (chọn +3 hoặc đi 2 ô) bị bỏ
    // qua thì KHÔNG được cộng điểm, KHÔNG được di chuyển.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    openCardTurn(room, p1, "CƠ HỘI 10");

    BonusEffectResponse res =
        service()
            .applyBonusEffect(new BonusEffectRequest("p1", "CƠ HỘI 10", "PLUS_3", null, true));

    assertThat(res.effect()).isEqualTo("SKIPPED");
    assertThat(res.winPoints()).isZero();
    assertThat(p1.getWinPoints()).isZero();
    assertThat(p1.getCurrentPosition()).isEqualTo(10);
  }
}
