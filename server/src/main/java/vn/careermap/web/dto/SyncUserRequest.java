package vn.careermap.web.dto;

/**
 * Yêu cầu đồng bộ hồ sơ người dùng từ client (Firebase Auth → DB game).
 */
public record SyncUserRequest(
    String firebaseUid,
    String email,
    String displayName,
    String photoURL) {}
