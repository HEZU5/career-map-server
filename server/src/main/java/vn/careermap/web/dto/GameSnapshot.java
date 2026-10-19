package vn.careermap.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import vn.careermap.domain.RiasecCategory;

/**
 * Trạng thái thời điểm thật của một ván chơi, gửi qua WebSocket và trả về cho
 * mọi lần poll/reconnect.
 *
 * <p>Client KHÔNG tự đếm lùi. Nó đọc {@code turnDeadlineAt} cùng {@code
 * serverTime} để suy ra độ lệch đồng hồ rồi hiển thị, nên tab bị throttle hay
 * máy lag không làm vòng đếm chạy sai. {@code serverTime} luôn kèm theo vì
 * deadline là mốc tuyệt đối của máy server.
 */
public record GameSnapshot(
    String code,
    boolean active,
    int activePlayerIndex,
    boolean pendingAnswer,
    boolean orderPhase,
    Integer lastDice,
    long rollId,
    String lastRollerKey,
    List<GamePlayerInfo> players,
    // ── Đồng hồ lượt ─────────────────────────────────────────────────────────
    long turnId,
    String turnPhase,
    String turnPlayerKey,
    Instant turnDeadlineAt,
    boolean turnSkipped,
    boolean lastRollAuto,
    Instant serverTime,
    // ── Vòng đời phòng ──────────────────────────────────────────────────────
    String status,
    String ownerKey,
    String actingHostKey,
    String effectiveHostKey,
    Instant finishedAt,
    String winnerKey,
    int onlineCount) {

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
      int orderDice,
      String characterName,
      /** online | offline | away | left — client vẽ avatar và toast theo đây. */
      String presence,
      int joinOrder,
      int playOrder,
      boolean bot) {}
}