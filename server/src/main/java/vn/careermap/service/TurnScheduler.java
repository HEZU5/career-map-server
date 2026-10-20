package vn.careermap.service;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.domain.TurnPhase;
import vn.careermap.web.dto.GameSnapshot;

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
 *
 * <p>Lượt do server tự tung PHẢI đi qua cùng luật với lượt do client tự tung:
 * {@link GameService#maybeAutoFinish(Room, Player, boolean)}. Trước đây đường
 * này gọi thẳng {@code TurnCoordinator.expireTurn} nên luật tự kết thúc khi đi
 * qua ô START không bao giờ chạy.
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
  private final GameService gameService;

  /**
   * {@code GameService} được inject vào đây để ĐƯỜNG SERVER TỰ TUNG dùng chung
   * luật tự kết thúc với đường người chơi tự tung ({@code GameService.rollDice}).
   *
   * <p>Không tạo vòng phụ thuộc: {@code GameService} không inject
   * {@code TurnScheduler}.
   */
  public TurnScheduler(
      RoomQueryService rooms,
      TurnCoordinator turns,
      GameBroadcaster broadcaster,
      GameSnapshotBuilder snapshotBuilder,
      GameService gameService) {
    this.rooms = rooms;
    this.turns = turns;
    this.broadcaster = broadcaster;
    this.snapshotBuilder = snapshotBuilder;
    this.gameService = gameService;
  }

  /**
   * Quét và xử lý các lượt đã quá hạn.
   *
   * <p>Có transaction vì các thay đổi trên {@code Room} (lượt mới, ván kết
   * thúc) PHẢI được ghi xuống DB — nếu không, phòng vẫn giữ lượt cũ đã hết
   * hạn và nhịp quét sau lại xử lý lại lượt đó.
   *
   * @return số lượt đã được xử lý.
   */
  @Transactional
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
      // SERVER tự tung cũng phải áp luật tự kết thúc — CÙNG một đường với
      // GameService.rollDice, nếu không thì người chơi đi qua ô START đủ điều
      // kiện mà ván vẫn cứ tiếp tục. Chỉ chạy khi quân THẬT SỰ di chuyển
      // (lượt xếp thứ tự đầu ván có moved=false).
      GameSnapshot finished =
          gameService.maybeAutoFinish(room, player, roll != null && roll.moved());
      if (finished != null) {
        // finishWithWinner đã bắn FINISHED kèm ảnh chụp đầy đủ — không bắn
        // thêm GAME để tránh hai sự kiện trùng nhau.
        handled++;
      } else if (roll != null || room.getTurnPhase() == TurnPhase.NONE) {
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