package vn.careermap.web.dto;

public record PlayerInfo(
    String playerKey, String name, boolean left, boolean orderRolled, int orderDice) {}