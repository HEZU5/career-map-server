package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.config.VnpayConfig;
import vn.careermap.domain.PaymentOrder;
import vn.careermap.domain.PaymentStatus;
import vn.careermap.repo.PaymentOrderRepository;

class PaymentIpnTest {

  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12);
  private static final VnpayConfig VNPAY =
      new VnpayConfig(true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn");

  private final VnpaySignatureService signatureService = new VnpaySignatureService(VNPAY);
  private final PaymentOrderRepository orderRepository = mock(PaymentOrderRepository.class);

  private final PaymentService service =
      new PaymentService(RULES, null, orderRepository, null, signatureService, VNPAY);

  private static Map<String, String> signedParams(String txnRef, String txnStatus) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("vnp_TxnRef", txnRef);
    params.put("vnp_TransactionStatus", txnStatus);
    String hash = new VnpaySignatureService(
        new VnpayConfig(true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn"))
        .sign(params);
    params.put("vnp_SecureHash", hash);
    return params;
  }

  @Test
  void ipnHopLeThiDonThanhPAID() {
    PaymentOrder order = PaymentOrder.create("order1", "p", "R12345", 50000, 15);
    when(orderRepository.findById("order1")).thenReturn(Optional.of(order));
    when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    String result = service.handleIpn(signedParams("order1", "01"));

    assertThat(result).isEqualTo("Transaction Status: 01");
    assertThat(order.getStatus()).isEqualTo(PaymentStatus.PAID);
    assertThat(order.getPaidAt()).isNotNull();
    verify(orderRepository).save(order);
  }

  @Test
  void ipnSaiChuKyKhongDoiTrangThai() {
    PaymentOrder order = PaymentOrder.create("order2", "p", "R12345", 50000, 15);
    when(orderRepository.findById("order2")).thenReturn(Optional.of(order));

    Map<String, String> params = signedParams("order2", "01");
    params.put("vnp_SecureHash", "chu-ky-sai");
    String result = service.handleIpn(params);

    assertThat(result).isEqualTo("Transaction Status: 01");
    assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
    verify(orderRepository, never()).save(any());
  }

  @Test
  void ipnOrderIdKhongTonTaiKhongNemLoi() {
    when(orderRepository.findById("missing")).thenReturn(Optional.empty());

    String result = service.handleIpn(signedParams("missing", "01"));

    assertThat(result).isEqualTo("Transaction Status: 01");
  }

  @Test
  void ipnDenSauKhiDonHetHanVanThanhPAID() {
    PaymentOrder order = PaymentOrder.create("order3", "p", "R12345", 50000, 15);
    order.markExpired();
    when(orderRepository.findById("order3")).thenReturn(Optional.of(order));
    when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    String result = service.handleIpn(signedParams("order3", "01"));

    assertThat(result).isEqualTo("Transaction Status: 01");
    assertThat(order.getStatus()).isEqualTo(PaymentStatus.PAID);
  }

  @Test
  void ipnGoiLaiNhieuLanKhongSai() {
    PaymentOrder order = PaymentOrder.create("order4", "p", "R12345", 50000, 15);
    when(orderRepository.findById("order4")).thenReturn(Optional.of(order));
    when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.handleIpn(signedParams("order4", "01"));
    Instant firstPaidAt = order.getPaidAt();

    String r2 = service.handleIpn(signedParams("order4", "01"));
    String r3 = service.handleIpn(signedParams("order4", "01"));

    assertThat(r2).isEqualTo("Transaction Status: 01");
    assertThat(r3).isEqualTo("Transaction Status: 01");
    assertThat(order.getPaidAt()).isEqualTo(firstPaidAt);
  }

  @Test
  void ipnTransactionStatusKhac01KhongDoi() {
    PaymentOrder order = PaymentOrder.create("order5", "p", "R12345", 50000, 15);
    when(orderRepository.findById("order5")).thenReturn(Optional.of(order));

    Map<String, String> params = signedParams("order5", "00");
    String result = service.handleIpn(params);

    assertThat(result).isEqualTo("Transaction Status: 01");
    assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
    verify(orderRepository, never()).save(any());
  }
}