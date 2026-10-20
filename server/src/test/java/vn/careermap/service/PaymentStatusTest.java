package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.config.VnpayConfig;
import vn.careermap.domain.PaymentOrder;
import vn.careermap.domain.PaymentStatus;
import vn.careermap.repo.PaymentOrderRepository;
import vn.careermap.web.dto.PaymentHistoryItem;
import vn.careermap.web.dto.PaymentStatusResponse;

class PaymentStatusTest {

  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, false);
  private static final VnpayConfig VNPAY =
      new VnpayConfig(true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn");

  private final VnpaySignatureService signatureService = new VnpaySignatureService(VNPAY);
  private final PaymentOrderRepository orderRepository = mock(PaymentOrderRepository.class);

  private final PaymentService service =
      new PaymentService(RULES, null, orderRepository, null, signatureService, VNPAY);

  @Test
  void donHetHanThiTraVeExpired() {
    PaymentOrder order = PaymentOrder.create("order1", "p", "R12345", 50000, -1);
    when(orderRepository.findByIdAndPlayerKey("order1", "p")).thenReturn(Optional.of(order));
    when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    PaymentStatusResponse res = service.getStatus("order1", "p");

    assertThat(res.status()).isEqualTo("EXPIRED");
    assertThat(order.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    verify(orderRepository).save(order);
  }

  @Test
  void donCuaNguoiKhacKhongXemDuoc() {
    when(orderRepository.findByIdAndPlayerKey("order2", "p2")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getStatus("order2", "p2"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e ->
            assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void lichSuMoiNhatTruoc() {
    PaymentOrder o1 = PaymentOrder.create("o1", "p", "R12345", 50000, 15);
    PaymentOrder o2 = PaymentOrder.create("o2", "p", "R12345", 50000, 15);
    PaymentOrder o3 = PaymentOrder.create("o3", "p", "R12345", 50000, 15);
    when(orderRepository.findByPlayerKeyOrderByCreatedAtDesc("p"))
        .thenReturn(List.of(o3, o2, o1));

    List<PaymentHistoryItem> history = service.history("p");

    assertThat(history).extracting(PaymentHistoryItem::orderId)
        .containsExactly("o3", "o2", "o1");
    assertThat(history).extracting(PaymentHistoryItem::createdAt)
        .isSortedAccordingTo(Comparator.reverseOrder());
  }

  /**
   * Lịch sử KHÔNG được chứa mã Holland.
   *
   * <p>{@code hollandCode} là 1 chữ cái đủ để NGƯỜI KHÁC đoán ra loại báo cáo
   * của người chơi chỉ với {@code playerKey} (chuỗi đoán được). Client hiện
   * không gọi endpoint lịch sử này nên bỏ đi được, chi phí bằng 0.
   */
  @Test
  void lichSuKhongChuaMaHolland() {
    PaymentOrder paid = PaymentOrder.create("paid", "p", "R12345", 50000, 15);
    paid.markPaid(Instant.now());
    paid.setRiasecJson("{\"R\":4,\"I\":2,\"A\":1,\"S\":6,\"E\":3,\"C\":0}");
    when(orderRepository.findByPlayerKeyOrderByCreatedAtDesc("p"))
        .thenReturn(List.of(paid));

    List<PaymentHistoryItem> history = service.history("p");

    assertThat(history).hasSize(1);
    assertThat(history.get(0).status()).isEqualTo("PAID");
    assertThat(PaymentHistoryItem.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .doesNotContain("hollandCode");
  }

  /**
   * Lịch sử là endpoint CÔNG KHAI theo {@code playerKey} — mà {@code playerKey}
   * đoán được ({@code host-<mã phòng>}) và mã phòng nằm trong danh sách phòng
   * công khai. Nên lịch sử KHÔNG được chứa nội dung báo cáo ({@code
   * riasecJson}) — nếu có thì ai cũng đọc được báo cáo của người khác, kể cả
   * đơn CHƯA thanh toán.
   */
  @Test
  void lichSuKhongChuaNoiDungBaoCao() {
    assertThat(PaymentHistoryItem.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .doesNotContain("riasecJson");
  }

  /** Lịch sử trả metadata đầy đủ (orderId, status, amount, createdAt, expiresAt). */
  @Test
  void lichSuTraDayDuMetadata() {
    PaymentOrder paid = PaymentOrder.create("paid", "p", "R12345", 50000, 15);
    paid.markPaid(Instant.now());
    paid.setRiasecJson("{\"R\":4,\"I\":2,\"A\":1,\"S\":6,\"E\":3,\"C\":0}");
    when(orderRepository.findByPlayerKeyOrderByCreatedAtDesc("p")).thenReturn(List.of(paid));

    PaymentHistoryItem item = service.history("p").get(0);

    assertThat(item.orderId()).isEqualTo("paid");
    assertThat(item.status()).isEqualTo("PAID");
    assertThat(item.amount()).isEqualTo(50000L);
    assertThat(item.createdAt()).isEqualTo(paid.getCreatedAt());
    assertThat(item.expiresAt()).isEqualTo(paid.getExpiresAt());
  }

  /** Tra trạng thái đơn của CHÍNH mình thì có nội dung báo cáo + thời điểm tạo đơn. */
  @Test
  void traTrangThaiDonCuaMinhCoNoiDungBaoCao() {
    PaymentOrder paid = PaymentOrder.create("mine", "p", "R12345", 50000, 15);
    paid.markPaid(Instant.now());
    paid.setRiasecJson("{\"R\":4,\"I\":2,\"A\":1,\"S\":6,\"E\":3,\"C\":0}");
    when(orderRepository.findByIdAndPlayerKey("mine", "p")).thenReturn(Optional.of(paid));

    PaymentStatusResponse res = service.getStatus("mine", "p");

    assertThat(res.riasecJson()).contains("\"S\":6");
    assertThat(res.hollandCode()).isEqualTo("S");
    assertThat(res.createdAt()).isEqualTo(paid.getCreatedAt());
  }
}
