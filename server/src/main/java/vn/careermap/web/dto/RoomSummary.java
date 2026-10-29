package vn.careermap.web.dto;

/** Tóm tắt phòng đang chơi để hiển thị trong trang quản trị. */
public record RoomSummary(
    String code,
    String status,
    String hostName,
    int playerCount,
    String winnerKey,
    String createdAt) {}
