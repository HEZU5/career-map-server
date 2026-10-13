package vn.careermap.service;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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
 * <p>Có độ trễ cố ý ({@link #SWEEP_DELAY_MS}) để không cạnh tranh với lần tự tung
 * phía client, giảm tải request không cần thiết.
 */
@Component
public class TurnScheduler {

  private static final Logger log = LoggerFactory.getLogger(TurnScheduler.class);

  /** Chờ thêm một nhịp quét sau khi hết hạn, để ưu tiên lần tự tung phía client. */
  private static final long SWEEP_DELAY_MS = 2000;

  private final RoomQueryService rooms;
  private final TurnCoordinator turns;
  private final GameBroadcaster broadcaster;
  private final GameSnapshotBuilder snapshotBuilder;

  public TurnScheduler(
      RoomQueryService rooms,
      TurnCoordinator turns,
      GameBroadcaster broadcaster,
      GameSnapshotBuilder snapshotBuilder) {
    this.rooms = rooms;
    this.turns = turns;
    this.broadcaster = broadcaster;
    this.snapshotBuilder = snapshotBuilder;
  }

  /** @return số lượt đã được xử lý. */
  public int sweepExpiredTurns() {
    Instant now = Instant.now();
    int handled = 0;
    for (Room room : rooms.activeRooms()) {
      if (room.getTurnPhase() == TurnPhase.NONE) continue;
      // Bỏ qua phòng không ai online: đồng hồ đã được dừng bởi PresenceService,
      // không có ai chơi thì tự tung cũng vô nghĩa.
      if (rooms.onlineCount(room, now) == 0) continue;
      if (!room.isDeadlinePassed(now.plusMillis(SWEEP_DELAY_MS))) continue;

      Player player = room.turnPlayer();
      if (player == null) continue;

      // Đã có câu trả lời trong lúc chờ quét → không đụng nữa.
      String reason =
          room.getTurnPhase() == TurnPhase.WAITING_ROLL ? "sweep-timeout" : "sweep-answer-timeout";
      TurnCoordinator.TurnRoll roll = turns.expireTurn(room, player, reason);
      if (roll != null || room.getTurnPhase() == TurnPhase.NONE) {
        broadcaster.gameUpdated(room.getCode(), snapshotBuilder.build(room));
        handled++;
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
  public int skipAwayTurns() {
    Instant now = Instant.now();
    int skipped = 0;
    for (Room room : rooms.activeRooms()) {
      if (room.getTurnPhase() == TurnPhase.NONE) continue;
      Player player = room.turnPlayer();
      if (player == null) continue;
      if (rooms.isAway(room, player, now)) {
        turns.skipTurn(room, player, now);
        broadcaster.gameUpdated(room.getCode(), snapshotBuilder.build(room));
        skipped++;
      }
    }
    if (skipped > 0) {
      log.info("[AWAY] da bo qua {} luot", skipped);
    }
    return skipped;
  }

  List<String> activeCodes() {
    return rooms.activeRooms().stream().map(Room::getCode).toList();
  }
}