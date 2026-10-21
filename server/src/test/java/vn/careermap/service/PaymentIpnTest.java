package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

  /**
   * IPN sai chữ ký phải để lại DẤU VẾT trong log.
   *
   * <p><b>Vì sao quan trọng:</b> IPN sai chữ ký bị từ chối với
   * {@code RspCode=97} ⇒ HTTP 200 ⇒ <b>VNPay KHÔNG retry</b> ⇒ đơn <b>không
   * bao giờ</b> chuyển PAID ⇒ hết 15 phút thành EXPIRED <b>dù tiền đã vào</b>.
   * Sau khi chuyển sang API 2.1.0 (HMAC-SHA512 + bỏ {@code vnp_SecureHashType}
   * khỏi chuỗi băm) thì rủi ro "chữ ký lệch" tăng mạnh ⇒ không có log thì
   * không chẩn đoán được chữ ký lệch ở đâu khi chạy giao dịch thật.
   *
   * <p>Log phải đủ để người vận hành DỰNG LẠI chuỗi băm (key + giá trị các
   * tham số nhận được) và so với chuỗi server đã gửi trong payUrl.
   */
  @Test
  void ipnSaiChuKyGhiLogDeChanDoan() {
    PaymentOrder order = PaymentOrder.create("order-log", "p", "R12345", 50000, 15);
    when(orderRepository.findById("order-log")).thenReturn(Optional.of(order));

    Map<String, String> params = signedParams("order-log", "00");
    params.put("vnp_SecureHash", "chu-ky-sai-abcdef0123456789");

    List<ILoggingEvent> warns = captureWarns(() -> {
      String result = service.handleIpn(params);

      // HÀNH VI KHÔNG ĐỔI: vẫn trả 97 (VNPay không retry ⇒ đơn không PAID).
      assertThat(result).isEqualTo("{\"RspCode\":\"97\",\"Message\":\"Fail checksum\"}");
      assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
    });

    assertThat(warns).hasSize(1);
    String message = warns.get(0).getFormattedMessage();
    // Đủ tham số để dựng lại chuỗi băm.
    assertThat(message).contains("vnp_TxnRef=order-log");
    assertThat(message).contains("vnp_Amount=5000000");
    assertThat(message).contains("vnp_TransactionStatus=00");
    // KHÔNG được lộ giá trị chữ ký (in ra nó là lộ chữ ký hợp lệ ⇒ kẻ xấu có
    // thể dò HashSecret offline).
    assertThat(message).doesNotContain("chu-ky-sai-abcdef0123456789");
    assertThat(message).contains("vnp_SecureHash=***");
  }

  /**
   * Giá trị tham số quá dài không được làm log phình vô hạn, và không được
   * chèn DÒNG LOG GIẢ (giá trị tham số do client kiểm soát được).
   */
  @Test
  void ipnSaiChuKyCatNganGiaTriDai() {
    Map<String, String> params = signedParams("order-log-long", "00");
    params.put("vnp_SecureHash", "chu-ky-sai");
    params.put("vnp_OrderInfo", "don-hang\n[INFO] dong log gia do client chen");
    params.put("vnp_BankCode", "x".repeat(500));

    List<ILoggingEvent> warns = captureWarns(() -> service.handleIpn(params));

    assertThat(warns).hasSize(1);
    String message = warns.get(0).getFormattedMessage();
    assertThat(message).doesNotContain("x".repeat(500));
    assertThat(message.length()).isLessThan(1000);
    // Không có newline thô ⇒ client không tạo được dòng log giả.
    assertThat(message).doesNotContain("\n");
    assertThat(message).contains("\\n");
  }

  /**
   * Log chẩn đoán IPN KHÔNG được để client chèn DÒNG LOG GIẢ.
   *
   * <p><b>Vì sao quan trọng:</b> {@code /api/payments/ipn} là endpoint CÔNG
   * KHAI, KHÔNG xác thực ⇒ ai cũng gọi được. Nếu một giá trị tham số (vd
   * {@code vnp_TxnRef}) được in THÔ vào message log thì client gửi
   * {@code vnp_TxnRef=%0A[INFO] ...} là chèn được dòng log giả vào log
   * Render ⇒ log bị giả mạo / đánh cắp.
   *
   * <p>Mọi giá trị tham số phải đi qua CÙNG một hàm escape + cắt (escape
   * {@code \r}/{@code \n} + cắt 120 ký tự) — giống hệt các tham số khác.
   */
  @Test
  void ipnSaiChuKyKhongChenDongLogGia() {
    Map<String, String> params =
        signedParams("abc\r\n[INFO] dong log gia do client chen", "00");
    params.put("vnp_SecureHash", "chu-ky-sai");

    List<ILoggingEvent> warns = captureWarns(() -> service.handleIpn(params));

    assertThat(warns).hasSize(1);
    String message = warns.get(0).getFormattedMessage();
    // KHÔNG có newline thô ⇒ client không tạo được dòng log giả.
    assertThat(message).doesNotContain("\n");
    assertThat(message).doesNotContain("\r");
    // Giá trị được escape y hệt các tham số khác (không phải bản thô).
    assertThat(message).contains("abc\\r\\n[INFO] dong log gia do client chen");
  }

  /**
   * Giá trị {@code vnp_TxnRef} quá dài cũng phải bị CẮT (giống các tham số
   * khác) — không được đẩy nguyên khối lớn vào log.
   */
  @Test
  void ipnSaiChuKyCatNganGiaTriDaiCuaTxnRef() {
    Map<String, String> params = signedParams("x".repeat(300), "00");
    params.put("vnp_SecureHash", "chu-ky-sai");

    List<ILoggingEvent> warns = captureWarns(() -> service.handleIpn(params));

    assertThat(warns).hasSize(1);
    String message = warns.get(0).getFormattedMessage();
    assertThat(message).doesNotContain("x".repeat(300));
    assertThat(message).contains("...(cắt bớt");
  }

  /**
   * Kẻ xấu replay một IPN THẬT (chữ ký hợp lệ) nhưng ĐỔI TÊN tham số chữ
   * ký ({@code vnp_SecureHash} → {@code vnp_securehash}) ⇒ giá trị chữ ký
   * thật KHÔNG được in ra log.
   *
   * <p><b>Vì sao quan trọng:</b> in ra chữ ký hợp lệ là lộ một chữ ký đúng
   * của VNPay ⇒ kẻ xấu dò được {@code HashSecret} offline (dictionary
   * attack) rồi tự ký đơn giả. Che theo đúng tên {@code vnp_SecureHash} là
   * không đủ — phải che theo TÊN không phân biệt hoa thường và theo GIÁ TRỊ.
   */
  @Test
  void ipnSaiChuKyKhongLoChuKyKhiDoiTenKey() {
    Map<String, String> genuine = signedParams("order-rename", "00");
    String validHash = genuine.get("vnp_SecureHash");

    Map<String, String> params = new LinkedHashMap<>(genuine);
    params.remove("vnp_SecureHash");
    params.put("vnp_securehash", validHash);

    List<ILoggingEvent> warns = captureWarns(() -> service.handleIpn(params));

    assertThat(warns).hasSize(1);
    String message = warns.get(0).getFormattedMessage();
    assertThat(message).doesNotContain(validHash);
    assertThat(message).contains("vnp_securehash=***");
  }

  /**
   * Kẻ xấu đổi tên tham số chữ ký HOÀN TOÀN (vd {@code vnp_h4sh}) ⇒ không
   * còn tên nào giống {@code vnp_SecureHash} để so khớp, nhưng giá trị vẫn
   * là chữ ký hợp lệ của bộ tham số gốc ⇒ cũng KHÔNG được lộ.
   */
  @Test
  void ipnSaiChuKyKhongLoChuKyKhiDoiTenKeyHoanToan() {
    Map<String, String> genuine = signedParams("order-rename-2", "00");
    String validHash = genuine.get("vnp_SecureHash");

    Map<String, String> params = new LinkedHashMap<>(genuine);
    params.remove("vnp_SecureHash");
    params.put("vnp_h4sh", validHash);

    List<ILoggingEvent> warns = captureWarns(() -> service.handleIpn(params));

    assertThat(warns).hasSize(1);
    String message = warns.get(0).getFormattedMessage();
    assertThat(message).doesNotContain(validHash);
    assertThat(message).contains("vnp_h4sh=***");
  }

  /**
   * Bản SAO của chữ ký đặt dưới tên tham số khác cũng phải bị che — không
   * được lộ chữ ký chỉ vì nó không nằm ở ô {@code vnp_SecureHash}.
   */
  @Test
  void ipnSaiChuKyKhongLoChuKyDoiDuoiTenKhac() {
    Map<String, String> genuine = signedParams("order-copy", "00");
    String validHash = genuine.get("vnp_SecureHash");

    Map<String, String> params = new LinkedHashMap<>(genuine);
    params.put("vnp_hash", validHash);

    List<ILoggingEvent> warns = captureWarns(() -> service.handleIpn(params));

    assertThat(warns).hasSize(1);
    String message = warns.get(0).getFormattedMessage();
    assertThat(message).doesNotContain(validHash);
  }

  /**
 * Che theo TÊN tham số KHÔNG phân biệt hoa thường — kể cả khi giá trị không
 * đúng hình dạng chữ ký VNPay (hex 64/128) thì tên đổi vẫn phải bị che.
 */
  @Test
  void ipnSaiChuKyCheTheoTenKhongPhanBietHoaThuong() {
    Map<String, String> params = signedParams("order-case", "00");
    params.remove("vnp_SecureHash");
    params.put("vnp_SECUREHASH", "chu-ky-khong-co-hinh-dang-hex");

    List<ILoggingEvent> warns = captureWarns(() -> service.handleIpn(params));

    assertThat(warns).hasSize(1);
    String message = warns.get(0).getFormattedMessage();
    assertThat(message).doesNotContain("chu-ky-khong-co-hinh-dang-hex");
    assertThat(message).contains("vnp_SECUREHASH=***");
  }

  /** Bắt log WARN của {@link PaymentService} trong lúc chạy khối lệnh. */
  private static List<ILoggingEvent> captureWarns(Runnable action) {
    Logger logger = (Logger) LoggerFactory.getLogger(PaymentService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      action.run();
      return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
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
