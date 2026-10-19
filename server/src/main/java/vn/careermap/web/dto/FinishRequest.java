package vn.careermap.web.dto;

/**
 * Yêu cầu kết thúc ván.
 *
 * <p>KHÔNG có {@code winnerKey}: trước đây client tự gửi lên và server ghi thẳng
 * vào phòng, tức client tự phong mình là người thắng. Nay server tự chấm theo
 * luật thắng trong {@code GameRulesConfig} — người đầu tiên đủ ngưỡng trong
 * phòng. Endpoint này chỉ ghi trạng thái kết thúc để phòng được giữ cho bảng
 * kết quả theo {@code FINISHED_ROOM_TTL_MIN}, rồi mới dọn.
 *
 * @param playerId playerKey người gọi — phải là chủ phòng mới kết thúc được
 */
public record FinishRequest(String playerId) {}
