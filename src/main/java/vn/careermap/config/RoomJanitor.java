package vn.careermap.config;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.service.GameBroadcaster;
import vn.careermap.service.PresenceService;
import vn.careermap.service.RoomQueryService;

/**
 * Dọn phòng theo vòng đời, thay cho luật "xoá sau 2/3 giờ" cũ.
 *
 * <p>Luật mới chỉ có ba đường xóa:
 *
 * <ol>
 *   <li>Chủ phòng bấm đóng phòng → xóa ngay (xử lý ngay trong
 *       {@code RoomService}, không thuộc file này).
 *   <li>Ván đã kết thúc → giữ {@code FINISHED_ROOM_TTL_MIN} cho bảng kết quả.
 *   <li>Không còn ai online quá {@code EMPTY_ROOM_TTL_MIN} → dọn.
 * </ol>
 *
 * <p>Không còn quy tắc "phòng chờ quá 2 giờ" và "trận quá 3 giờ": một phòng có
 * người đang chơi thì phải sống cho tới khi họ chủ động rời — xóa giữa trận là
 * mất ván của người đang chơi.
 */
@Component
public class RoomJanitor {
  private static final Logger log = LoggerFactory.getLogger(RoomJanitor.class);

  private final RoomQueryService rooms;
  private final PresenceService presence;
  private final GameRulesConfig rules;
  private final GameBroadcaster broadcaster;

  public RoomJanitor(
      RoomQueryService rooms,
      PresenceService presence,
      GameRulesConfig rules,
      GameBroadcaster broadcaster) {
    this.rooms = rooms;
    this.presence = presence;
    this.rules = rules;
    this.broadcaster = broadcaster;
  }

  @Transactional
  public int sweep() {
    Instant now = Instant.now();
    int deleted = 0;
    deleted += deleteFinished(now);
    deleted += deleteEmpty(now);
    if (deleted > 0) {
      log.info("[JANITOR] đã xoá {} phòng", deleted);
    }
    return deleted;
  }

  /** Ván đã xong: giữ đủ lâu cho người chơi xem bảng kết quả rồi mới dọn. */
  @Transactional
  public int deleteFinished(Instant now) {
    Instant cutoff =
        now.minus(Duration.ofMinutes(rules.finishedRoomTtlMin));
    List<Room> done = rooms.finishedRooms();
    int count = 0;
    for (Room room : done) {
      Instant finishedAt = room.getFinishedAt();
      if (finishedAt == null || finishedAt.isAfter(cutoff)) continue;
      // Vẫn còn người online xem bảng kết quả thì chưa động vào.
      if (presence.onlineCount(room, now) > 0) continue;
      delete(room, "finished-ttl");
      count++;
    }
    return count;
  }

  /**
   * Phòng không còn ai online. Mốc tính từ {@code offlineSince} (thời điểm server
   * ghi nhận mất kết nối) chứ không phải {@code lastActivityAt}: nếu dùng
   * lastActivity, phòng đã đầy người rồi cả nhóm cùng đi ăn tối sẽ bị xóa ngay
   * lúc bước ra khỏi phòng.
   */
  @Transactional
  public int deleteEmpty(Instant now) {
    Instant cutoff = now.minus(Duration.ofMinutes(rules.emptyRoomTtlMin));
    int count = 0;
    for (Room room : rooms.activeRooms()) {
      if (presence.onlineCount(room, now) > 0) continue;
      // Mốc "phòng trống từ bao giờ": lúc NGƯỜI CUỐI CÙNG rời (offlineSince mới
      // nhất trong phòng). lastActivityAt chỉ là fallback cho phòng cũ chưa từng
      // có presence — nếu dùng nó cho phòng mới, cả nhóm cùng đi ăn tối sẽ làm
      // phòng bị xóa ngay ngày ra khỏi phòng.
      Instant emptySince = lastOffline(room);
      if (emptySince == null) {
        emptySince = room.lastActivityAt();
      }
      if (emptySince.isAfter(cutoff)) continue;
      // Phòng đã kết thúc xử lý ở deleteFinished theo mốc riêng.
      if (room.isFinished()) continue;
      delete(room, "empty-ttl");
      count++;
    }
    return count;
  }

  /** Thời điểm NGƯỜI CUỐI CÙNG trong phòng rời mạng: offlineSince mới nhất. */
  private static Instant lastOffline(Room room) {
    Instant last = null;
    for (Player player : room.getPlayers()) {
      Instant offlineSince = player.getOfflineSince();
      if (offlineSince != null && (last == null || offlineSince.isAfter(last))) {
        last = offlineSince;
      }
    }
    return last;
  }

  /**
   * Xóa phòng và báo các máy còn lại.
   *
   * <p>Đánh dấu {@code left} cho toàn bộ người chơi TRƯỚC khi xóa: nếu ai đó đang
   * giữ màn hình kết quả và vừa bấm thoát ở phía client, họ sẽ quay lại lobby
   * và gặp màn "không tìm thấy phòng" thay vì bảng kết quả — nên chỉ xóa khi đã
   * hết hạn, còn thao tác chủ động thì đi qua {@code RoomService}.
   */
  private void delete(Room room, String reason) {
    log.info("[JANITOR] xoá phòng {} — lý do: {}", room.getCode(), reason);
    broadcaster.roomClosed(room.getCode(), reason);
    room.getPlayers().forEach(p -> p.setLeft(true));
    rooms.delete(room);
  }
}