package vn.careermap.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Entity
@Table(name = "rooms")
public class Room {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * Chống ghi đè khi hai máy cùng gửi (hai lần tự tung cùng một lượt).
   *
   * <p>Không có cột này thì hai request song song đều đọc cùng
   * {@code activePlayerIndex}/{@code currentPosition} rồi đều ghi — mất cập
   * nhật nhật và quân đi hai lần. Hibernate phát sinh lỗi OptimisticLock khi
   * commit nên service có thể thử lại an toàn.
   */
  @Version
  @Column(nullable = false)
  private long version;

  @Column(nullable = false, unique = true, length = 8)
  private String code;

  @Column(nullable = false)
  private String hostName;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private boolean active;

  @Column(nullable = false)
  private int activePlayerIndex;

  @Column(nullable = false)
  private boolean pendingAnswer;

  /** Đang trong giai đoạn tung xúc xắc quyết định thứ tự lượt
   *  (ngay sau khi bắt đầu ván, ai tung cao nhất đi trước). */
  @Column(name = "order_phase", nullable = false, columnDefinition = "boolean not null default false")
  private boolean orderPhase;

  /** Phòng RIÊNG TƯ: không xuất hiện trong danh sách phòng công khai
   *  (GET /api/rooms) nhưng vẫn vào được bằng mã phòng. Phòng công khai
   *  (false) thì ai cũng thấy trong danh sách. */
  @Column(name = "is_private", nullable = false, columnDefinition = "boolean not null default false")
  private boolean privateRoom;

  private Integer lastDice;

  /** Đếm số lần tung trong ván — client dùng làm "rollId" để nhận biết lần
   *  tung MỚI (mọi người chơi đều thấy cùng kết quả, không phải chỉ người
   *  tung nhận được). */
  @Column(nullable = false, columnDefinition = "bigint not null default 0")
  private long rollId;

  /** Key người vừa tung (để client gắn avatar + tên vào thông báo kết quả). */
  @Column(name = "last_roller_key", length = 64)
  private String lastRollerKey;

  /** Lần cuối phòng có hoạt động (tạo, vào phòng, đổi tên, chọn nhân vật, bấm
   *  nút…). Hibernate tự cập nhật mỗi lần ghi — nhờ đó {@code RoomCleanupService}
   *  biết phòng nào bị bỏ hoang để xoá. Phòng cũ chưa có cột này thì lấy
   *  {@code createdAt} làm mốc. */
  @Column(name = "updated_at")
  private Instant updatedAt;

  // ── Vòng đời phòng ──────────────────────────────────────────────────────────

  /** Chờ / đang chơi / đã kết thúc. Chỉ dọn phòng theo TTL khi FINISHED. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16, columnDefinition = "varchar(16) not null default 'WAITING'")
  private RoomStatus status = RoomStatus.WAITING;

  /** playerKey của người tạo phòng — CHỦ PHÒNG. Không đổi khi mất kết nối. */
  @Column(name = "owner_key", length = 64)
  private String ownerKey;

  /** Người tạm giữ quyền chủ phòng khi chủ phòng offline. */
  @Column(name = "acting_host_key", length = 64)
  private String actingHostKey;

  /** Lúc kết thúc ván (giữ phòng để xem bảng kết quả trước khi dọn). */
  @Column(name = "finished_at")
  private Instant finishedAt;

  /** playerKey người thắng; null nếu ván kết thúc không có người thắng. */
  @Column(name = "winner_key", length = 64)
  private String winnerKey;

  // ── Lượt chơi ───────────────────────────────────────────────────────────────

  /**
   * Mã lượt. Tăng mỗi lần chuyển lượt; dùng làm điều kiện compare-and-set cho
   * mọi lần tung và bỏ qua. Client và server cùng so với con số này để chỉ một
   * bên được ghi.
   */
  @Column(name = "turn_id", nullable = false, columnDefinition = "bigint not null default 0")
  private long turnId;

  @Enumerated(EnumType.STRING)
  @Column(
      name = "turn_phase",
      nullable = false,
      length = 24,
      columnDefinition = "varchar(24) not null default 'NONE'")
  private TurnPhase turnPhase = TurnPhase.NONE;

  /** playerKey của người đang đến lượt (null khi chưa có lượt). */
  @Column(name = "turn_player_key", length = 64)
  private String turnPlayerKey;

  /**
   * Mốc hết hạn của lượt, đặt bởi SERVER một lần duy nhất khi lượt bắt đầu.
   *
   * <p>Client chỉ đọc trường này và tự tính {@code deadlineAt - (giờ hiện tại đã
   * bù lệch)} — không đếm lùi bằng timer cục bộ, nên tab nền/máy lag không làm
   * đồng hồ lệch, và mọi máy luôn thấy cùng một con số.
   */
  @Column(name = "turn_deadline_at")
  private Instant turnDeadlineAt;

  /** Lượt đã bị bỏ qua vì người chơi vắng mặt (dùng cho pill thông báo). */
  @Column(name = "turn_skipped", nullable = false, columnDefinition = "boolean not null default false")
  private boolean turnSkipped;

  /** true nếu lần tung gần nhất do hết giờ tự động (hiện pill "tự động tung"). */
  @Column(name = "last_roll_auto", nullable = false, columnDefinition = "boolean not null default false")
  private boolean lastRollAuto;

  @jakarta.persistence.PrePersist
  @jakarta.persistence.PreUpdate
  void touch() {
    this.updatedAt = Instant.now();
  }

  @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
  @OrderBy("playOrder ASC, joinOrder ASC")
  private List<Player> players = new ArrayList<>();

  /** Người tạo phòng — CHỦ PHÒNG. Chỉ chủ phòng được xoá phòng. */
  public static Room create(String code, String hostName, boolean privateRoom, String ownerKey) {
    Room room = new Room();
    room.code = code;
    room.hostName = hostName;
    room.createdAt = Instant.now();
    room.privateRoom = privateRoom;
    room.ownerKey = ownerKey;
    room.status = RoomStatus.WAITING;
    return room;
  }

  // ── Lượt: bắt đầu / hết hạn / bỏ qua ─────────────────────────────────────

  /**
   * Bắt đầu một lượt mới cho {@code player}: tăng {@code turnId}, đặt hạn.
   *
   * <p>Gọi MỘT lần duy nhất mỗi lần chuyển lượt — đây là nơi duy nhất sinh ra
   * {@code deadlineAt}, nhờ vậy mọi máy nhận cùng một mốc hết hạn.
   *
   * @param deadline hạn chót (bằng {@code Instant.now()} cộng TTL của pha)
   */
  public void beginTurn(Player player, TurnPhase phase, Instant deadline, boolean skipped) {
    this.turnId++;
    this.turnPhase = phase;
    this.turnPlayerKey = player == null ? null : player.getPlayerKey();
    this.turnDeadlineAt = deadline;
    this.turnSkipped = skipped;
    if (player != null) {
      this.activePlayerIndex = indexOf(player);
    }
  }

  /** Đóng lượt (đã xử lý xong) — không deadline nào chạy nữa. */
  public void clearTurn() {
    this.turnPhase = TurnPhase.NONE;
    this.turnDeadlineAt = null;
    this.turnSkipped = false;
  }

  /** Lượt này còn đang chờ và đã quá hạn chưa? */
  public boolean isDeadlinePassed(Instant now) {
    return turnDeadlineAt != null && turnPhase != TurnPhase.NONE && now.isAfter(turnDeadlineAt);
  }

  /** Compare-and-set cho mọi lần tung: chỉ khi lượt và pha vẫn như cũ. */
  public boolean isCurrentTurn(long expectedTurnId, TurnPhase expectedPhase) {
    return this.turnId == expectedTurnId && this.turnPhase == expectedPhase;
  }

  public int indexOf(Player player) {
    for (int i = 0; i < players.size(); i++) {
      if (players.get(i).getPlayerKey().equals(player.getPlayerKey())) return i;
    }
    return -1;
  }

  /** Người đang đến lượt, hoặc null nếu turnPlayerKey không còn trong phòng. */
  public Player turnPlayer() {
    if (turnPlayerKey == null) return null;
    for (Player p : players) {
      if (p.getPlayerKey().equals(turnPlayerKey)) return p;
    }
    return null;
  }

  /** Ai đang thực sự giữ quyền chủ phòng lúc này. */
  public String effectiveHostKey() {
    if (actingHostKey != null && hasPlayer(actingHostKey) && !isLeft(actingHostKey)) {
      return actingHostKey;
    }
    return ownerKey;
  }

  public boolean hasPlayer(String playerKey) {
    return players.stream().anyMatch(p -> p.getPlayerKey().equals(playerKey));
  }

  public boolean isLeft(String playerKey) {
    return players.stream()
        .anyMatch(p -> p.getPlayerKey().equals(playerKey) && p.isLeft());
  }

  public boolean isOwner(String playerKey) {
    return ownerKey != null && ownerKey.equals(playerKey);
  }

  /**
   * Chủ phòng offline và còn người online khác thì chuyển quyền tạm cho người
   * vào phòng sớm nhất. Chủ phòng quay lại sẽ lấy lại (xem {@link #reclaimHost}).
   */
  public void transferHostTo(PresenceServiceProbe probe, java.time.Instant now) {
    if (actingHostKey != null) return; // đã có người giữ quyền tạm
    Player owner = findPlayer(ownerKey);
    if (owner != null && probe.isOnline(this, owner, now)) {
      reclaimHost();
      return;
    }
    for (Player p : players) {
      if (p.isLeft()) continue;
      if (p.getPlayerKey().equals(ownerKey)) continue;
      if (!probe.isOnline(this, p, now)) continue;
      this.actingHostKey = p.getPlayerKey();
      return;
    }
  }

  /** Tìm người chơi theo key trong phòng này, null nếu không có. */
  public Player findPlayer(String playerKey) {
    if (playerKey == null) return null;
    for (Player p : players) {
      if (p.getPlayerKey().equals(playerKey)) return p;
    }
    return null;
  }

  public void reclaimHost() {
    this.actingHostKey = null;
  }

  public boolean canJoin() {
    return players.size() < 4;
  }

  public void addPlayer(Player player) {
    player.assignRoom(this);
    player.setJoinOrder(players.size());
    players.add(player);
  }

  public void advanceTurn() {
    if (players.isEmpty()) {
      activePlayerIndex = 0;
      return;
    }
    int next = (activePlayerIndex + 1) % players.size();
    int guard = 0;
    while (players.get(next).isLeft() && guard < players.size()) {
      next = (next + 1) % players.size();
      guard++;
    }
    activePlayerIndex = next;
  }

  /** Ghi nhận kết quả tung quyết định thứ tự theo LƯỢT (mỗi người lần lượt
   *  tung, không ai chen được của ai) và kết thúc giai đoạn nếu mọi người
   *  (còn trong trận) đã tung. Trả true khi giai đoạn kết thúc. */
  public boolean rollOrder(Player roller, int diceValue) {
    roller.setOrderRolled(true);
    roller.setOrderDice(diceValue);
    boolean done = finalizeIfOrderComplete();
    if (!done) {
      advanceToNextOrderRoller();
    }
    return done;
  }

  /** Chuyển `activePlayerIndex` sang người kế tiếp chưa tung quyết định thứ
   *  tự (bỏ qua người đã rời phòng), để giai đoạn thứ tự đi lần lượt. */
  public void advanceToNextOrderRoller() {
    if (players.isEmpty()) {
      activePlayerIndex = 0;
      return;
    }
    int next = activePlayerIndex;
    for (int i = 0; i < players.size(); i++) {
      next = (next + 1) % players.size();
      if (!players.get(next).isLeft() && !players.get(next).isOrderRolled()) {
        activePlayerIndex = next;
        return;
      }
    }
  }

  /** Ai cao nhất đi trước; hoà thì người vào phòng trước được trước. */
  public boolean finalizeIfOrderComplete() {
    if (!orderPhase) return false;
    for (Player p : players) {
      if (!p.isLeft() && !p.isOrderRolled()) return false;
    }
    orderPhase = false;
    players.sort(
        Comparator.comparingInt(Player::getOrderDice).reversed()
            .thenComparingInt(Player::getJoinOrder));
    for (int i = 0; i < players.size(); i++) {
      players.get(i).setPlayOrder(i);
    }
    activePlayerIndex = firstNonLeftIndex();
    return true;
  }

  private int firstNonLeftIndex() {
    for (int i = 0; i < players.size(); i++) {
      if (!players.get(i).isLeft()) return i;
    }
    return 0;
  }

  public Long getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getHostName() {
    return hostName;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /** Mốc hoạt động gần nhất; phòng cũ chưa có cột này thì lùi về createdAt. */
  public Instant lastActivityAt() {
    return updatedAt != null ? updatedAt : createdAt;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public int getActivePlayerIndex() {
    return activePlayerIndex;
  }

  public void setActivePlayerIndex(int activePlayerIndex) {
    this.activePlayerIndex = activePlayerIndex;
  }

  public boolean isPendingAnswer() {
    return pendingAnswer;
  }

  public void setPendingAnswer(boolean pendingAnswer) {
    this.pendingAnswer = pendingAnswer;
  }

  public boolean isOrderPhase() {
    return orderPhase;
  }

  public void setOrderPhase(boolean orderPhase) {
    this.orderPhase = orderPhase;
  }

  public boolean isPrivateRoom() {
    return privateRoom;
  }

  public void setPrivateRoom(boolean privateRoom) {
    this.privateRoom = privateRoom;
  }

  public Integer getLastDice() {
    return lastDice;
  }

  public void setLastDice(Integer lastDice) {
    this.lastDice = lastDice;
  }

  public long getRollId() {
    return rollId;
  }

  public String getLastRollerKey() {
    return lastRollerKey;
  }

  /** Ghi nhận 1 lần tung: tăng `rollId` + lưu ai vừa tung. */
  public void recordRoll(String rollerKey, int diceValue, boolean auto) {
    this.rollId++;
    this.lastRollerKey = rollerKey;
    this.lastDice = diceValue;
    this.lastRollAuto = auto;
  }

  public List<Player> getPlayers() {
    return players;
  }

  // ── Getter/setter mới ──────────────────────────────────────────────────────

  public RoomStatus getStatus() {
    return status;
  }

  public void setStatus(RoomStatus status) {
    this.status = status;
  }

  public boolean isFinished() {
    return status == RoomStatus.FINISHED;
  }

  public String getOwnerKey() {
    return ownerKey;
  }

  public void setOwnerKey(String ownerKey) {
    this.ownerKey = ownerKey;
  }

  public String getActingHostKey() {
    return actingHostKey;
  }

  public void setActingHostKey(String actingHostKey) {
    this.actingHostKey = actingHostKey;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public void setFinishedAt(Instant finishedAt) {
    this.finishedAt = finishedAt;
  }

  public String getWinnerKey() {
    return winnerKey;
  }

  public void setWinnerKey(String winnerKey) {
    this.winnerKey = winnerKey;
  }

  public long getTurnId() {
    return turnId;
  }

  public TurnPhase getTurnPhase() {
    return turnPhase;
  }

  public String getTurnPlayerKey() {
    return turnPlayerKey;
  }

  public Instant getTurnDeadlineAt() {
    return turnDeadlineAt;
  }

  public void setTurnDeadlineAt(Instant turnDeadlineAt) {
    this.turnDeadlineAt = turnDeadlineAt;
  }

  public boolean isTurnSkipped() {
    return turnSkipped;
  }

  public boolean isLastRollAuto() {
    return lastRollAuto;
  }
}