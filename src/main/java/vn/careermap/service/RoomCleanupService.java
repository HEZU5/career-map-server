package vn.careermap.service;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.repo.RoomRepository;

/**
 * Dọn phòng bỏ hoang.
 *
 * <p>Không có việc này thì lobby lấp đầy phòng chết: tạo phòng rồi đóng tab, phòng
 * đã kết thúc mà ai cũng offline. Chạy định kỳ 10 phút và một lần ngay sau khi
 * khởi động (để dọn nốt đống đã tích tụ trước đó).
 *
 * <p>Luật xoá — cố ý KHÔNG xoá phòng đang có người thật, kể cả người đang tạm
 * thoát (F5, đóng tab):
 *
 * <ul>
 *   <li>không còn ai;
 *   <li>còn người nhưng tất cả đều đã bấm rời phòng;
 *   <li>phòng chờ quá {@link #WAITING_TTL} không ai vào;
 *   <li>phòng đã bắt đầu nhưng không có hoạt động nào quá {@link #STARTED_IDLE_TTL}.
 * </ul>
 */
@Service
public class RoomCleanupService {
  private static final Logger log = LoggerFactory.getLogger(RoomCleanupService.class);

  /** Phòng chờ bỏ trống quá lâu thì bỏ khỏi danh sách công khai và xoá hẳn. */
  public static final Duration WAITING_TTL = Duration.ofHours(2);

  /** Phòng đã chơi mà im lặng quá lâu (ai cũng đóng tab) thì xoá. */
  public static final Duration STARTED_IDLE_TTL = Duration.ofHours(3);

  private final RoomRepository roomRepository;

  public RoomCleanupService(RoomRepository roomRepository) {
    this.roomRepository = roomRepository;
  }

  @Scheduled(initialDelay = 20_000, fixedDelay = 600_000)
  @Transactional
  public void purgeStaleRooms() {
    Instant now = Instant.now();
    int removed = 0;
    for (Room room : roomRepository.findAll()) {
      if (isStale(room, now)) {
        log.info("Xoá phòng bỏ hoang {} (đã bắt đầu={})", room.getCode(), room.isActive());
        roomRepository.delete(room);
        removed++;
      }
    }
    if (removed > 0) {
      log.info("Đã xoá {} phòng bỏ hoang", removed);
    }
  }

  /** Phòng này có chắc chắn bỏ hoang không? Package-private để test gọi thẳng. */
  boolean isStale(Room room, Instant now) {
    if (room.getPlayers().isEmpty()) return true;
    if (room.getPlayers().stream().allMatch(Player::isLeft)) return true;
    Duration ttl = room.isActive() ? STARTED_IDLE_TTL : WAITING_TTL;
    return room.lastActivityAt().isBefore(now.minus(ttl));
  }
}