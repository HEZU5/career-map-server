package vn.careermap.web.dto;

/** Tóm tắt thông tin user để hiển thị trong trang quản trị (không lộ nhạy cảm). */
public record UserSummary(
    String firebaseUid,
    String email,
    String displayName,
    String role,
    String createdAt) {}
