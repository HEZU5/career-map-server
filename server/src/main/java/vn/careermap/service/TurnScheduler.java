package vn.careermap.service;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.domain.TurnPhase;

/**
 * Đồng hồ server: quét định kỳ và xử lý mọi lượt đã quá hạn.
 *
 * <p>Đây là nguồn ưu tiên cho mọi lần tự tung. Client và máy trọng tài CŨNG tự
 * tung khi thấy hết giờ, nhưng chỉ để phòng trải nghiệm mượt khi server bận — hai
 * bên đều phải thắng cùng một điều kiện compare-and-set nên không bao giờ xảy ra
 * hai lần tung cho một lượt.
 *
 * <p>Có độ trễ cố ý ({@link TurnSweepProcessor#SWEEP_DELAY_MS}) để không cạnh
 * tranh với lần tự tung phía client, giảm tải request không cần thiết.
 *
 * <p>Lượt do server tự tung PHẢI đi qua cùng luật với lượt do client tự tung:
 * {@link GameService#maybeAutoFinish(Room, Player, boolean)}. Trước đây đường
 * này gọi thẳng {@code TurnCoordinator.expireTurn} nên luật tự kết thúc khi đi
 * qua ô START không bao giờ chạy.
 *
 * <p>Transaction: hai method quét ở đây CÓ {@code @Transactional} — bắt buộc,
 * vì {@code Room.players} là collection LAZY và {@code spring.jpa.open-in-view}
 * = false: đọc nó ngoài transaction sẽ ném {@code LazyInitializationException},
 * làm hỏng cả nhịp quét. Xem {@link TurnSchedulerTransactionalTest}.
 *
 * <p>Nhưng phần GHI của mỗi phòng nằm ở {@link TurnSweepProcessor} với
 * {@code REQUIRES_NEW} — mỗi phòng một transaction riêng, để một phòng gặp
 * xung đột optimistic-lock không làm hỏng các phòng còn lại trong cùng nhịp
 * quét. Ở đây chỉ quét danh sách phòng và gọi xuống từng phòng, mỗi lỗi được
 * bắt riêng.
 */
@Component
public class TurnScheduler {

  private static final Logger log = LoggerFactory.getLogger(TurnScheduler.class);

  private final RoomQueryService rooms;
  private final TurnSweepProcessor processor;

  public TurnScheduler(RoomQueryService rooms, TurnSweepProcessor processor) {
    this.rooms = rooms;
    this.processor = processor;
  }

  /**
   * Quét và xử lý các lượt đã quá hạn.
   *
   * @return số lượt đã được xử lý.
   */
  @Transactional
  public int sweepExpiredTurns() {
    Instant now = Instant.now();
    int handled = 0;
    for (Room room : rooms.activeRooms()) {
      if (room.getTurnPhase() == TurnPhase.NONE) continue;
      if (!room.isDeadlinePassed(now.plusMillis(TurnSweepProcessor.SWEEP_DELAY_MS))) continue;
      try {
        // Mỗi phòng một transaction riêng — xung đột của phòng này không được
        // làm hỏng các phòng đã xử lý trước đó trong cùng nhịp quét.
        if (processor.sweepExpiredRoom(room.getCode(), now)) {
          handled++;
        }
      } catch (Exception e) {
        // Phòng này hỏng (xung đột optimistic-lock, phòng vừa bị xoá...) thì bỏ
        // qua phòng này, KHÔNG bỏ qua các phòng còn lại.
        log.warn("[SWEEP] phong={} loi, bo qua phong nay: {}", room.getCode(), e.getMessage());
      }
    }
    if (handled > 0) {
      log.info("[SWEEP] da xu ly {} luot het han", handled);
    }
    return handled;
  }

  /**
   * Bỏ qua lượt của người đã vắng mặt quá {@code AWAY_AFTER_S}.
   *
   * <p>Tách khỏi {@link #sweepExpiredTurns()} vì điều kiện khác: deadline chưa hết
   * nhưng người chơi đã vắng quá lâu thì bỏ luôn, không bắt cả bàn ngồi nhìn.
   */
  @Transactional
  public int skipAwayTurns() {
    Instant now = Instant.now();
    int skipped = 0;
    for (Room room : rooms.activeRooms()) {
      if (room.getTurnPhase() == TurnPhase.NONE) continue;
      Player player = room.turnPlayer();
      if (player == null) continue;
      if (!rooms.isAway(room, player, now)) continue;
      try {
        if (processor.skipAwayRoom(room.getCode(), now)) {
          skipped++;
        }
      } catch (Exception e) {
        log.warn("[AWAY] phong={} loi, bo qua phong nay: {}", room.getCode(), e.getMessage());
      }
    }
    if (skipped > 0) {
      log.info("[AWAY] da bo qua {} luot", skipped);
    }
    return skipped;
  }
}
