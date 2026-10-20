package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.domain.RoomStatus;
import vn.careermap.domain.TurnPhase;
import vn.careermap.repo.PlayerRepository;

/**
 * Một phòng lỗi KHÔNG được làm hỏng các phòng khác trong cùng nhịp quét.
 *
 * <p>Trước đây cả nhịp quét nằm trong MỘT transaction: một phòng gặp xung đột
 * optimistic-lock sẽ rollback LUÔN mọi phòng đã xử lý trước đó trong nhịp đó
 * (reviewer đo được cả hai phòng vẫn {@code PLAYING} sau một lần xung đột).
 *
 * <p>Nay mỗi phòng được xử lý trong transaction riêng
 * ({@code TurnSweepProcessor} — {@code REQUIRES_NEW}) và lỗi của một phòng được
 * bắt riêng trong vòng lặp của {@link TurnScheduler}.
 *
 * <p>Thuần JUnit + Mockito, không Spring context, không database.
 */
class TurnSweepIsolationTest {

  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, false, 5L);

  /** Phòng đang chờ tung nhưng đồng hồ ĐÃ HẾT HẠN. */
  private Room room(String code) {
    Room room = Room.create(code, "Chủ phòng", false, "owner-" + code);
    room.setActive(true);
    room.setStatus(RoomStatus.PLAYING);
    Player p = Player.create("owner-" + code, "Chủ phòng");
    room.addPlayer(p);
    room.beginTurn(p, TurnPhase.WAITING_ROLL, Instant.now().minusSeconds(30), false);
    return room;
  }

  private TurnScheduler scheduler(RoomQueryService rooms, TurnSweepProcessor processor) {
    return new TurnScheduler(rooms, processor);
  }

  // ── Blast radius ──────────────────────────────────────────────────────────

  @Test
  void motPhongXungDotKhongLamHongPhongKhacKhiQuetHetHan() {
    RoomQueryService rooms = mock(RoomQueryService.class);
    when(rooms.activeRooms()).thenReturn(List.of(room("H1"), room("H2")));
    TurnSweepProcessor processor = mock(TurnSweepProcessor.class);
    when(processor.sweepExpiredRoom(eq("H1"), any()))
        .thenThrow(new ObjectOptimisticLockingFailureException(Room.class, "H1"));
    when(processor.sweepExpiredRoom(eq("H2"), any())).thenReturn(true);

    int handled = scheduler(rooms, processor).sweepExpiredTurns();

    // Phòng H1 hỏng KHÔNG được nuốt phòng H2.
    assertThat(handled).isEqualTo(1);
    verify(processor).sweepExpiredRoom(eq("H2"), any());
  }

  @Test
  void motPhongXungDotKhongLamHongPhongKhacKhiBoQuaLuotVang() {
    RoomQueryService rooms = mock(RoomQueryService.class);
    when(rooms.activeRooms()).thenReturn(List.of(room("H1"), room("H2")));
    when(rooms.isAway(any(), any(), any())).thenReturn(true);
    TurnSweepProcessor processor = mock(TurnSweepProcessor.class);
    when(processor.skipAwayRoom(eq("H1"), any()))
        .thenThrow(new ObjectOptimisticLockingFailureException(Room.class, "H1"));
    when(processor.skipAwayRoom(eq("H2"), any())).thenReturn(true);

    int skipped = scheduler(rooms, processor).skipAwayTurns();

    assertThat(skipped).isEqualTo(1);
    verify(processor).skipAwayRoom(eq("H2"), any());
  }

  @Test
  void moiPhongDangChoiDauDuocGoiXuong() {
    RoomQueryService rooms = mock(RoomQueryService.class);
    when(rooms.activeRooms()).thenReturn(List.of(room("H1"), room("H2")));
    TurnSweepProcessor processor = mock(TurnSweepProcessor.class);

    scheduler(rooms, processor).sweepExpiredTurns();

    verify(processor).sweepExpiredRoom(eq("H1"), any());
    verify(processor).sweepExpiredRoom(eq("H2"), any());
  }

  // ── Phòng phải được NẠP LẠI trong transaction của chính nó ───────────────

  @Test
  void processorNapLaiPhongTrongTransactionCuaChinhNo() {
    Room stale = room("H1"); // phòng ở transaction ngoài
    Room fresh = room("H1"); // phòng nạp lại trong transaction của phòng

    RoomQueryService rooms = mock(RoomQueryService.class);
    when(rooms.findByCode("H1")).thenReturn(Optional.of(fresh));
    when(rooms.onlineCount(any(), any())).thenReturn(1);
    TurnCoordinator turns = mock(TurnCoordinator.class);
    when(turns.expireTurn(any(), any(), anyString()))
        .thenReturn(new TurnCoordinator.TurnRoll(3, 0, true, true, "sweep-timeout"));
    GameBroadcaster broadcaster = mock(GameBroadcaster.class);
    PlayerRepository players = mock(PlayerRepository.class);
    GameService gameService =
        new GameService(
            players,
            broadcaster,
            new GameSnapshotBuilder(RULES),
            turns,
            RULES,
            new CardEffectRegistry());

    TurnSweepProcessor processor =
        new TurnSweepProcessor(
            rooms, turns, broadcaster, new GameSnapshotBuilder(RULES), gameService);
    processor.sweepExpiredRoom("H1", Instant.now());

    // Phải xử lý trên entity NẠP LẠI — entity của transaction ngoài không thuộc
    // persistence context nên thay đổi trên nó sẽ không được flush.
    verify(turns).expireTurn(same(fresh), any(), anyString());
    assertThat(stale.getTurnPhase()).isEqualTo(TurnPhase.WAITING_ROLL);
  }

  @Test
  void processorTraFalseKhiPhongKhongConTonTai() {
    RoomQueryService rooms = mock(RoomQueryService.class);
    when(rooms.findByCode("H1")).thenReturn(Optional.empty());
    TurnCoordinator turns = mock(TurnCoordinator.class);
    GameBroadcaster broadcaster = mock(GameBroadcaster.class);
    PlayerRepository players = mock(PlayerRepository.class);
    GameService gameService =
        new GameService(
            players,
            broadcaster,
            new GameSnapshotBuilder(RULES),
            turns,
            RULES,
            new CardEffectRegistry());

    TurnSweepProcessor processor =
        new TurnSweepProcessor(
            rooms, turns, broadcaster, new GameSnapshotBuilder(RULES), gameService);

    assertThat(processor.sweepExpiredRoom("H1", Instant.now())).isFalse();
    assertThat(processor.skipAwayRoom("H1", Instant.now())).isFalse();
  }
}
