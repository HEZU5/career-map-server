package vn.careermap.service;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.domain.Player;
import vn.careermap.domain.PresenceStatus;
import vn.careermap.domain.Room;
import vn.careermap.repo.RoomRepository;

/**
 * Truy vấn phòng cho các tác vụ nền (quét đồng hồ, quét presence, dọn phòng).
 *
 * <p>Tách riêng khỏi {@link PresenceService} để các scheduler không phụ thuộc
 * chằng chịt vào broadcaster — tránh vòng phụ thuộc vòng (PresenceService cần
 * broadcaster để báo trạng thái, scheduler thì không).
 */
@Service
public class RoomQueryService {
  private final RoomRepository roomRepository;
  private final PresenceService presence;
  private final GameRulesConfig rules;

  public RoomQueryService(
      RoomRepository roomRepository, PresenceService presence, GameRulesConfig rules) {
    this.roomRepository = roomRepository;
    this.presence = presence;
    this.rules = rules;
  }

  @Transactional(readOnly = true)
  public List<Room> activeRooms() {
    return roomRepository.findByActiveTrue();
  }

  @Transactional(readOnly = true)
  public List<Room> finishedRooms() {
    return roomRepository.findByStatus(vn.careermap.domain.RoomStatus.FINISHED);
  }

  @Transactional
  public void delete(Room room) {
    roomRepository.delete(room);
  }

  @Transactional(readOnly = true)
  public int onlineCount(Room room, Instant now) {
    return presence.onlineCount(room, now);
  }

  @Transactional(readOnly = true)
  public boolean isAway(Room room, Player player, Instant now) {
    return player.isAway(room, now, rules.awayAfterS);
  }

  @Transactional(readOnly = true)
  public PresenceStatus presenceOf(Room room, Player player, Instant now) {
    return presence.statusOf(room, player, now);
  }
}