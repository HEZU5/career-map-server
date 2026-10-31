package vn.careermap.web.dto;

/** Debug adjustment for an online player's score in local/staging only. */
public record DebugPointsRequest(String playerId, String category, int delta) {}
