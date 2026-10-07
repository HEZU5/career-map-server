package vn.careermap.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.domain.Player;
import vn.careermap.domain.PresenceStatus;
import vn.careermap.domain.PresenceServiceProbe;
import vn.careermap.domain.Room;
import vn.careermap.domain.TurnPhase;
import vn.careermap.repo.RoomRepository;

/**
 * Nhận biết người chơi còn online hay không.
 *
 * <p>Hạ tầng là STOMP WebSocket server→client (không có onDisconnect như Firebase,
 * không có presence sẵn như Supabase), nên dùng heartbeat do CLIENT gửi qua
 * REST và mốc thời gian do SERVER đặt. Client không tự khai "tôi offline".
 *
 * <p>Sweep định kỳ so {@code lastSeen} với {@code OFFLINE_DETECT_S} để ghi
 * {@code offlineSince}, nâng lên {@code AWAY} khi quá {@code AWAY_AFTER_S}, và
 * broadcast để mọi máy cập nhật avatar / toast.
 */
@Service
public class PresenceService implements PresenceServiceProbe {

  private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

  private final RoomRepository roomRepository;
  private final GameRulesConfig rules;
  private final GameBroadcaster broadcaster;
  private final GameSnapshotBuilder snapshotBuilder;

  public PresenceService(
      RoomRepository roomRepository,
      GameRulesConfig rules,
      GameBroadcaster broadcaster,
      GameSnapshotBuilder snapshotBuilder) {
    this.roomRepository = roomRepository;
    this.rules = rules;
    this.broadcaster = broadcaster;
    this.snapshotBuilder = snapshotBuilder;
  }

  /**
 * Client gửi heartbeat: ghi nhận còn sống và báo lại trạng thái hiện tại.
 *
   <p>Không gửi snapshot mỗi nhịp — nhiều máy cùng đẩy sẽ dội WebSocket vô ích;
   * client có sẵn vòng poll để tự khớp lại.
   */
  @Transactional
  public PresenceStatus heartbeat(String code, String playerKey) {
    Room room = roomRepository.findByCode(normalizeCode(code)).orElse(null);
    if (room == null) return PresenceStatus.LEFT;
    Player player = room.findPlayer(normalizeKey(playerKey));
    if (player == null) return PresenceStatus.LEFT;
    if (player.isLeft()) return PresenceStatus.LEFT;

    Instant now = Instant.now();
    PresenceStatus before = statusOf(room, player, now);
    player.setLastSeen(now);
    player.setOfflineSince(null);
    PresenceStatus after = statusOf(room, player, now);
    if (before != after) {
      log.info(
          "[PRESENCE] phong={} nguoi={} {} -> {}",
          room.getCode(),
          player.getName(),
          before,
          after);
    }
    return after;
  }

  /**
   * Quét mọi phòng đang chơi: cập nhật offline/away, chuyển quyền chủ phòng khi
   * chủ phòng bị văng.
   *
   * @return số phòng có thay đổi trạng thái.
   */
  @Transactional
  public int sweep() {
    Instant now = Instant.now();
    int changed = 0;
    for (Room room : roomRepository.findByActiveTrue()) {
      List<String> transitions = new ArrayList<>();
for (Player player : room.getPlayers()) {
      PresenceStatus now0 = statusOf(room, player, now);
        if (now0 == PresenceStatus.ONLINE && player.getOfflineSince() != null) {
          player.setOfflineSince(null);
          transitions.add(player.getName() + " đã quay lại");
        } else if (now0 == PresenceStatus.OFFLINE && player.getOfflineSince() == null) {
          player.setOfflineSince(now);
          transitions.add(player.getName() + " mất kết nối");
        } else if (now0 == PresenceStatus.AWAY && player.getOfflineSince() != null) {
          transitions.add(player.getName() + " vắng mặt");
        }
      }

      String acting = room.getActingHostKey();
      if (room.isOwner(acting == null ? "" : acting) == false && acting != null) {
        // acting host đã rời → trả quyền về chủ phòng nếu còn online.
        room.reclaimHost();
      }
      String ownerKey = room.getOwnerKey();
      if (ownerKey != null) {
        Player owner = findPlayer(room, ownerKey);
        if (owner != null && owner.isAway(room, now, rules.awayAfterS)) {
          String before = room.getActingHostKey();
          room.transferHostTo(this, now);
          if (!java.util.Objects.equals(before, room.getActingHostKey())) {
            log.info(
                "[HOST] phong={} chu-phong={} mat-ket-noi → nguoi tam quyen={}",
                room.getCode(),
                ownerKey,
                room.getActingHostKey());
            transitions.add("quyền chủ phòng chuyển sang " + room.getActingHostKey());
          }
        } else if (owner != null && room.getActingHostKey() != null) {
          // Chủ phòng quay lại → lấy lại quyền.
          log.info("[HOST] phong={} chu-phong={} quay-lai → lay-lai quyen", room.getCode(), ownerKey);
          room.reclaimHost();
          transitions.add(owner.getName() + " lấy lại quyền chủ phòng");
        }
      }

      // Phòng không còn ai online → huỷ lượt đang treo. Không để người offline
      // giữ đồng hồ chạy vô ích: deadline vẫn hết, nhưng người này không nên
      // phải chờ 15s mới tới lượt khi ván đã không ai chơi.
      if (onlineCount(room, now) == 0 && room.getTurnPhase() != TurnPhase.NONE) {
        room.clearTurn();
        transitions.add("cả bàn đang offline — tạm dừng đồng hồ");
      }

      if (!transitions.isEmpty()) {
        broadcaster.gameUpdated(room.getCode(), snapshotBuilder.build(room));
        broadcaster.presence(room.getCode(), transitions);
        changed++;
      }
    }
    return changed;
  }

  @Override
  public boolean isOnline(Room room, Player player, Instant now) {
    return statusOf(room, player, now) == PresenceStatus.ONLINE;
  }

  /**
   * Trạng thái hiện diện, dùng {@code room.lastActivityAt()} làm mốc dự phòng cho
   * phòng tạo trước khi có cột {@code last_seen}.
   */
  public PresenceStatus statusOf(Room room, Player player, Instant now) {
    if (player == null || player.isLeft()) return PresenceStatus.LEFT;
    Instant fallback = room == null ? null : room.lastActivityAt();
    return player.presenceAt(now, rules.offlineDetectS, rules.awayAfterS, fallback);
  }

  /** Số người đang online trong phòng. */
  public int onlineCount(Room room, Instant now) {
    int count = 0;
    for (Player p : room.getPlayers()) {
      if (isOnline(room, p, now)) count++;
    }
    return count;
  }

  /** Có ai trong phòng đang online không. */
  public boolean anyoneOnline(Room room, Instant now) {
    return onlineCount(room, now) > 0;
  }

  private Player findPlayer(Room room, String key) {
    for (Player p : room.getPlayers()) {
      if (p.getPlayerKey().equals(key)) return p;
    }
    return null;
  }

  private static String normalizeCode(String code) {
    return code == null ? "" : code.trim().toUpperCase();
  }

  private static String normalizeKey(String key) {
    return key == null ? "" : key.trim();
  }

  /** Người còn online sớm nhất, trừ {@code excludeKey} — dùng cho máy trọng tài. */
  public String refereeKey(Room room, Instant now, String excludeKey) {
    String best = null;
    int bestOrder = Integer.MAX_VALUE;
    for (Player p : room.getPlayers()) {
      if (p.isLeft()) continue;
      if (p.getPlayerKey().equals(excludeKey)) continue;
      if (!isOnline(room, p, now)) continue;
      if (p.getJoinOrder() < bestOrder) {
        bestOrder = p.getJoinOrder();
        best = p.getPlayerKey();
      }
    }
    return best;
  }

  /** Trạng thái hiện diện của từng người, để client vẽ avatar. */
  public Map<String, PresenceStatus> statusMap(Room room, Instant now) {
    Map<String, PresenceStatus> map = new HashMap<>();
    for (Player p : room.getPlayers()) {
      map.put(p.getPlayerKey(), p.presenceAt(now, rules.offlineDetectS, rules.awayAfterS));
    }
    return map;
  }
}