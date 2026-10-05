package vn.careermap.web.dto;

import java.time.Instant;

/**
 * Dòng trong danh sách phòng công khai — chỉ những gì client cần để hiển thị
 * và quyết định "vào phòng này", không kéo theo cả danh sách người chơi.
 */
public record OpenRoomResponse(
    String code, String hostName, int playerCount, int maxPlayers, Instant createdAt) {}