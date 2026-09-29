package vn.careermap.web.dto;

public record AnswerRequest(
    String playerId,
    String questionId,
    String selectedOption,
    String category,
    String targetCategory,
    Integer points,
    Integer winPoints) {}