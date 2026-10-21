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
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, false, 5L);
  private static final VnpayConfig VNPAY =
      new VnpayConfig(true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn");

  /**
   * Ảnh chụp RIASEC mẫu — nhóm {@code I} điểm cao nhất ⇒ mã Holland {@code "I"}.
   *
   * <p>Dùng chung cho các test dưới để assert cả 2 vế: nội dung báo cáo (6
   * điểm) bị che hay không, còn mã Holland thì luôn có.
   */
  private static final String RIASEC = "{\"R\":0,\"I\":14,\"A\":2,\"S\":8,\"E\":2,\"C\":4}";

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

  // ── Nội dung báo cáo: CHỈ trả khi đơn ĐÃ thanh toán ────────────────────────

  /**
   * Đơn CHƯA thanh toán (PENDING) ⇒ KHÔNG được trả nội dung báo cáo.
   *
   * <p>Lỗ hổng đã kiểm chứng live trên production: {@code GET
   * /api/payments/{orderId}?playerKey=} trả {@code riasecJson} (6 điểm RIASEC
   * = toàn bộ nội dung báo cáo Holland) cho cả đơn CHƯA thanh toán ⇒ người
   * chơi tạo đơn rồi gọi API là có báo cáo MIỄN PHÍ (đúng thứ 50.000đ phải
   * mua).
   *
   * <p>Nội dung báo cáo là thứ ĐÃ TRẢ TIỀN ⇒ chỉ trả khi đơn {@code PAID}.
   * Mã Holland (1 chữ) thì LUÔN trả — chủ dự án chấp nhận mức lộ này.
   */
  @Test
  void donPendingKhongLoNoiDungBaoCao() {
    PaymentOrder pending = PaymentOrder.create("pending", "p", "R12345", 50000, 15);
    pending.setRiasecJson(RIASEC);
    when(orderRepository.findByIdAndPlayerKey("pending", "p")).thenReturn(Optional.of(pending));

    PaymentStatusResponse res = service.getStatus("pending", "p");

    assertThat(res.status()).isEqualTo("PENDING");
    assertThat(res.riasecJson()).isEmpty();
    // Mã Holland vẫn đúng (được phép lộ 1 chữ).
    assertThat(res.hollandCode()).isEqualTo("I");
  }

  /**
   * Đơn HẾT HẠN (EXPIRED) ⇒ cũng KHÔNG được trả nội dung báo cáo.
   *
   * <p>Đây chính là bằng chứng đã kiểm chứng live: đơn EXPIRED (chưa trả đồng
   * nào) mà vẫn trả {@code riasecJson}.
   */
  @Test
  void donExpiredKhongLoNoiDungBaoCao() {
    PaymentOrder expired = PaymentOrder.create("expired", "p", "R12345", 50000, -1);
    expired.setRiasecJson(RIASEC);
    when(orderRepository.findByIdAndPlayerKey("expired", "p")).thenReturn(Optional.of(expired));
    when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    PaymentStatusResponse res = service.getStatus("expired", "p");

    assertThat(res.status()).isEqualTo("EXPIRED");
    assertThat(res.riasecJson()).isEmpty();
    assertThat(res.hollandCode()).isEqualTo("I");
  }

  /**
   * Đơn ĐÃ thanh toán ⇒ vẫn trả đầy đủ nội dung báo cáo + mã Holland.
   *
   * <p>Chống hồi quy: việc che báo cáo phải KHÔNG làm hỏng luồng mua hợp lệ —
   * người chơi đã trả tiền thì vẫn đọc được báo cáo.
   */
  @Test
  void donPaidVanLoNoiDungBaoCaoDayDu() {
    PaymentOrder paid = PaymentOrder.create("paid-order", "p", "R12345", 50000, 15);
    paid.markPaid(Instant.now());
    paid.setRiasecJson(RIASEC);
    when(orderRepository.findByIdAndPlayerKey("paid-order", "p")).thenReturn(Optional.of(paid));

    PaymentStatusResponse res = service.getStatus("paid-order", "p");

    assertThat(res.status()).isEqualTo("PAID");
    assertThat(res.riasecJson()).isEqualTo(RIASEC);
    assertThat(res.hollandCode()).isEqualTo("I");
  }

  /**
   * Đơn ĐÃ thanh toán nhưng bản chụp RIASEC rỗng ⇒ trả {@code ""}, không lỗi.
   *
   * <p>Phòng bị dọn sớm có thể làm ảnh chụp mất ⇒ không được ném lỗi 500.
   */
  @Test
  void donPaidKhongCoAnhChupRiasecVanTraChuoiRong() {
    PaymentOrder paid = PaymentOrder.create("paid-rong", "p", "R12345", 50000, 15);
    paid.markPaid(Instant.now());
    paid.setRiasecJson(null);
    when(orderRepository.findByIdAndPlayerKey("paid-rong", "p")).thenReturn(Optional.of(paid));

    PaymentStatusResponse res = service.getStatus("paid-rong", "p");

    assertThat(res.status()).isEqualTo("PAID");
    assertThat(res.riasecJson()).isEmpty();
    assertThat(res.hollandCode()).isEmpty();
  }
}
