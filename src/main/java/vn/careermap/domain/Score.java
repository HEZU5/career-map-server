package vn.careermap.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "scores",
    uniqueConstraints = @UniqueConstraint(columnNames = {"player_id", "category"}))
public class Score {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "player_id", nullable = false)
  private Player player;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 1)
  private RiasecCategory category;

  @Column(nullable = false)
  private int points;

  public static Score create(Player player, RiasecCategory category) {
    Score score = new Score();
    score.player = player;
    score.category = category;
    score.points = 0;
    return score;
  }

  public void addPoints(int amount) {
    points += amount;
  }

  public Long getId() {
    return id;
  }

  public Player getPlayer() {
    return player;
  }

  public RiasecCategory getCategory() {
    return category;
  }

  public int getPoints() {
    return points;
  }
}