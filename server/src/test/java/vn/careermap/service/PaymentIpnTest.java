package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.dao.DataAccessResourceFailureException;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.config.VnpayConfig;
import vn.careermap.domain.PaymentOrder;
import vn.careermap.domain.PaymentStatus;
import vn.careermap.exception.PaymentInfrastructureException;
import vn.careermap.repo.PaymentOrderRepository;

class PaymentIpnTest {

  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, false, 5L);
  private static final VnpayConfig VNPAY =
      new VnpayConfig(true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn");

  private final VnpaySignatureService signatureService = new VnpaySignatureService(VNPAY);
  private final PaymentOrderRepository orderRepository = mock(PaymentOrderRepository.class);

  private final PaymentService service =
      new PaymentService(RULES, null, orderRepository, null, signatureService, VNPAY);

  private static Map<String, String> signedParams(String txnRef, String txnStatus) {
    return signedParams(txnRef, txnStatus, 50000L);
  }

  private static Map<String, String> signedParams(String txnRef, String txnStatus, long amountVnd) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("vnp_TxnRef", txnRef);
    params.put("vnp_TransactionStatus", txnStatus);
    params.put("vnp_Amount", String.valueOf(amountVnd * 100));
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

    // Mặc định là API 2.1.0 (vnp_Command=pay) => mã thành công là "00".
    String result = service.handleIpn(signedParams("order1", "00"));

    assertThat(result).isEqualTo("{\"RspCode\":\"00\",\"Message\":\"Confirm Success\"}");
    assertThat(order.getStatus()).isEqualTo(PaymentStatus.PAID);
    assertThat(order.getPaidAt()).isNotNull();
    verify(orderRepository).save(order);
  }

  @Test
  void ipnSaiChuKyKhongDoiTrangThai() {
    PaymentOrder order = PaymentOrder.create("order2", "p", "R12345", 50000, 15);
    when(orderRepository.findById("order2")).thenReturn(Optional.of(order));

    Map<String, String> params = signedParams("order2", "00");
    params.put("vnp_SecureHash", "chu-ky-sai");
    String result = service.handleIpn(params);

    assertThat(result).isEqualTo("{\"RspCode\":\"97\",\"Message\":\"Fail checksum\"}");
    assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
    verify(orderRepository, never()).save(any());
  }

  @Test
  void ipnOrderIdKhongTonTaiKhongNemLoi() {
    when(orderRepository.findById("missing")).thenReturn(Optional.empty());

    String result = service.handleIpn(signedParams("missing", "00"));

    assertThat(result).isEqualTo("{\"RspCode\":\"01\",\"Message\":\"Order not found\"}");
  }

  @Test
  void ipnDenSauKhiDonHetHanVanThanhPAID() {
    PaymentOrder order = PaymentOrder.create("order3", "p", "R12345", 50000, 15);
    order.markExpired();
    when(orderRepository.findById("order3")).thenReturn(Optional.of(order));
    when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    String result = service.handleIpn(signedParams("order3", "00"));

    assertThat(result).isEqualTo("{\"RspCode\":\"00\",\"Message\":\"Confirm Success\"}");
    assertThat(order.getStatus()).isEqualTo(PaymentStatus.PAID);
  }

  @Test
  void ipnGoiLaiNhieuLanKhongSai() {
    PaymentOrder order = PaymentOrder.create("order4", "p", "R12345", 50000, 15);
    when(orderRepository.findById("order4")).thenReturn(Optional.of(order));
    when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.handleIpn(signedParams("order4", "00"));
    Instant firstPaidAt = order.getPaidAt();

    String r2 = service.handleIpn(signedParams("order4", "00"));
    String r3 = service.handleIpn(signedParams("order4", "00"));

    assertThat(r2).isEqualTo("{\"RspCode\":\"02\",\"Message\":\"Order already confirmed\"}");
    assertThat(r3).isEqualTo("{\"RspCode\":\"02\",\"Message\":\"Order already confirmed\"}");
    assertThat(order.getPaidAt()).isEqualTo(firstPaidAt);
  }

  /**
   * Giao dịch THẤT BẠI (status khác mã thành công) ⇒ mã KẾT THÚC LUỒNG.
   *
   * <p>KHÔNG được trả 99: 99 là mã "merchant chưa cập nhật được" ⇒ VNPay retry
   * tối đa 10 lần (cách nhau 5 phút) cho một giao dịch đã thất bại ở ngân
   * hàng ⇒ 10 lần retry cũng không bao giờ thành công, chỉ tối 50 phút.
   */
  @Test
  void ipnTransactionStatusKhacMaThanhCongKhongDoi() {
    PaymentOrder order = PaymentOrder.create("order5", "p", "R12345", 50000, 15);
    when(orderRepository.findById("order5")).thenReturn(Optional.of(order));

    // API 2.1.0: "00" là mã THÀNH CÔNG, "01" là giao dịch KHÔNG thành công.
    Map<String, String> params = signedParams("order5", "01");
    String result = service.handleIpn(params);

    assertThat(result).isEqualTo("{\"RspCode\":\"02\",\"Message\":\"Order not confirmed\"}");
    assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
    verify(orderRepository, never()).save(any());
  }

  /**
   * API 2.1.0 ({@code vnp_Command=pay}) báo THÀNH CÔNG bằng
   * {@code vnp_TransactionStatus=00} — khác API cũ (01).
   *
   * <p>Nếu không phân biệt thì IPN của API 2.1.0 bị coi là thất bại ⇒ đơn kẹt
   * PENDING dù tiền đã vào.
   */
  @Test
  void ipnApi21CoStatus00ThiThanhCong() {
    VnpayConfig api21 =
        new VnpayConfig(
            true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
            "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html", "SHA512", "",
            "pay", "2.1.0", "other");
    PaymentService api21Service =
        new PaymentService(RULES, null, orderRepository, null, new VnpaySignatureService(api21), api21);

    PaymentOrder order = PaymentOrder.create("order7", "p", "R12345", 50000, 15);
    when(orderRepository.findById("order7")).thenReturn(Optional.of(order));
    when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, String> params = new LinkedHashMap<>();
    params.put("vnp_TxnRef", "order7");
    params.put("vnp_TransactionStatus", "00");
    params.put("vnp_Amount", "5000000");
    params.put("vnp_SecureHash", new VnpaySignatureService(api21).sign(params));

    String result = api21Service.handleIpn(params);

    assertThat(result).isEqualTo("{\"RspCode\":\"00\",\"Message\":\"Confirm Success\"}");
    assertThat(order.getStatus()).isEqualTo(PaymentStatus.PAID);
  }

  /**
   * Lỗi HẠ TẦNG (DB lạnh, connection reset) KHÔNG được nuốt thành HTTP 200.
   *
   * <p>Trả 200 ở đây = VNPay KHÔNG retry ⇒ đơn kẹt PENDING ⇒ hết 15 phút thành
   * EXPIRED dù tiền ĐÃ vào. Vì vậy exception phải ném ra ⇒ HTTP 5xx ⇒ retry.
   */
  @Test
  void loiHaTauPhaiNemRaDeVnPayRetry() {
    when(orderRepository.findById("order6"))
        .thenThrow(new DataAccessResourceFailureException("Neon lạnh - connection reset"));

    assertThatThrownBy(() -> service.handleIpn(signedParams("order6", "00")))
        .isInstanceOf(PaymentInfrastructureException.class);
  }
}
