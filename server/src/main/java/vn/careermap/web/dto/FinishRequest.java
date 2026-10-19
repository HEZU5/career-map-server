package vn.careermap.web.dto;

/**
 * Đánh dấu ván kết thúc.
 *
 * <p>Không thay đổi luật chơi: client vẫn là bên quyết định ai thắng (đã làm vậy
 * từ trước qua {@code checkWinner}). Endpoint này chỉ ghi trạng thái để phòng
 * biết cần giữ lại cho bảng kết quả theo {@code FINISHED_ROOM_TTL_MIN}, rồi mới
 * dọn — thay vì xóa ngay như cách cũ.
 *
 * @param winnerKey playerKey người thắng, rỗng nếu kết thúc không có thắng
 */
public record FinishRequest(String playerId, String winnerKey) {}