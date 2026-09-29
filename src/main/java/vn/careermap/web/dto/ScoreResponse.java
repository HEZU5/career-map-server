package vn.careermap.web.dto;

import java.util.Map;
import vn.careermap.domain.RiasecCategory;

public record ScoreResponse(
    String playerId,
    Map<RiasecCategory, Integer> scoreByCategory,
    int totalAnswered,
    int currentPosition) {}