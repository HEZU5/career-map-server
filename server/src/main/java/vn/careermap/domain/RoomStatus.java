package vn.careermap.domain;

/** Vòng đời phòng — quyết định phòng còn "sống" để dọn rác không. */
public enum RoomStatus {
  /** Phòng chờ, chưa bắt đầu. */
  WAITING,

  /** Đang chơi. */
  PLAYING,

  /** Đã có người thắng / người chơi kết thúc ván; giữ để xem bảng kết quả. */
  FINISHED
}