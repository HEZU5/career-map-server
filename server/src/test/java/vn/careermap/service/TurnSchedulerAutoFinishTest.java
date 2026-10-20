package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.domain.RoomStatus;
import vn.careermap.domain.TurnPhase;
import vn.careermap.repo.PlayerRepository;
import vn.careermap.web.dto.GameSnapshot;

/**
 * SERVER tự tung (đồng hồ hết hạn) cũng phải áp luật tự kết thúc.
 *
 * <p>Trước đây {@code TurnScheduler.sweepExpiredTurns()} gọi thẳng
 * {@code TurnCoordinator.expireTurn} nên LUẬT TỰ KẾT THÚC KHÔNG CHẠY: khi
 * client offline/lag, đồng hồ server tự tung, người chơi đi qua ô START đủ điều
 * kiện mà ván vẫn cứ tiếp tục — không kết thúc, không được cộng điểm.
 *
 * <p>Đường sửa: cả hai đường (client tự tung và server tự tung) đi qua cùng
 * một hàm {@code GameService.maybeAutoFinish}.
 *
 * <p>Phần xử lý lượt của từng phòng nằm ở {@link TurnSweepProcessor} (mỗi phòng
 * một transaction riêng — {@code REQUIRES_NEW}); {@link TurnScheduler} chỉ quét
 * danh sách phòng rồi gọi xuống từng phòng.
 *
 * <p>Thuần JUnit + Mockito, không Spring context, không database.
 */
class TurnSchedulerAutoFinishTest {
  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, false, 5L);

  /** key -> player, đóng vai "database" cho {@code resolvePlayer}. */
  private final Map<String, Player> byKey = new HashMap<>();

  private GameBroadcaster broadcaster;

  private GameService gameService(TurnCoordinator turns) {
    PlayerRepository players = mock(PlayerRepository.class);
    when(players.findByPlayerKey(anyString()))
        .thenAnswer(call -> Optional.ofNullable(byKey.get(call.<String>getArgument(0))));
    broadcaster = mock(GameBroadcaster.class);
    return new GameService(
        players, broadcaster, new GameSnapshotBuilder(RULES), turns, RULES, new CardEffectRegistry());
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

  /** Phòng đang chờ tung nhưng đồng hồ ĐÃ HẾT HẠN ⇒ server phải tự tung. */
  private void moLuotHetHan(Room room, Player roller) {
    room.beginTurn(roller, TurnPhase.WAITING_ROLL, Instant.now().minusSeconds(5), false);
  }

  /** Server tự tung và quân vừa đi qua ô START (cộng 1 vòng trước khi trả về). */
  private TurnCoordinator stubServerTungQuaStart() {
    TurnCoordinator turns = mock(TurnCoordinator.class);
    when(turns.expireTurn(any(), any(), anyString()))
        .thenAnswer(
            call -> {
              Player roller = call.getArgument(1);
              roller.setCurrentPosition(0);
              roller.addCompletedLaps(1);
              return new TurnCoordinator.TurnRoll(3, 0, true, true, "sweep-timeout");
            });
    return turns;
  }

  private TurnScheduler scheduler(Room room, TurnCoordinator turns) {
    RoomQueryService rooms = mock(RoomQueryService.class);
    when(rooms.activeRooms()).thenReturn(List.of(room));
    // Mỗi phòng được nạp LẠI trong transaction riêng của nó — xem
    // TurnSweepProcessor (phải nạp lại, không dùng entity của transaction ngoài).
    when(rooms.findByCode(room.getCode())).thenReturn(Optional.of(room));
    when(rooms.onlineCount(any(), any())).thenReturn(1);
    GameService gameService = gameService(turns);
    TurnSweepProcessor processor =
        new TurnSweepProcessor(
            rooms, turns, broadcaster, new GameSnapshotBuilder(RULES), gameService);
    return new TurnScheduler(rooms, processor);
  }

  // ── Server tự tung ────────────────────────────────────────────────────────

  @Test
  void turnSchedulerTuTungQuaStartThiVanKetThuc() {
    Player p = player("owner", 0, 12);
    Room room = room("owner", p);
    moLuotHetHan(room, p);
    TurnCoordinator turns = stubServerTungQuaStart();

    TurnScheduler scheduler = scheduler(room, turns);
    int handled = scheduler.sweepExpiredTurns();

    assertThat(handled).isEqualTo(1);
    assertThat(room.getStatus()).isEqualTo(RoomStatus.FINISHED);
    assertThat(room.getWinnerKey()).isEqualTo("owner");
    assertThat(room.getFinishedAt()).isNotNull();
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.NONE);
    assertThat(p.getWinPoints()).isEqualTo(5);
    verify(broadcaster).gameFinished(eq("H12345"), any(GameSnapshot.class), eq("owner"));
    // finishWithWinner đã bắn FINISHED kèm ảnh chụp đầy đủ — không bắn thêm
    // GAME để tránh hai sự kiện trùng nhau.
    verify(broadcaster, never()).gameUpdated(anyString(), any());
  }

  @Test
  void serverTuTungKhongDuDieuKienThiVanChayTiep() {
    Player p = player("owner", 0, 11); // thiếu 1 câu nữa mới đủ ngưỡng
    Room room = room("owner", p);
    moLuotHetHan(room, p);
    TurnCoordinator turns = stubServerTungQuaStart();

    TurnScheduler scheduler = scheduler(room, turns);
    scheduler.sweepExpiredTurns();

    assertThat(room.getStatus()).isEqualTo(RoomStatus.PLAYING);
    assertThat(room.getWinnerKey()).isNull();
    assertThat(p.getWinPoints()).isEqualTo(0);
    verify(broadcaster).gameUpdated(eq("H12345"), any(GameSnapshot.class));
    verify(broadcaster, never()).gameFinished(anyString(), any(), anyString());
  }

  @Test
  void serverTuTungLượtXepThứTựKhongKetThucVan() {
    // Lượt xếp thứ tự đầu ván: quân KHÔNG di chuyển (moved=false) ⇒ không thể
    // vừa đi qua ô START ⇒ không được kết thúc ván.
    Player p = player("owner", 0, 12);
    Room room = room("owner", p);
    moLuotHetHan(room, p);
    TurnCoordinator turns = mock(TurnCoordinator.class);
    when(turns.expireTurn(any(), any(), anyString()))
        .thenReturn(new TurnCoordinator.TurnRoll(3, 0, false, true, "sweep-timeout"));

    TurnScheduler scheduler = scheduler(room, turns);
    scheduler.sweepExpiredTurns();

    assertThat(room.getStatus()).isEqualTo(RoomStatus.PLAYING);
    assertThat(p.getWinPoints()).isEqualTo(0);
    verify(broadcaster, never()).gameFinished(anyString(), any(), anyString());
  }
}
