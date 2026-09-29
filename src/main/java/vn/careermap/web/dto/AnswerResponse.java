package vn.careermap.web.dto;

public record AnswerResponse(
    boolean solved, int pointsEarned, String category, int totalAnswered, int winPoints) {}