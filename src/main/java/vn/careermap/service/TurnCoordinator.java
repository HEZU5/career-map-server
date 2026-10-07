package vn.careermap.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.domain.TileType;
import vn.careermap.domain.TurnPhase;

/**
 * Nơi DUY NHẤT sinh ra {@code turnId} và {@code deadlineAt}.
 *
 * <p>Điểm cốt lõi của yêu cầu đồng hồ: mốc hết hạn phải do server đặt đúng một
 * lần. Nếu mỗi máy tự đếm ngược thì tab nền bị throttle, máy lag làm đồng hồ
 * lệch, và lần tự tung của máy trọng tài sẽ kích hoạt lệch với lượt thật.
 *
 * <p>Mọi lối vào tung — bấm tay, tự tung của máy người chơi, tự tung của máy
 * trọng tài, hay sweeper của server — đều gọi {@link #rollForTurn} với
 * {@code turnId} mà nó tin là hiện hành. Hàm kiểm tra lại bằng compare-and-set
 * nên chỉ một bên ghi được, các bên còn lại nhận {@code null} và im lặng bỏ qua.
 */
@Service
public class TurnCoordinator {
  private static final Logger log = LoggerFactory.getLogger(TurnCoordinator.class);

/**
   * 53 ô — bảng chuyển động, GIỮ NGUYÊN so với bản gốc (chỉ đổi chỗ chứa).
   *
   * <p>Quân đi ngược index theo mũi tên `<<<<`. 24 ô RIASEC (mỗi nhóm 4),
   * 10 CƠ HỘI, 7 THỬ THÁCH (gồm 4 góc), 11 ô ⭐ trang trí, 1 START. Không có ô
   * FINISH.
   */
  private static final List<TileType> PATH =
      List.of(
          TileType.START, TileType.CHANCE, TileType.R, TileType.CHALLENGE,
          TileType.I, TileType.CHANCE, TileType.A, TileType.STAR,
          TileType.S, TileType.CHANCE, TileType.E, TileType.STAR,
          TileType.CHALLENGE, TileType.C, TileType.CHANCE, TileType.R,
          TileType.STAR, TileType.I, TileType.CHALLENGE, TileType.A,
          TileType.STAR, TileType.S, TileType.CHANCE, TileType.E,
          TileType.STAR, TileType.C, TileType.CHALLENGE, TileType.R,
          TileType.STAR, TileType.I, TileType.CHANCE, TileType.A,
          TileType.STAR,
          TileType.CHALLENGE, TileType.S, TileType.CHANCE, TileType.E,
          TileType.STAR, TileType.C, TileType.CHANCE, TileType.R,
          TileType.STAR,
          TileType.CHALLENGE, TileType.I, TileType.CHANCE, TileType.A,
          TileType.STAR, TileType.S, TileType.CHALLENGE, TileType.E,
          TileType.STAR, TileType.C, TileType.CHANCE);

  private final GameRulesConfig rules;
  private final SecureRandom dice = new SecureRandom();
  private final OfflinePolicyHandler policy;

  public TurnCoordinator(GameRulesConfig rules, OfflinePolicyHandler policy) {
    this.rules = rules;
    this.policy = policy;
  }

  // ── Tung ────────────────────────────────────────────────────────────────────

  /**
   * Tung cho lượt {@code expectedTurnId}.
   *
   * @return {@code null} nếu lượt không còn hợp lệ — người gọi phải im lặng bỏ
   *     qua, không báo lỗi (đây là trường hợp bình thường khi hai máy tranh
   *     nhau cùng tự tung).
   */
  TurnRoll rollForTurn(
      Room room, Player roller, long expectedTurnId, boolean auto, String reason) {
    if (room.getTurnPhase() != TurnPhase.WAITING_ROLL) return null;
    if (!room.isCurrentTurn(expectedTurnId, TurnPhase.WAITING_ROLL)) return null;
    if (room.getTurnPlayerKey() == null) return null;
    if (!room.getTurnPlayerKey().equals(roller.getPlayerKey())) return null;
    if (roller.isLeft()) return null;
    return room.isOrderPhase()
        ? rollOrderPhase(room, roller, auto, reason)
        : rollAndMove(room, roller, auto, reason);
  }

  private TurnRoll rollOrderPhase(Room room, Player roller, boolean auto, String reason) {
    if (roller.isOrderRolled()) return null;
    int value = rollValue();
    room.recordRoll(roller.getPlayerKey(), value, auto);
    room.rollOrder(roller, value);

    if (room.isOrderPhase()) {
      room.beginTurn(
          nextOrderRoller(room), TurnPhase.WAITING_ROLL, Instant.now().plusSeconds(rules.turnRollS), false);
    } else {
      // Giai đoạn xếp thứ tự khép lại → mở lượt chơi thật cho người đi trước.
      room.clearTurn();
      startNextTurn(room, Instant.now());
    }
    logAuto(roller, room, auto, reason);
    return new TurnRoll(value, roller.getCurrentPosition(), false, auto, reason);
  }

  private TurnRoll rollAndMove(Room room, Player roller, boolean auto, String reason) {
    int value = rollValue();
    room.recordRoll(roller.getPlayerKey(), value, auto);

    int pos = roller.getCurrentPosition();
    int raw = pos - value;
    int next = Math.floorMod(raw, PATH.size());
    roller.setCurrentPosition(next);
    if (raw < 0) {
      roller.addCompletedLaps(1);
    }

    boolean needsAnswer = needsAnswerAt(next);
    room.setPendingAnswer(needsAnswer);
    if (needsAnswer) {
      // Rơi vào ô thẻ: đổi đồng hồ sang ANSWER_TIMEOUT_S cho chính người này.
      room.beginTurn(roller, TurnPhase.WAITING_ANSWER, Instant.now().plusSeconds(rules.answerTimeoutS), false);
    } else {
      room.clearTurn();
      room.advanceTurn();
      startNextTurn(room, Instant.now());
    }
    logAuto(roller, room, auto, reason);
    return new TurnRoll(value, next, true, auto, reason);
  }

  private void logAuto(Player roller, Room room, boolean auto, String reason) {
    if (!auto) return;
    log.info(
        "[AUTO-ROLL] phong={} turnId={} nguoi={} ly-do={} gia-tri={}",
        room.getCode(),
        room.getTurnId(),
        roller.getName(),
        reason,
        room.getLastDice());
  }

  // ── Bắt đầu / chuyển lượt ──────────────────────────────────────────────────

  /** Mở lượt cho người ở {@code activePlayerIndex}, tôn trọng luật vắng mặt. */
  void startNextTurn(Room room, Instant now) {
    Player player = playerAtActiveIndex(room);
    if (player == null) return;

    if (player.isAway(room, now, rules.awayAfterS)) {
      // Vắng mặt từ AWAY_AFTER_S → đọc OFFLINE_POLICY.
      if (!policy.onPlayerAway(room, player, now)) {
        skipTurn(room, player, now);
      }
      return;
    }

    // Mất kết nối tạm thời: tự tung sau FAST_AUTO_ROLL_S, không bắt cả bàn chờ 15s.
    boolean disconnected = player.isDisconnected(room, now, rules.offlineDetectS);
    long seconds = disconnected ? rules.fastAutoRollS : rules.turnRollS;
    room.beginTurn(player, TurnPhase.WAITING_ROLL, now.plusSeconds(seconds), false);
  }

  /**
   * Bỏ qua lượt người vắng mặt: quân và điểm giữ nguyên, không tung, không mở
   * thẻ, sang người kế tiếp. Mỗi lượt bỏ qua chiếm gần như 0 giây trên màn hình.
   */
  void skipTurn(Room room, Player skipped, Instant now) {
    log.info(
        "[AWAY-SKIP] phong={} turnId={} bo-qua={}",
        room.getCode(),
        room.getTurnId(),
        skipped.getName());
    room.clearTurn();
    room.advanceTurn();
    startNextTurn(room, now);
  }

  private Player nextOrderRoller(Room room) {
    int count = room.getPlayers().size();
    int candidate = room.getActivePlayerIndex();
    for (int i = 0; i < count; i++) {
      candidate = (candidate + 1) % count;
      Player p = room.getPlayers().get(candidate);
      if (p != null && !p.isLeft() && !p.isOrderRolled()) return p;
    }
    return playerAtActiveIndex(room);
  }

  private Player playerAtActiveIndex(Room room) {
    int index = room.getActivePlayerIndex();
    if (index < 0 || index >= room.getPlayers().size()) return null;
    return room.getPlayers().get(index);
  }

  // ── Hết hạn ────────────────────────────────────────────────────────────────

  /**
   * Xử lý lượt đã quá hạn.
   *
   * <p>Trả về {@code null} nghĩa là không còn gì phải làm (lượt đã được xử lý bởi
   * bên khác trong lúc chờ khoá).
   */
  TurnRoll expireTurn(Room room, Player player, String reason) {
    if (room.getTurnPhase() != TurnPhase.WAITING_ROLL) {
      if (room.getTurnPhase() == TurnPhase.WAITING_ANSWER) {
        // Hết giờ trả lời: đóng thẻ, không cộng điểm, không phạt, chuyển lượt.
        log.info(
            "[ANSWER-TIMEOUT] phong={} turnId={} nguoi={}",
            room.getCode(),
            room.getTurnId(),
            player.getName());
        room.setPendingAnswer(false);
        room.setLastDice(null);
        room.clearTurn();
        room.advanceTurn();
        startNextTurn(room, Instant.now());
      }
      return null;
    }
    TurnRoll roll = rollForTurn(room, player, room.getTurnId(), true, reason);
    return roll;
  }

  int rollValue() {
    return dice.nextInt(6) + 1;
  }

  /** Số ô trên bàn (53) — bảng PATH là nguồn duy nhất. */
  public static int pathSize() {
    return PATH.size();
  }

  /** Loại ô tại vị trí đã chuẩn hoá — dùng chung để không lệch luật. */
  public static TileType pathTile(int position) {
    return PATH.get(Math.floorMod(position, PATH.size()));
  }

  /** Ô này có cần trả lời không (giữ luật cũ: mọi ô trừ START và ⭐). */
  boolean needsAnswerAt(int position) {
    TileType type = PATH.get(Math.floorMod(position, PATH.size()));
    return type != TileType.START && type != TileType.STAR;
  }

  /** Kết quả một lần tung. */
  public record TurnRoll(int value, int position, boolean moved, boolean auto, String reason) {}

  /** Chiến lược xử lý người vắng mặt — thực thi theo {@code OfflinePolicy}. */
  public interface OfflinePolicyHandler {
    /** @return true nếu handler đã tự xử lý lượt (ví dụ bot thay). */
    boolean onPlayerAway(Room room, Player player, Instant now);
  }
}