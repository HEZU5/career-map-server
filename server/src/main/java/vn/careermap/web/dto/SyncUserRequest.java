package vn.careermap.web.dto;

/**
 * Yêu cầu đồng bộ hồ sơ người dùng từ client (Firebase Auth → DB game).
 *
 * <p>Cũng dùng làm payload trả về của {@code GET /api/users/{firebaseUid}}
 * (field {@code role} chỉ được điền khi đọc, client gửi sync không cần role).
 */
public record SyncUserRequest(
    String firebaseUid,
    String email,
    String displayName,
    String photoURL,
    String role) {}
