package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.domain.RoomStatus;
import vn.careermap.domain.TileType;
import vn.careermap.domain.TurnPhase;
import vn.careermap.repo.PlayerRepository;
import vn.careermap.web.dto.DiceResponse;
import vn.careermap.web.dto.RollRequest;

/**
 * SERVER tự RÚT LÁ khi người chơi rơi vào ô thẻ — nguồn sự thật về "lá nào".
 *
 * <p>Trước đây client tự rút lá ở máy rồi tự khai {@code cardId} lên server ⇒
 * người chơi tự chọn được lá đẹp nhất (MOVE_3, EXTRA_ROLL miễn phí). Nay:
 *
 * <ol>
 *   <li>Server rút lá ĐÚNG LÚC người chơi rơi vào ô CƠ HỘI / THỬ THÁCH.
 *   <li>Lá được lưu vào phòng ({@code Room#getPendingCardId()}) cho tới khi
 *       lượt đóng.
 *   <li>Bản thân bản đồ của server phải KHỚP bản của client, vì server căn cứ
 *       loại ô để quyết định rút lá CƠ HỘI hay THỬ THÁCH.
 * </ol>
 *
 * <p>Thuần JUnit + AssertJ + Mockito, không Spring context, không database.
 */
class ServerCardDrawTest {
  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, false, 5L);

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

  private GameService serviceWithDice(int diceValue) {
    PlayerRepository players = mock(PlayerRepository.class);
    when(players.findByPlayerKey(anyString()))
        .thenAnswer(call -> Optional.ofNullable(byKey.get(call.<String>getArgument(0))));
    when(players.save(any(Player.class))).thenAnswer(call -> call.getArgument(0));
    return new GameService(
        players,
        mock(GameBroadcaster.class),
        new GameSnapshotBuilder(RULES),
        turns(diceValue),
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

  /** Tung xúc xắc với giá trị cố định ⇒ biết chắc rơi vào ô nào. */
  private void rollTo(Room room, Player roller, int diceValue) {
    room.beginTurn(roller, TurnPhase.WAITING_ROLL, Instant.now().plusSeconds(60), false);
    turns(diceValue).rollForTurn(room, roller, room.getTurnId(), false, "manual");
  }

  @Test
  void roiVaoOCoHoiThiServerRutLaCoHoi() {
    // Ô 10 (E) + xúc xắc 1 ⇒ rơi vào ô 9 = CHANCE.
    Room room = room("p1", player("p1", 8), player("p2", 10));
    Player p1 = room.findPlayer("p1");

    rollTo(room, p1, 1);

    assertThat(p1.getCurrentPosition()).isEqualTo(9);
    assertThat(room.getPendingCardId()).isNotNull().startsWith(CardEffectRegistry.CHANCE_PREFIX);
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.WAITING_ANSWER);
    assertThat(room.getPendingCardId())
        .isIn(new CardEffectRegistry().chanceCardIds().toArray(String[]::new));
  }

  @Test
  void roiVaoOThuThachThiServerRutLaThuThach() {
    // Ô 4 (I) + xúc xắc 1 ⇒ rơi vào ô 3 = CHALLENGE.
    Room room = room("p1", player("p1", 2), player("p2", 10));
    Player p1 = room.findPlayer("p1");

    rollTo(room, p1, 1);

    assertThat(p1.getCurrentPosition()).isEqualTo(3);
    assertThat(room.getPendingCardId())
        .isNotNull()
        .startsWith(CardEffectRegistry.CHALLENGE_PREFIX);
    assertThat(room.getPendingCardId())
        .isIn(new CardEffectRegistry().challengeCardIds().toArray(String[]::new));
  }

  @Test
  void roiVaoORiasecThiKhongRutLa() {
    // Ô 14 (CHANCE) + xúc xắc 1 ⇒ rơi vào ô 13 = ô RIASEC ⇒ có lượt trả lời
    // nhưng KHÔNG có lá bài.
    Room room = room("p1", player("p1", 12), player("p2", 10));
    Player p1 = room.findPlayer("p1");

    rollTo(room, p1, 1);

    assertThat(p1.getCurrentPosition()).isEqualTo(13);
    assertThat(TurnCoordinator.pathTile(13)).isEqualTo(TileType.C);
    assertThat(room.getPendingCardId()).isNull();
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.WAITING_ANSWER);
  }

  @Test
  void roiVaoOsaoMoLuotTraLoi() {
    // Ô 8 (CHANCE) + xúc xắc 1 ⇒ rơi vào ô 7 = ⭐ ⇒ server MỞ lượt trả lời và
    // cấp lá GIẢ "SAO" (+1 điểm). Trước đây ô ⭐ không mở lượt trả lời ⇒
    // server không biết người chơi dừng ở ô sao ⇒ client tự cộng +1 ở máy ⇒
    // bảng xếp hạng trên server sai.
    Room room = room("p1", player("p1", 6), player("p2", 10));
    Player p1 = room.findPlayer("p1");

    rollTo(room, p1, 1);

    assertThat(p1.getCurrentPosition()).isEqualTo(7);
    assertThat(TurnCoordinator.pathTile(7)).isEqualTo(TileType.STAR);
    assertThat(room.getPendingCardId()).isEqualTo(CardEffectRegistry.STAR_CARD_ID);
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.WAITING_ANSWER);
    assertThat(room.getTurnPlayerKey()).isEqualTo("p1");
    assertThat(room.isPendingAnswer()).isTrue();
  }

  @Test
  void hetGioTraLoiThiXoaLaDaRut() {
    // Lá đã rút mà hết giờ thì phải bị xoá — không được dùng lại ở lượt sau.
    Room room = room("p1", player("p1", 10), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    rollTo(room, p1, 1);
    assertThat(room.getPendingCardId()).isNotNull();

    turns(1).expireTurn(room, p1, "test");

    assertThat(room.getPendingCardId()).isNull();
  }

  @Test
  void traLoiXongThiXoaLaDaRut() {
    Room room = room("p1", player("p1", 10), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    rollTo(room, p1, 1);
    assertThat(room.getPendingCardId()).isNotNull();

    room.setPendingAnswer(false);
    room.setPendingCardId(null);
    room.clearTurn();
    room.advanceTurn();

    assertThat(room.getPendingCardId()).isNull();
  }

  @Test
  void rollDiceTraVeCardIdDeClientHienThi() {
    // Client cần biết server cấp lá nào ⇒ DiceResponse phải mang cardId.
    Room room = room("p1", player("p1", 8), player("p2", 10));
    Player p1 = room.findPlayer("p1");
    room.beginTurn(p1, TurnPhase.WAITING_ROLL, Instant.now().plusSeconds(60), false);

    DiceResponse res = serviceWithDice(1).rollDice(RollRequest.manual("p1"));

    assertThat(res.cardId()).isEqualTo(room.getPendingCardId());
    assertThat(res.cardId()).isNotNull().startsWith(CardEffectRegistry.CHANCE_PREFIX);
  }

  @Test
  void laRutLuonThuocDungBoLa() {
    CardEffectRegistry registry = new CardEffectRegistry();

    for (int i = 0; i < 100; i++) {
      assertThat(registry.drawCardFor(TileType.CHANCE))
          .isIn(registry.chanceCardIds().toArray(String[]::new));
      assertThat(registry.drawCardFor(TileType.CHALLENGE))
          .isIn(registry.challengeCardIds().toArray(String[]::new));
      // Ô ⭐ ⇒ lá GIẢ "SAO" (không thuộc bộ Cơ hội/Thử thách).
      assertThat(registry.drawCardFor(TileType.STAR)).isEqualTo(CardEffectRegistry.STAR_CARD_ID);
      assertThat(registry.drawCardFor(TileType.START)).isNull();
      assertThat(registry.drawCardFor(TileType.R)).isNull();
    }
  }

  @Test
  void boLaCoDung10LaCoiHoiVa10LaThuThach() {
    // BÀN có 10 ô CƠ HỘI và 7 ô THỬ THÁCH, nhưng BỘ LÁ có 10 lá mỗi loại:
    // mỗi ô THỬ THÁCH rút từ bộ 10 lá THỬ THÁCH.
    CardEffectRegistry registry = new CardEffectRegistry();

    assertThat(registry.chanceCardIds()).hasSize(10);
    assertThat(registry.challengeCardIds()).hasSize(10);
    assertThat(registry.chanceCardIds())
        .allMatch(id -> id.startsWith(CardEffectRegistry.CHANCE_PREFIX));
    assertThat(registry.challengeCardIds())
        .allMatch(id -> id.startsWith(CardEffectRegistry.CHALLENGE_PREFIX));
  }

  /**
   * Bản đồ server PHẢI khớp bản của client ({@code
   * lib/features/game/domain/board.dart}) — server căn cứ loại ô để rút lá
   * CƠ HỘI hay THỬ THÁCH, lệch một ô là cấp sai loại lá cho người chơi.
   */
  @Test
  void banServerKhopBanClient() {
    List<Integer> chance = new ArrayList<>();
    List<Integer> challenge = new ArrayList<>();
    List<Integer> star = new ArrayList<>();
    List<Integer> riasec = new ArrayList<>();
    for (int i = 0; i < TurnCoordinator.pathSize(); i++) {
      switch (TurnCoordinator.pathTile(i)) {
        case CHANCE -> chance.add(i);
        case CHALLENGE -> challenge.add(i);
        case STAR -> star.add(i);
        case START -> assertThat(i).isZero();
        default -> riasec.add(i);
      }
    }

    assertThat(chance)
        .containsExactly(1, 5, 9, 14, 22, 30, 35, 39, 44, 52)
        .as("ô CƠ HỘI phải khớp bản client");
    assertThat(challenge)
        .containsExactly(3, 12, 18, 26, 33, 42, 48)
        .as("ô THỬ THÁCH phải khớp bản client");
    assertThat(star)
        .containsExactly(7, 11, 16, 20, 24, 28, 32, 37, 41, 46, 50)
        .as("ô ⭐ phải khớp bản client");
    assertThat(riasec)
        .containsExactly(
            2, 4, 6, 8, 10, 13, 15, 17, 19, 21, 23, 25, 27, 29, 31, 34, 36, 38, 40, 43, 45, 47, 49,
            51)
        .as("ô RIASEC phải khớp bản client");
  }
}
