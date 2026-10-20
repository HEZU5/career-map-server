package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.config.VnpayConfig;
import vn.careermap.domain.PaymentOrder;
import vn.careermap.domain.PaymentStatus;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.repo.PaymentOrderRepository;
import vn.careermap.repo.PlayerRepository;
import vn.careermap.web.dto.CreatePaymentResponse;
import vn.careermap.web.dto.WinnerResponse;

/**
 * Tạo đơn VNPay: kiểm tra điều kiện thắng, tái dùng đơn PENDING còn hạn, ký
 * chữ ký HMAC-SHA256 thật bằng {@link VnpaySignatureService} (không mock).
 *
 * <p>Không Spring context, không DB: chỉ mock repository và
 * {@link GameService} (vì {@code isEligibleToWin} là private trong GameService;
 * phải gọi qua {@code checkWinner}).
 */
class PaymentServiceTest {

  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, false);
  private static final VnpayConfig VNPAY =
      new VnpayConfig(true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn");

  private final VnpaySignatureService signatureService = new VnpaySignatureService(VNPAY);
  private final GameService gameService = mock(GameService.class);
  private final PlayerRepository playerRepository = mock(PlayerRepository.class);
  private final PaymentOrderRepository orderRepository = mock(PaymentOrderRepository.class);

  private final PaymentService service =
      new PaymentService(RULES, playerRepository, orderRepository, gameService, signatureService, VNPAY);

  private static Player player(String key, int laps, int answered) {
    Player p = Player.create(key, "Nguoi Choi");
    Room room = Room.create("R12345", "Chu Phong", false, "owner");
    room.addPlayer(p);
    p.addCompletedLaps(laps);
    for (int i = 0; i < answered; i++) {
      p.incrementTotalAnswered();
    }
    return p;
  }

  private static Map<String, String> parseQuery(String payUrl) {
    String query = payUrl.substring(payUrl.indexOf('?') + 1);
    Map<String, String> params = new LinkedHashMap<>();
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      params.put(
          URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return params;
  }

  private static void assertForbidden(Throwable thrown) {
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void taoDonChoNguoiDuDieuKien() {
    when(playerRepository.findByPlayerKey("winner")).thenReturn(Optional.of(player("winner", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "winner", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());
    when(orderRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    CreatePaymentResponse response = service.createOrder("winner");

    ArgumentCaptor<PaymentOrder> saved = ArgumentCaptor.forClass(PaymentOrder.class);
    verify(orderRepository).save(saved.capture());
    PaymentOrder order = saved.getValue();

    assertThat(order.getId()).matches("[0-9a-f]{32}");
    assertThat(order.getAmount()).isEqualTo(50000L);
    assertThat(order.getPlayerKey()).isEqualTo("winner");

    assertThat(response.orderId()).isEqualTo(order.getId());
    assertThat(response.expiresAt()).isEqualTo(order.getExpiresAt());

    assertThat(response.payUrl())
        .startsWith("https://sandbox.vnpayment.vn/paymentgateway/paymenturl.htm?");
    assertThat(response.payUrl()).contains("vnp_TxnRef=" + order.getId());
    assertThat(response.payUrl()).contains("vnp_Amount=5000000");
    assertThat(response.payUrl()).contains("vnp_CurrCode=VND");
    assertThat(response.payUrl()).contains("vnp_Locale=vn");
    assertThat(response.payUrl()).contains("vnp_Command=pay_web");
  }

  @Test
  void nguoiChuaDuKhongTaoDuoc() {
    when(playerRepository.findByPlayerKey("almost"))
        .thenReturn(Optional.of(player("almost", 1, 11)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(false, 11, 8));

    assertThatThrownBy(() -> service.createOrder("almost"))
        .satisfies(PaymentServiceTest::assertForbidden);

    verify(orderRepository, never()).save(any());
  }

  @Test
  void khongPhaiNguoiThatRaKhongTaoDuoc() {
    when(playerRepository.findByPlayerKey("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.createOrder("ghost"))
        .satisfies(PaymentServiceTest::assertForbidden);

    verify(gameService, never()).checkWinner(any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void taoLaiThiTraLaiDonCungDangCho() {
    when(playerRepository.findByPlayerKey("again"))
        .thenReturn(Optional.of(player("again", 2, 20)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 20, 8));
    PaymentOrder pending = PaymentOrder.create(
        "0123456789abcdef0123456789abcdef", "again", "R12345", 50000, 15);
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "again", PaymentStatus.PENDING))
        .thenReturn(Optional.of(pending));

    CreatePaymentResponse response = service.createOrder("again");

    assertThat(response.orderId()).isEqualTo(pending.getId());
    assertThat(response.expiresAt()).isEqualTo(pending.getExpiresAt());
    assertThat(response.payUrl()).contains("vnp_TxnRef=" + pending.getId());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void donCuDaHanThiTaoDonMoi() {
    when(playerRepository.findByPlayerKey("old"))
        .thenReturn(Optional.of(player("old", 2, 20)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 20, 8));
    PaymentOrder expired = PaymentOrder.create(
        "0123456789abcdef0123456789abcdef", "old", "R12345", 50000, -1);
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "old", PaymentStatus.PENDING))
        .thenReturn(Optional.of(expired));
    when(orderRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    CreatePaymentResponse response = service.createOrder("old");

    assertThat(response.orderId()).isNotEqualTo(expired.getId());
    verify(orderRepository).save(any());
  }

  @Test
  void payUrlCoVnpTxnRefLaIdCuaDon() {
    when(playerRepository.findByPlayerKey("ref"))
        .thenReturn(Optional.of(player("ref", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "ref", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());
    when(orderRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    CreatePaymentResponse response = service.createOrder("ref");

    assertThat(response.payUrl()).contains("vnp_TxnRef=" + response.orderId());

    Map<String, String> params = parseQuery(response.payUrl());
    String hash = params.remove("vnp_SecureHash");
    assertThat(hash).isNotNull();
    assertThat(hash).hasSize(64);
    assertThat(signatureService.sign(params)).isEqualTo(hash);
  }
}
