package vn.careermap.domain;

/** Vai trò người dùng trong hệ thống quản trị. */
public enum UserRole {
  /** Người dùng thường — chỉ chơi game và xem báo cáo của mình. */
  USER,

  /** Quản trị viên hệ thống — quản lý người dùng và phòng đang chơi, KHÔNG xem doanh thu. */
  ADMIN,

  /** Nhân viên kế toán — xem giao dịch thanh toán (doanh thu), KHÔNG quản lý user/phòng. */
  ACCOUNTANT
}
