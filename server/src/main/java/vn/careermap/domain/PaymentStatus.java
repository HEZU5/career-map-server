package vn.careermap.domain;

/**
 * Trạng thái đơn thanh toán.
 *
 * <p>Lưu dạng chuỗi ({@code @Enumerated(EnumType.STRING)}) để dữ liệu trong
 * bảng đọc được bằng mắt người và không phụ thuộc thứ tự khai báo hằng — thêm
 * hằng mới ở giữa không làm lệch giá trị đã lưu.
 */
public enum PaymentStatus {
  /** Vừa tạo, đang chờ người dùng chuyển tiền. */
  PENDING,

  /** VNPay đã xác nhận tiền vào. */
  PAID,

  /** Đã quá hạn thanh toán mà chưa nhận được tiền. */
  EXPIRED
}
