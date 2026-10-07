package vn.careermap.config;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.careermap.service.PresenceService;
import vn.careermap.service.TurnScheduler;

/**
 * Nhịp nền của phòng chơi.
 *
 * <p>Ba việc, ba nhịp khác nhau vì mức độ nhạy cảm khác nhau:
 *
 * <ul>
 *   <li>Presence 2s: cần phản hồi nhanh — người vừa mất mạng phải tối avatar
 *       ngay, và lượt của họ phải được xử lý sớm khi đã vắng quá lâu.
 *   <li>Đồng hồ 1s: quét mốc hết hạn.
 *   <li>Dọn phòng 60s: dọn là việc nền, chập chờn không sao.
 * </ul>
 *
 * <p>Luôn bọc try/catch: một phòng dữ liệu hỏng không được phép làm chết cả
 * luồng đếm của các phòng khác.
 */
@Component
public class GameTickScheduler {
  private final TurnScheduler turnScheduler;
  private final PresenceService presence;
  private final RoomJanitor janitor;

  public GameTickScheduler(
      TurnScheduler turnScheduler, PresenceService presence, RoomJanitor janitor) {
    this.turnScheduler = turnScheduler;
    this.presence = presence;
    this.janitor = janitor;
  }

  @Scheduled(fixedDelayString = "${game.tick-ms:1000}")
  public void tickTurns() {
    try {
      // 1. Trước hết bỏ qua lượt của người đã vắng mặt lâu (không bắt cả bàn
      //    ngồi nhìn), 2. sau đó mới xử lý lượt hết hạn theo deadline.
      turnScheduler.skipAwayTurns();
      turnScheduler.sweepExpiredTurns();
    } catch (Exception e) {
      // Không log stack hết động dài — chỉ gối lỗi để lỗi 1 phòng không lặp vô hạn.
      System.err.println("[tick] sweep turn lỗi: " + e.getMessage());
    }
  }

  @Scheduled(fixedDelayString = "${game.presence-tick-ms:2000}")
  public void tickPresence() {
    try {
      presence.sweep();
    } catch (Exception e) {
      System.err.println("[tick] sweep presence lỗi: " + e.getMessage());
    }
  }

  @Scheduled(fixedDelayString = "${game.janitor-tick-ms:60000}")
  public void tickJanitor() {
    try {
      janitor.sweep();
    } catch (Exception e) {
      System.err.println("[tick] dọn phòng lỗi: " + e.getMessage());
    }
  }
}