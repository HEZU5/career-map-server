package vn.careermap.web.dto;

/**
 * Kết quả một nhịp heartbeat.
 *
 * @param presence online | offline | away | left
 * @param heartbeatS chu kỳ gửi tiếp theo — client dùng luôn con số này thay vì
 *     hard-code, nên đổi cấu hình là client tự theo kịp.
 * @param serverTime giờ server, để client hiệu chỉnh đồng hồ so với deadline
 * @param roomClosed true nếu phòng đã bị xóa → client phải dừng timer và về lobby
 */
public record HeartbeatResponse(
    String presence, long heartbeatS, java.time.Instant serverTime, boolean roomClosed) {}