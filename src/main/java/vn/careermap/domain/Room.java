package vn.careermap.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
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

  private Integer lastDice;

  @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
  @OrderBy("playOrder ASC, joinOrder ASC")
  private List<Player> players = new ArrayList<>();

  public static Room create(String code, String hostName) {
    Room room = new Room();
    room.code = code;
    room.hostName = hostName;
    room.createdAt = Instant.now();
    return room;
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

  /** Ghi nhận kết quả tung quyết định thứ tự (KHÔNG theo lượt — ai tới cũng
   *  tự tung được) và kết thúc giai đoạn nếu mọi người (còn trong trận) đã
   *  tung. Trả true khi giai đoạn kết thúc. */
  public boolean rollOrder(Player roller, int diceValue) {
    roller.setOrderRolled(true);
    roller.setOrderDice(diceValue);
    return finalizeIfOrderComplete();
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

  public Integer getLastDice() {
    return lastDice;
  }

  public void setLastDice(Integer lastDice) {
    this.lastDice = lastDice;
  }

  public List<Player> getPlayers() {
    return players;
  }
}