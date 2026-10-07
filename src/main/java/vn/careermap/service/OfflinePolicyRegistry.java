package vn.careermap.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.careermap.config.OfflinePolicy;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;

/**
 * Cài đặt {@code OFFLINE_POLICY} theo kiểu strategy.
 *
 * <p>Chỉ {@code 'skip'} được cài đầy đủ. {@code 'bot'} và {@code 'eliminate'} là
 * chỗ cắm: thêm hành vi vào đây, chỗ gọi trong {@link TurnCoordinator} không
 * phải đổi.
 */
@Component
public class OfflinePolicyRegistry implements TurnCoordinator.OfflinePolicyHandler {

  private static final Logger log = LoggerFactory.getLogger(OfflinePolicyRegistry.class);

  private final OfflinePolicy policy;

  public OfflinePolicyRegistry(vn.careermap.config.GameRulesConfig rules) {
    this.policy = rules.offlinePolicy;
  }

  @Override
  public boolean onPlayerAway(Room room, Player player, java.time.Instant now) {
    return switch (policy) {
      case SKIP -> {
        log.info(
            "[POLICY] phong={} nguoi={} bo-qua-luot (skip)",
            room.getCode(),
            player.getName());
        yield false; // false = để TurnCoordinator bỏ qua lượt và sang người sau
      }
      case BOT -> {
        log.warn(
            "[POLICY] phong={} che-do-bot CHUA CAI — van bo-qua-luot thay the",
            room.getCode());
        yield false;
      }
      case ELIMINATE -> {
        log.warn(
            "[POLICY] phong={} che-do-eliminate CHUA CAI — van bo-qua-luot thay the",
            room.getCode());
        yield false;
      }
    };
  }

  OfflinePolicy policy() {
    return policy;
  }
}