package vn.careermap.config;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import vn.careermap.service.PresenceService;
import vn.careermap.service.TurnScheduler;

/**
 * Nhịp quét phải giữ được cả HAI bước khi một bước lỗi.
 *
 * <p>Trước đây {@code tickTurns()} bọc CHUNG một try/catch cho cả hai bước:
 * {@code skipAwayTurns()} ném {@code LazyInitializationException} (đọc
 * collection lazy của {@code Room} ngoài transaction) ⇒ lỗi bị nuốt ⇒
 * {@code sweepExpiredTurns()} — bước QUÉT LƯỢT HẾT HẠN — không bao giờ chạy.
 * Đường server tự tung chết lặng, kể cả luật tự kết thúc ván.
 *
 * <p>Nay mỗi bước có try/catch riêng: lỗi bước 1 không chặn bước 2.
 *
 * <p>Thuần JUnit + Mockito, không Spring context, không database.
 */
class GameTickSchedulerTest {

  @Test
  void tickChayCaHaiBuoc() {
    TurnScheduler turnScheduler = mock(TurnScheduler.class);
    GameTickScheduler tick =
        new GameTickScheduler(
            turnScheduler, mock(PresenceService.class), mock(RoomJanitor.class));

    tick.tickTurns();

    verify(turnScheduler).skipAwayTurns();
    verify(turnScheduler).sweepExpiredTurns();
  }

  @Test
  void loiBuocBoQuaLuotVangKhongLamChetBuocQuetHetHan() {
    TurnScheduler turnScheduler = mock(TurnScheduler.class);
    doThrow(
            new IllegalStateException(
                "failed to lazily initialize a collection of role: Room.players"))
        .when(turnScheduler)
        .skipAwayTurns();
    GameTickScheduler tick =
        new GameTickScheduler(
            turnScheduler, mock(PresenceService.class), mock(RoomJanitor.class));

    tick.tickTurns();

    verify(turnScheduler).sweepExpiredTurns();
  }
}
