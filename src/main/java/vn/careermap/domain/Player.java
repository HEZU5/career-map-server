package vn.careermap.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "players")
public class Player {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String playerKey;

  @Column(nullable = false)
  private String name;

  /** Nhân vật nghề nghiệp đã chọn (ví dụ "Bác sĩ", "Kỹ sư"). Tách khỏi
   *  [name] vì trước đây chọn nhân vật GHI ĐÈ tên người chơi — mất tên thật. */
  @Column(name = "character_name", length = 64)
  private String characterName;

  /** Lần cuối máy người chơi gửi heartbeat (đặt bởi SERVER, không tin client). */
  @Column(name = "last_seen")
  private Instant lastSeen;

  /**
   * Lúc bắt đầu mất kết nối; null khi đang online.
   *
   * <p>Mốc này quyết định khi nào lượt bị bỏ qua, nên phải do server tự đặt —
   * client không được tự khai "tôi offline".
   */
  @Column(name = "offline_since")
  private Instant offlineSince;

  /** Đây là bot thay thế người đã vắng (nhãn để không lẫn với người thật). */
  @Column(name = "bot", nullable = false, columnDefinition = "boolean not null default false")
  private boolean bot;

  @Column(nullable = false)
  private int currentPosition;

  @Column(nullable = false)
  private int totalAnswered;

  @Column(name = "join_order", nullable = false)
  private int joinOrder;

  @Column(name = "left_game", nullable = false)
  private boolean left;

  @Column(name = "win_points", nullable = false)
  private int winPoints;

  /** Đã tung xúc xắc quyết định thứ tự lượt chưa (trước khi bắt đầu ván). */
  @Column(name = "order_rolled", nullable = false, columnDefinition = "boolean not null default false")
  private boolean orderRolled;

  /** Mặt xúc xắc người chơi đã tung để xếp thứ tự lượt (1–6). */
  @Column(name = "order_dice", nullable = false, columnDefinition = "integer not null default 0")
  private int orderDice;

  /** Thứ tự ngồi trên bàn sau khi xếp theo xúc xắc (0 = được đi trước). */
  @Column(name = "play_order", nullable = false, columnDefinition = "integer not null default 0")
  private int playOrder;

  /** Số vòng cờ đã hoàn thành (đi qua START = ô 0). */
  @Column(name = "completed_laps", nullable = false, columnDefinition = "integer not null default 0")
  private int completedLaps;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "room_id")
  private Room room;

  @OneToMany(mappedBy = "player", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
  private List<Score> scores = new ArrayList<>();

  void assignRoom(Room room) {
    this.room = room;
  }

  public static Player create(String playerKey, String name) {
    Player player = new Player();
    player.playerKey = playerKey;
    player.name = name;
    player.currentPosition = 0;
    player.totalAnswered = 0;
    player.left = false;
    player.winPoints = 0;
    player.orderRolled = false;
    player.orderDice = 0;
    player.playOrder = 0;
    player.completedLaps = 0;
    return player;
  }

  public Score scoreOf(RiasecCategory category) {
    return scores.stream()
        .filter(score -> score.getCategory() == category)
        .findFirst()
        .orElseGet(() -> {
          Score score = Score.create(this, category);
          scores.add(score);
          return score;
        });
  }

  public Long getId() {
    return id;
  }

  public String getPlayerKey() {
    return playerKey;
  }

  public int getJoinOrder() {
    return joinOrder;
  }

  public void setJoinOrder(int joinOrder) {
    this.joinOrder = joinOrder;
  }

  public boolean isLeft() {
    return left;
  }

  public void setLeft(boolean left) {
    this.left = left;
  }

  public String getName() {
    return name;
  }

  public int getCurrentPosition() {
    return currentPosition;
  }

  public void setCurrentPosition(int currentPosition) {
    this.currentPosition = currentPosition;
  }

  public int getTotalAnswered() {
    return totalAnswered;
  }

  public int getWinPoints() {
    return winPoints;
  }

  public void addWinPoints(int points) {
    this.winPoints += points;
  }

  public void incrementTotalAnswered() {
    totalAnswered++;
  }

  public boolean isOrderRolled() {
    return orderRolled;
  }

  public void setOrderRolled(boolean orderRolled) {
    this.orderRolled = orderRolled;
  }

  public int getOrderDice() {
    return orderDice;
  }

  public void setOrderDice(int orderDice) {
    this.orderDice = orderDice;
  }

  public int getPlayOrder() {
    return playOrder;
  }

  public int getCompletedLaps() {
    return completedLaps;
  }

  public void addCompletedLaps(int laps) {
    this.completedLaps += laps;
  }

  public void setPlayOrder(int playOrder) {
    this.playOrder = playOrder;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getCharacterName() {
    return characterName;
  }

  public void setCharacterName(String characterName) {
    this.characterName = characterName;
  }

  // ── Hiện diện ───────────────────────────────────────────────────────────────

  public Instant getLastSeen() {
    return lastSeen;
  }

  public void setLastSeen(Instant lastSeen) {
    this.lastSeen = lastSeen;
  }

  public Instant getOfflineSince() {
    return offlineSince;
  }

  public void setOfflineSince(Instant offlineSince) {
    this.offlineSince = offlineSince;
  }

  public boolean isBot() {
    return bot;
  }

  public void setBot(boolean bot) {
    this.bot = bot;
  }

  /**
   * Suy ra trạng thái hiện diện tại thời điểm {@code now}.
   *
   * <p>Chưa từng gửi heartbeat (vừa vào phòng, chờ lần đầu) coi là online để
   * không biến người mới vào thành "mất kết nối" ngay.
   */
  public PresenceStatus presenceAt(Instant now, long offlineDetectS, long awayAfterS) {
    return presenceAt(now, offlineDetectS, awayAfterS, null);
  }

  /**
   * Như trên nhưng có mốc dự phòng cho phòng tạo trước khi có heartbeat.
   *
   * <p>Phòng cũ không có cột {@code last_seen} nên nếu coi "chưa có heartbeat =
   * online" thì chúng sẽ online vĩnh viễn và không bao giờ được dọn. Lấy
   * {@code room.lastActivityAt()} làm mốc thay thế giải quyết đúng vấn đề này mà
   * không cần migration dữ liệu.
   */
  public PresenceStatus presenceAt(
      Instant now, long offlineDetectS, long awayAfterS, Instant fallback) {
    if (left) return PresenceStatus.LEFT;
    Instant anchor = lastSeen != null ? lastSeen : fallback;
    if (anchor == null) return PresenceStatus.ONLINE;
    long seconds = java.time.Duration.between(anchor, now).getSeconds();
    if (seconds > awayAfterS) return PresenceStatus.AWAY;
    if (seconds > offlineDetectS) return PresenceStatus.OFFLINE;
    return PresenceStatus.ONLINE;
  }

  /** Đã vắng mặt đủ lâu để bỏ qua lượt chưa? */
  public boolean isAway(Room room, Instant now, long awayAfterS) {
    return presenceAt(now, Long.MAX_VALUE, awayAfterS, room == null ? null : room.lastActivityAt())
        == PresenceStatus.AWAY;
  }

  /** Mất kết nối (chưa tới ngưỡng vắng mặt) — tự động chơi hộ sau
   *  {@code FAST_AUTO_ROLL_S}. */
  public boolean isDisconnected(Room room, Instant now, long offlineDetectS) {
    return presenceAt(now, offlineDetectS, Long.MAX_VALUE, room == null ? null : room.lastActivityAt())
        == PresenceStatus.OFFLINE;
  }

  public Room getRoom() {
    return room;
  }

  public List<Score> getScores() {
    return scores;
  }
}