package vn.careermap.domain;

public enum TileType {
  START, R, I, A, S, E, C, CHANCE, CHALLENGE, FINISH;

  public boolean isRiasec() {
    return this == R || this == I || this == A || this == S || this == E || this == C;
  }
}