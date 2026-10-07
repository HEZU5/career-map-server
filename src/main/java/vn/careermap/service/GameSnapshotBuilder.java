package vn.careermap.service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.domain.Player;
import vn.careermap.domain.PresenceStatus;
import vn.careermap.domain.RiasecCategory;
import vn.careermap.domain.Room;
import vn.careermap.web.dto.GameSnapshot;

/**
 * Dựng ảnh chụp toàn bộ state ván để đẩy qua WebSocket.
 *
 * <p>Một thời điểm {@code now} được lấy MỘT lần rồi truyền cho mọi người chơi,
 * để không xảy ra trường hợp người này bị tính offline còn người kia online chỉ
 * vì lệch vài mili giây giữa hai vòng lặp.
 */
@Component
public class GameSnapshotBuilder {
  private final GameRulesConfig rules;

  public GameSnapshotBuilder(GameRulesConfig rules) {
    this.rules = rules;
  }

  public GameSnapshot build(Room room) {
    Instant now = Instant.now();
    Instant fallback = room.lastActivityAt();
    int online =
        (int)
            room.getPlayers().stream()
                .filter(
                    player ->
                        player.presenceAt(
                                now, rules.offlineDetectS, rules.awayAfterS, fallback)
                            == PresenceStatus.ONLINE)
                .count();
    List<GameSnapshot.GamePlayerInfo> players =
        room.getPlayers().stream()
            .map(
                player ->
                    toInfo(
                        player,
                        player.presenceAt(
                            now, rules.offlineDetectS, rules.awayAfterS, fallback)))
            .toList();

    return new GameSnapshot(
        room.getCode(),
        room.isActive(),
        room.getActivePlayerIndex(),
        room.isPendingAnswer(),
        room.isOrderPhase(),
        room.getLastDice(),
        room.getRollId(),
        room.getLastRollerKey(),
        players,
        room.getTurnId(),
        room.getTurnPhase().name(),
        room.getTurnPlayerKey(),
        room.getTurnDeadlineAt(),
        room.isTurnSkipped(),
        room.isLastRollAuto(),
        now,
        room.getStatus().name(),
        room.getOwnerKey(),
        room.getActingHostKey(),
        room.effectiveHostKey(),
        room.getFinishedAt(),
        room.getWinnerKey(),
        online);
  }

  private GameSnapshot.GamePlayerInfo toInfo(Player player, PresenceStatus presence) {
    return new GameSnapshot.GamePlayerInfo(
        player.getPlayerKey(),
        player.getName(),
        player.getCurrentPosition(),
        player.getTotalAnswered(),
        totalPoints(player),
        player.getWinPoints(),
        scoresOf(player),
        player.isLeft(),
        player.isOrderRolled(),
        player.getOrderDice(),
        player.getCharacterName(),
        presence.name().toLowerCase(),
        player.getJoinOrder(),
        player.getPlayOrder(),
        player.isBot());
  }

  private Map<RiasecCategory, Integer> scoresOf(Player player) {
    Map<RiasecCategory, Integer> byCategory = new EnumMap<>(RiasecCategory.class);
    for (RiasecCategory category : RiasecCategory.values()) {
      byCategory.put(category, player.scoreOf(category).getPoints());
    }
    return byCategory;
  }

  private int totalPoints(Player player) {
    return scoresOf(player).values().stream().mapToInt(Integer::intValue).sum();
  }
}