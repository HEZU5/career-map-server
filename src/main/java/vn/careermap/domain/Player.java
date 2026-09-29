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

  public void setPlayOrder(int playOrder) {
    this.playOrder = playOrder;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Room getRoom() {
    return room;
  }

  public List<Score> getScores() {
    return scores;
  }
}