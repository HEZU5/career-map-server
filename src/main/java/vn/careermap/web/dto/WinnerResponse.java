package vn.careermap.web.dto;

public record WinnerResponse(
    boolean eligibleToFinish, int totalAnswered, int currentPosition) {}