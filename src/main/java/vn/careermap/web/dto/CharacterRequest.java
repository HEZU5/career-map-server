package vn.careermap.web.dto;

/**
 * Chọn nhân vật nghề nghiệp. Tách khỏi {@link RenameRequest} vì nhân vật KHÔNG
 * phải tên hiển thị — trước đây chọn nhân vật ghi đè luôn tên người chơi.
 */
public record CharacterRequest(String characterName) {}