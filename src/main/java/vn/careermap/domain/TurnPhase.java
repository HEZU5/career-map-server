package vn.careermap.domain;

/** Giai đoạn của lượt hiện tại — quyết định đồng hồ nào chạy. */
public enum TurnPhase {
  /** Chưa có lượt nào (phòng chờ). */
  NONE,

  /** Đang chờ người chơi tung xúc xắc. */
  WAITING_ROLL,

  /** Đang chờ trả lời thẻ ở ô đích. */
  WAITING_ANSWER
}