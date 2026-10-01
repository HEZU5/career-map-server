package vn.careermap.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import vn.careermap.domain.Player;
import vn.careermap.domain.RiasecCategory;
import vn.careermap.domain.Room;
import vn.careermap.web.dto.GameSnapshot;

/** Dựng ảnh chụp toàn bộ state ván để đẩy qua WebSocket. */
@Component
public class GameSnapshotBuilder {
  public GameSnapshot build(Room room) {
    List<GameSnapshot.GamePlayerInfo> players =
        room.getPlayers().stream()
            .map(player -> new GameSnapshot.GamePlayerInfo(
                player.getPlayerKey(),
                player.getName(),
                player.getCurrentPosition(),
                player.getTotalAnswered(),
                totalPoints(player),
                player.getWinPoints(),
                scoresOf(player),
                player.isLeft(),
                player.isOrderRolled(),
                player.getOrderDice()))
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
        players);
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