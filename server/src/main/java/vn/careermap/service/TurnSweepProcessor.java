package vn.careermap.service;

import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.domain.TurnPhase;
import vn.careermap.web.dto.GameSnapshot;

/**
 * Xử lý lượt của MỘT phòng — mỗi phòng một transaction RIÊNG.
 *
 * <p>Vì sao tách khỏi {@link TurnScheduler}: một nhịp quét có thể chạm vào hàng
 * chục phòng. Nếu CẢ nhịp quét nằm trong một transaction thì một phòng gặp
 * xung đột optimistic-lock sẽ rollback LUÔN cả các phòng đã xử lý trước đó —
 * blast radius là toàn bộ nhịp quét chứ không chỉ phòng hỏng. Ở đây mỗi phòng
 * được xử lý trong transaction riêng ({@link Propagation#REQUIRES_NEW}) nên xung
 * đột của một phòng chỉ hủy chính phòng đó.
 *
 * <p>QUAN TRỌNG — phải NẠP LẠI phòng trong transaction của chính nó: entity
 * nạp ở transaction ngoài không thuộc persistence context của transaction này
 * nên thay đổi trên nó KHÔNG được flush ⇒ lỗi optimistic-lock chỉ nổ ra lúc
 * commit của transaction ngoài ⇒ quay lại đúng cái bệnh "một transaction cho
 * cả nhịp quét". Vì vậy mỗi method ở đây nhận MÃ phòng và tự nạp lại qua
 * {@link RoomQueryService#findByCode(String)}.
 *
 * <p>Thuần JUnit + Mockito (không Spring context, không database) — xem
 * {@code TurnSweepIsolationTest}.
 */
@Component
public class TurnSweepProcessor {

  /** Chờ thêm một nhịp quét sau khi hết hạn, để ưu tiên lần tự tung phía client. */
  static final long SWEEP_DELAY_MS = 2000;

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
   * {@code TurnSweepProcessor}.
   */
  public TurnSweepProcessor(
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
   * Xử lý lượt đã quá hạn của MỘT phòng.
   *
   * @param roomCode mã phòng (phòng được nạp lại trong transaction này).
   * @param now mốc thời gian của nhịp quét.
   * @return {@code true} nếu phòng này đã được xử lý (có thay đổi trạng thái).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean sweepExpiredRoom(String roomCode, Instant now) {
    Room room = rooms.findByCode(roomCode).orElse(null);
    if (room == null) return false;
    if (room.getTurnPhase() == TurnPhase.NONE) return false;
    // Bỏ qua phòng không ai online: đồng hồ đã được dừng bởi PresenceService,
    // không có ai chơi thì tự tung cũng vô nghĩa.
    if (rooms.onlineCount(room, now) == 0) return false;
    if (!room.isDeadlinePassed(now.plusMillis(SWEEP_DELAY_MS))) return false;

    Player player = room.turnPlayer();
    if (player == null) return false;

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
      return true;
    }
    if (roll != null || room.getTurnPhase() == TurnPhase.NONE) {
      broadcaster.gameUpdated(room.getCode(), snapshotBuilder.build(room));
      return true;
    }
    return false;
  }

  /**
   * Bỏ qua lượt của người đã vắng mặt quá {@code AWAY_AFTER_S} — MỘT phòng.
   *
   * <p>Tách khỏi {@link #sweepExpiredRoom(String, Instant)} vì điều kiện khác:
   * deadline chưa hết nhưng người chơi đã vắng quá lâu thì bỏ luôn, không bắt
   * cả bàn ngồi nhìn.
   *
   * @return {@code true} nếu lượt của phòng này đã bị bỏ qua.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean skipAwayRoom(String roomCode, Instant now) {
    Room room = rooms.findByCode(roomCode).orElse(null);
    if (room == null) return false;
    if (room.getTurnPhase() == TurnPhase.NONE) return false;
    Player player = room.turnPlayer();
    if (player == null) return false;
    if (!rooms.isAway(room, player, now)) return false;
    turns.skipTurn(room, player, now);
    broadcaster.gameUpdated(room.getCode(), snapshotBuilder.build(room));
    return true;
  }
}
