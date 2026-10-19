package vn.careermap.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Luật vòng đời đơn thanh toán.
 *
 * <p>Test thuần logic, không Spring context, không database — dựng đơn trực tiếp
 * qua factory giống các test domain khác (xem {@code RoomLifecycleTest}).
 */
class PaymentOrderTest {

  private static final long AMOUNT_VND = 50_000L;
  private static final long TTL_MINUTES = 15L;

  private static PaymentOrder newOrder(String id) {
    return PaymentOrder.create(id, "player-1", "H12345", AMOUNT_VND, TTL_MINUTES);
  }

  @Test
  void donMoiMacDinhLaPendingVaCoHan() {
    PaymentOrder order = newOrder("vnp-1");

    assertThat(order.getId()).isEqualTo("vnp-1");
    assertThat(order.getPlayerKey()).isEqualTo("player-1");
    assertThat(order.getRoomCode()).isEqualTo("H12345");
    assertThat(order.getAmount()).isEqualTo(AMOUNT_VND);
    assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(order.getCreatedAt()).isNotNull();
    assertThat(order.getExpiresAt()).isNotNull();
    // Chưa trả tiền thì chưa có mốc paidAt.
    assertThat(order.getPaidAt()).isNull();
  }

  @Test
  void hanDungKhiTao() {
    PaymentOrder order = newOrder("vnp-2");

    Duration han = Duration.between(order.getCreatedAt(), order.getExpiresAt());
    assertThat(han).isEqualTo(Duration.ofMinutes(TTL_MINUTES));
  }

  @Test
  void danhDauDaThanhToanDatPaidAt() {
    PaymentOrder order = newOrder("vnp-3");
    java.time.Instant paidAt = order.getCreatedAt().plusSeconds(30);

    order.markPaid(paidAt);

    assertThat(order.getStatus()).isEqualTo(PaymentStatus.PAID);
    assertThat(order.getPaidAt()).isEqualTo(paidAt);
  }

  @Test
  void donQuaHanLaExpired() {
    PaymentOrder order = newOrder("vnp-4");

    order.markExpired();

    assertThat(order.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
  }
}
