package vn.careermap.web.dto;

import java.time.Instant;
import java.util.List;

public record RoomResponse(
    String code, String hostName, int maxPlayers, List<PlayerInfo> players, boolean active, Instant createdAt) {}