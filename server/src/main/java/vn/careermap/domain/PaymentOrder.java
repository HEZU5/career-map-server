package vn.careermap.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

/**
 * Một đơn thanh toán VNPay cho báo cáo Holland.
 *
 * <p>Đơn lưu riêng (không gắn khóa ngoại tới phòng/người chơi) vì báo cáo được
 * sinh SAU khi phòng đã có thể bị dọn — chỉ cần giữ {@code playerKey},
 * {@code roomCode} và số tiền để hiển thị và đối soát.
 *
 * <p>{@code id} chính là {@code vnp_TxnRef} do server sinh; VNPay báo về đúng
 * chuỗi này trong IPN nên đây là khoá tra đơn. Bảng được tạo tự động bởi
 * {@code ddl-auto=update} — không có migration.
 */
@Entity
@Table(name = "payment_orders")
public class PaymentOrder {

  /** Khoá tra đơn — khớp {@code vnp_TxnRef} của VNPay. */
  @Id
  @Column(nullable = false, unique = true, length = 64)
  private String id;

  /** Khoá người chơi mua — dùng tra/kiểm quyền sở hữu đơn. */
  @Column(nullable = false, length = 64)
  private String playerKey;

  /** Mã phòng lúc tạo đơn — chỉ để hiển thị/đối soát. */
  @Column(name = "room_code", nullable = false, length = 8)
  private String roomCode;

  /** Số tiền (VND) — do server đặt, không nhận từ client. */
  @Column(nullable = false)
  private long amount;

  /** Trạng thái đơn; mặc định khi tạo là {@link PaymentStatus#PENDING}. */
  @Enumerated(EnumType.STRING)
  @Column(
      nullable = false,
      length = 16,
      columnDefinition = "varchar(16) not null default 'PENDING'")
  private PaymentStatus status = PaymentStatus.PENDING;

  /** Lúc tạo đơn (do server đặt, không tin client). */
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** Hạn thanh toán — client đếm ngược từ mốc này. */
  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  /** Lúc VNPay xác nhận tiền vào; null khi chưa trả. */
  @Column(name = "paid_at")
  private Instant paidAt;

  /**
   * Ảnh chụp 6 điểm RIASEC lúc kết thúc ván, lưu dạng JSON ({@code {"R":4,...}}).
   * Dùng để tính mã Holland cho báo cáo sau khi phòng đã bị dọn.
   */
  @Column(name = "riasec_json", columnDefinition = "text")
  private String riasecJson;

  /**
   * Tạo đơn mới trong trạng thái {@code PENDING}.
   *
   * <p>{@code createdAt} và {@code expiresAt} lấy từ CÙNG một mốc {@code now}
   * nên {@code expiresAt - createdAt} luôn bằng đúng {@code ttlMinutes}, không
   * lệch theo thời điểm gọi {@code Instant.now()} hai lần.
   *
   * @param id khoá đơn (= {@code vnp_TxnRef}), do server sinh
   * @param ttlMinutes hạn đơn tính bằng phút (từ {@code payment.ttl-minutes})
   */
  public static PaymentOrder create(
      String id, String playerKey, String roomCode, long amount, long ttlMinutes) {
    PaymentOrder order = new PaymentOrder();
    order.id = id;
    order.playerKey = playerKey;
    order.roomCode = roomCode;
    order.amount = amount;
    Instant now = Instant.now();
    order.createdAt = now;
    order.expiresAt = now.plus(Duration.ofMinutes(ttlMinutes));
    order.status = PaymentStatus.PENDING;
    return order;
  }

  /** Đơn đã nhận được tiền chưa? Dùng khi chống IPN gọi lại nhiều lần. */
  public boolean isPaid() {
    return status == PaymentStatus.PAID;
  }

  /** Đơn còn hạn thanh toán tại {@code now}? */
  public boolean isExpiredAt(Instant now) {
    return now.isAfter(expiresAt);
  }

  /** Đánh dấu đã trả tiền tại {@code paidAt}. */
  public void markPaid(Instant paidAt) {
    this.status = PaymentStatus.PAID;
    this.paidAt = paidAt;
  }

  /** Đánh dấu đơn đã quá hạn (chưa nhận tiền). */
  public void markExpired() {
    this.status = PaymentStatus.EXPIRED;
  }

  public String getId() {
    return id;
  }

  public String getPlayerKey() {
    return playerKey;
  }

  public String getRoomCode() {
    return roomCode;
  }

  public long getAmount() {
    return amount;
  }

  public PaymentStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getPaidAt() {
    return paidAt;
  }

  public String getRiasecJson() {
    return riasecJson;
  }

  public void setRiasecJson(String riasecJson) {
    this.riasecJson = riasecJson;
  }
}
