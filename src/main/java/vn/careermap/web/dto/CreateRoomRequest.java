package vn.careermap.web.dto;

/**
 * @param isPrivate phòng riêng tư (ẩn khỏi danh sách công khai, vẫn vào
 *     được bằng mã). Kiểu {@link Boolean} để client cũ không gửi field này vẫn
 *     chạy được — null được coi như phòng công khai.
 */
public record CreateRoomRequest(String hostName, Boolean isPrivate) {}