package vn.careermap.web.dto;

public record PlayerInfo(
    String playerKey,
    String name,
    String characterName,
    boolean left,
    boolean orderRolled,
    int orderDice) {}