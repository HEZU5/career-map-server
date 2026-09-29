package vn.careermap.web.dto;

import java.util.List;
import java.util.Map;
import vn.careermap.domain.RiasecCategory;

/** Trạng thái thời điểm thật của một ván chơi, gửi qua WebSocket. */
public record GameSnapshot(
    String code,
    boolean active,
    int activePlayerIndex,
    boolean pendingAnswer,
    boolean orderPhase,
    Integer lastDice,
    List<GamePlayerInfo> players) {

  public record GamePlayerInfo(
      String playerKey,
      String name,
      int position,
      int totalAnswered,
      int totalPoints,
      int winPoints,
      Map<RiasecCategory, Integer> scores,
      boolean left,
      boolean orderRolled,
      int orderDice) {}
}