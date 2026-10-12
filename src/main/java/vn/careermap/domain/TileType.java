package vn.careermap.domain;

public enum TileType {
  START, STAR, R, I, A, S, E, C, CHANCE, CHALLENGE;

  public boolean isRiasec() {
    return this == R || this == I || this == A || this == S || this == E || this == C;
  }
}