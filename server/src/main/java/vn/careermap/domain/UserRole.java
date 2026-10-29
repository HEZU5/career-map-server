package vn.careermap.domain;

/** Vai trò người dùng trong hệ thống quản trị. */
public enum UserRole {
  /** Người dùng thường — chỉ chơi game và xem báo cáo của mình. */
  USER,

  /** Quản trị viên — xem danh sách user, phòng đang chơi, giao dịch đã thanh toán. */
  ADMIN
}
