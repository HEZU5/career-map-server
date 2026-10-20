package vn.careermap.domain;

/**
 * Hiệu ứng của 20 lá Cơ hội / Thử thách.
 *
 * <p>Enum này là MỘT PHÍA của hợp đồng giữa server và client: client (Flutter)
 * dùng cùng tên hiệu ứng để vẽ nút bấm, server dùng nó để áp hiệu ứng thật.
 * Đổi tên ở đây thì phải đổi cả ở client (xem {@code lib/features/game/data/
 * career_cards.dart}).
 *
 * <p>Vì sao có enum: trước đây lá bài chỉ mang {@code winPoints} và hiệu ứng
 * thật ("Tiến thêm 2 ô", "Chọn 1 người chơi", "Chơi thêm 1 lượt") bị viết ra rồi
 * bỏ qua — server chỉ cộng điểm. Nay server tra bảng hiệu ứng theo {@code
 * cardId} và áp đúng hiệu ứng.
 */
public enum BonusEffect {
  /** Chỉ cộng Điểm Chiến thắng, không có hiệu ứng phụ. */
  NONE,

  /** Được chơi thêm một lượt ngay (tung tiếp) — "gặp người cố vấn", "quay lại mạnh mẽ". */
  EXTRA_ROLL,

  /** Tiến thêm 2 ô theo chiều đi, KHÔNG cộng điểm. */
  MOVE_2,

  /** Tiến thêm 3 ô theo chiều đi, KHÔNG cộng điểm. */
  MOVE_3,

  /** Chọn 1 người chơi trong phòng; người chọn và người được chọn cùng +1 điểm. */
  PICK_PLAYER_PLUS1,

  /** Mọi người trong phòng (kể cả người vừa đến) cùng +1 điểm. */
  ALL_PLAYERS_PLUS1,

  /** Chọn một trong hai: {@code PLUS_3} (+3 điểm) hoặc {@code MOVE_2} (đi 2 ô). */
  PLUS3_OR_MOVE_2
}
