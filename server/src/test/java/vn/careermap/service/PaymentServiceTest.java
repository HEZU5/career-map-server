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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.config.VnpayConfig;
import vn.careermap.domain.PaymentOrder;
import vn.careermap.domain.PaymentStatus;
import vn.careermap.domain.Player;
import vn.careermap.domain.RiasecCategory;
import vn.careermap.domain.Room;
import vn.careermap.domain.RoomStatus;
import vn.careermap.repo.PaymentOrderRepository;
import vn.careermap.repo.PlayerRepository;
import vn.careermap.web.dto.CreatePaymentResponse;
import vn.careermap.web.dto.WinnerResponse;

/**
 * Tạo đơn VNPay: kiểm tra điều kiện thắng, tái sử dụng đơn PENDING còn hạn, ký
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

  /**
   * Người chơi đã chơi xong ván: phòng ĐÃ KẾT THÚC và đã có ảnh chụp RIASEC
   * riêng (đúng trạng thái thật lúc người chơi bấm "Nhận báo cáo").
   */
  private static Player player(String key, int laps, int answered) {
    Player p = Player.create(key, "Nguoi Choi");
    Room room = Room.create("R12345", "Chu Phong", false, "owner");
    room.addPlayer(p);
    p.addCompletedLaps(laps);
    for (int i = 0; i < answered; i++) {
      p.incrementTotalAnswered();
    }
    p.scoreOf(RiasecCategory.R).addPoints(4);
    p.scoreOf(RiasecCategory.S).addPoints(6);
    room.setStatus(RoomStatus.FINISHED);
    room.captureRiasecSnapshot();
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

  // ── Ván chưa kết thúc / phòng đã bị dọn ──────────────────────────────────

  /**
   * Ván CHƯA kết thúc thì không bán: điểm lúc đó còn dở dang, mua xong báo cáo
   * sai mà tiền cũng mất.
   */
  @Test
  void vanChuaKetThucThiKhongTaoDuocDon() {
    Player p = player("busy", 1, 12);
    p.getRoom().setStatus(RoomStatus.PLAYING);
    when(playerRepository.findByPlayerKey("busy")).thenReturn(Optional.of(p));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));

    assertThatThrownBy(() -> service.createOrder("busy"))
        .satisfies(thrown -> {
          PaymentServiceTest.assertForbidden(thrown);
          assertThat(((ResponseStatusException) thrown).getReason()).contains("Ván chưa kết thúc");
        });

    verify(orderRepository, never()).save(any());
  }

  /**
   * Phòng đã bị {@code RoomJanitor} dọn ⇒ cascade xoá {@code Player} ⇒ cả dòng
   * người chơi biến mất ⇒ KHÔNG tạo được đơn (không phải 500, không phải đơn
   * rác PENDING).
   */
  @Test
  void phongDaBiDonThiKhongTaoDuocDon() {
    // Mô phỏng đúng thứ tự cascade: xoá phòng -> xoá Player -> xoá Score.
    Player p = player("orphan", 1, 12);
    Room room = p.getRoom();
    room.getPlayers().remove(p);
    room.setRiasecSnapshot(new java.util.LinkedHashMap<>());
    // Dòng người chơi đã bị xoá theo => repository không còn trả về.
    when(playerRepository.findByPlayerKey("orphan")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.createOrder("orphan"))
        .satisfies(thrown -> {
          PaymentServiceTest.assertForbidden(thrown);
          assertThat(((ResponseStatusException) thrown).getReason()).contains("Không tìm thấy");
        });

    verify(orderRepository, never()).save(any());
  }

  /** Người chơi còn tồn tại nhưng đã không ở phòng nào nữa → 403 rõ ràng. */
  @Test
  void nguoiChoiKhongConPhongThiKhongTaoDuocDon() {
    Player orphan = Player.create("noroom", "Khach");
    when(playerRepository.findByPlayerKey("noroom")).thenReturn(Optional.of(orphan));

    assertThatThrownBy(() -> service.createOrder("noroom"))
        .satisfies(thrown -> {
          PaymentServiceTest.assertForbidden(thrown);
          assertThat(((ResponseStatusException) thrown).getReason())
              .contains("không còn ở phòng nào");
        });

    verify(orderRepository, never()).save(any());
  }

  /**
   * Ván đã kết thúc nhưng ảnh chụp RIASEC không còn (phòng bị dọn sạch) ⇒
   * KHÔNG được im lặng tạo báo cáo RỘNG — phải trả 403 rõ ràng.
   */
  @Test
  void khongCoAnhChupThiKhongTaoDuocDon() {
    Player p = player("nosnap", 1, 12);
    p.getRoom().setRiasecSnapshot(new java.util.LinkedHashMap<>());
    when(playerRepository.findByPlayerKey("nosnap")).thenReturn(Optional.of(p));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));

    assertThatThrownBy(() -> service.createOrder("nosnap"))
        .satisfies(thrown -> {
          PaymentServiceTest.assertForbidden(thrown);
          assertThat(((ResponseStatusException) thrown).getReason()).contains("không còn");
        });

    verify(orderRepository, never()).save(any());
  }

  /** Báo cáo phải lấy điểm RIASEC của CHÍNH người mua, không phải tổng cả phòng. */
  @Test
  void anhChupLayDungDiemCuaNguoiMua() {
    Player buyer = player("buyer", 1, 12);
    Player other = Player.create("other", "Khac");
    buyer.getRoom().addPlayer(other);
    other.scoreOf(RiasecCategory.A).addPoints(99);
    buyer.getRoom().captureRiasecSnapshot();

    when(playerRepository.findByPlayerKey("buyer")).thenReturn(Optional.of(buyer));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "buyer", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());
    when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.createOrder("buyer");

    ArgumentCaptor<PaymentOrder> saved = ArgumentCaptor.forClass(PaymentOrder.class);
    verify(orderRepository).save(saved.capture());
    String riasec = saved.getValue().getRiasecJson();
    assertThat(riasec).contains("\"R\":4").contains("\"S\":6");
    assertThat(riasec).doesNotContain("\"A\":99");
    assertThat(PaymentService.hollandCode(riasec)).isEqualTo("S");
  }

  @Test
  void vnpayTatThiKhongTaoDuocDon() {
    VnpayConfig disabled = new VnpayConfig(
        false, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn");
    PaymentService offService = new PaymentService(
        RULES,
        playerRepository,
        orderRepository,
        gameService,
        new VnpaySignatureService(disabled),
        disabled);

    assertThatThrownBy(() -> offService.createOrder("winner"))
        .satisfies(thrown -> {
          assertThat(thrown).isInstanceOf(ResponseStatusException.class);
          ResponseStatusException ex = (ResponseStatusException) thrown;
          assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
          assertThat(ex.getReason()).contains("khả dụng");
        });

    // Chặn ở cửa: không tra người chơi, không tạo đơn rác trong DB.
    verify(playerRepository, never()).findByPlayerKey(any());
    verify(gameService, never()).checkWinner(any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void vnpayBatNhungThieuKhoaBiMatThiKhongTaoDuocDon() {
    VnpayConfig noSecret =
        new VnpayConfig(true, "TMNCODE", "", "https://app.example/return", "https://app.example/ipn");
    PaymentService brokenService = new PaymentService(
        RULES,
        playerRepository,
        orderRepository,
        gameService,
        new VnpaySignatureService(noSecret),
        noSecret);

    // Người chơi CÓ THẬT và đủ điều kiện thắng: nếu không chặn ở cửa thì đơn
    // sẽ được ghi vào DB trước, rồi VnpaySignatureService mới ném lỗi
    // ("Empty key") -> còn đơn rác PENDING trong bảng.
    when(playerRepository.findByPlayerKey("winner"))
        .thenReturn(Optional.of(player("winner", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "winner", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> brokenService.createOrder("winner"))
        .satisfies(thrown -> {
          assertThat(thrown).isInstanceOf(ResponseStatusException.class);
          ResponseStatusException ex = (ResponseStatusException) thrown;
          assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
          assertThat(ex.getReason()).contains("cấu hình");
        });

    // Chặn ở cửa: KHÔNG đụng DB, nên không còn đơn rác PENDING.
    verify(playerRepository, never()).findByPlayerKey(any());
    verify(gameService, never()).checkWinner(any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void vnpayBatNhungThieuMaMerchantThiKhongTaoDuocDon() {
    VnpayConfig noTmnCode =
        new VnpayConfig(true, "", "secret", "https://app.example/return", "https://app.example/ipn");
    PaymentService brokenService = new PaymentService(
        RULES,
        playerRepository,
        orderRepository,
        gameService,
        new VnpaySignatureService(noTmnCode),
        noTmnCode);

    when(playerRepository.findByPlayerKey("winner"))
        .thenReturn(Optional.of(player("winner", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "winner", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> brokenService.createOrder("winner"))
        .satisfies(thrown -> {
          assertThat(thrown).isInstanceOf(ResponseStatusException.class);
          ResponseStatusException ex = (ResponseStatusException) thrown;
          assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
          assertThat(ex.getReason()).contains("cấu hình");
        });

    verify(playerRepository, never()).findByPlayerKey(any());
    verify(gameService, never()).checkWinner(any());
    verify(orderRepository, never()).save(any());
  }

  /**
   * Thiếu {@code VNPAY_RETURN_URL}: người chơi thanh toán xong KHÔNG được đưa
   * về trang kết quả của app. Phải chặn ở cửa, không ghi đơn.
   */
  @Test
  void vnpayBatNhungThieuUrlTraVeThiKhongTaoDuocDon() {
    VnpayConfig noReturnUrl =
        new VnpayConfig(true, "TMNCODE", "secret", "", "https://app.example/ipn");
    PaymentService brokenService = new PaymentService(
        RULES,
        playerRepository,
        orderRepository,
        gameService,
        new VnpaySignatureService(noReturnUrl),
        noReturnUrl);

    // Người chơi CÓ THẬT và đủ điều kiện thắng: nếu không chặn ở cửa thì đơn
    // sẽ được ghi vào DB với payUrl thiếu vnp_ReturnUrl.
    when(playerRepository.findByPlayerKey("winner"))
        .thenReturn(Optional.of(player("winner", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "winner", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> brokenService.createOrder("winner"))
        .satisfies(thrown -> {
          assertThat(thrown).isInstanceOf(ResponseStatusException.class);
          ResponseStatusException ex = (ResponseStatusException) thrown;
          assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
          assertThat(ex.getReason()).contains("cấu hình");
          assertThat(ex.getReason()).contains("VNPAY_RETURN_URL");
        });

    verify(playerRepository, never()).findByPlayerKey(any());
    verify(gameService, never()).checkWinner(any());
    verify(orderRepository, never()).save(any());
  }

  /**
   * Thiếu {@code VNPAY_IPN_URL}: server KHÔNG nhận được IPN từ VNPay → đơn kẹt
   * PENDING → hết 15 phút thành EXPIRED dù người chơi ĐÃ trả tiền. Phải chặn
   * ở cửa, không ghi đơn.
   */
  @Test
  void vnpayBatNhungThieuUrlIpnThiKhongTaoDuocDon() {
    VnpayConfig noIpnUrl =
        new VnpayConfig(true, "TMNCODE", "secret", "https://app.example/return", "");
    PaymentService brokenService = new PaymentService(
        RULES,
        playerRepository,
        orderRepository,
        gameService,
        new VnpaySignatureService(noIpnUrl),
        noIpnUrl);

    when(playerRepository.findByPlayerKey("winner"))
        .thenReturn(Optional.of(player("winner", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "winner", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> brokenService.createOrder("winner"))
        .satisfies(thrown -> {
          assertThat(thrown).isInstanceOf(ResponseStatusException.class);
          ResponseStatusException ex = (ResponseStatusException) thrown;
          assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
          assertThat(ex.getReason()).contains("cấu hình");
          assertThat(ex.getReason()).contains("VNPAY_IPN_URL");
        });

    verify(playerRepository, never()).findByPlayerKey(any());
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

  // ── Tham số VNPay đúng chuẩn (rủi ro protocol thật) ────────────────────────

  /**
   * {@code vnp_TxnExpireDate} KHÔNG phải param chuẩn của API cũ
   * {@code paymentgateway/paymenturl.htm}. Gateway có thể loại bỏ param lạ
   * trước khi tự tính hash ⇒ hash của ta ≠ hash của VNPay ⇒ đơn bị từ chối.
   */
  @Test
  void payUrlKhongCoParamLaTxnExpireDate() {
    when(playerRepository.findByPlayerKey("params"))
        .thenReturn(Optional.of(player("params", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "params", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());
    when(orderRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    CreatePaymentResponse response = service.createOrder("params");

    assertThat(response.payUrl()).doesNotContain("vnp_TxnExpireDate");
    assertThat(response.payUrl()).contains("vnp_ExpireDate=");
  }

  /** {@code vnp_OrderInfo} bắt buộc với {@code pay_web}; {@code vnp_IpAddr} lấy từ IP client. */
  @Test
  void payUrlCoOrderInfoVaIpAddr() {
    when(playerRepository.findByPlayerKey("ip"))
        .thenReturn(Optional.of(player("ip", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "ip", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());
    when(orderRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    CreatePaymentResponse response = service.createOrder("ip", "203.0.113.7");

    assertThat(response.payUrl()).contains("vnp_OrderInfo=");
    assertThat(response.payUrl()).contains("vnp_IpAddr=203.0.113.7");
  }

  /** Không có IP client thì KHÔNG gửi {@code vnp_IpAddr} (giá trị rỗng bị lọc khỏi chữ ký). */
  @Test
  void payUrlKhongCoIpAddrKhiKhongBietIp() {
    when(playerRepository.findByPlayerKey("noip"))
        .thenReturn(Optional.of(player("noip", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "noip", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());
    when(orderRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    CreatePaymentResponse response = service.createOrder("noip", "");

    assertThat(response.payUrl()).doesNotContain("vnp_IpAddr");
  }

  /** Gateway VNPay đổi được bằng biến môi trường, KHÔNG cần sửa code. */
  @Test
  void payUrlDungBaseUrlTuCauHinh() {
    VnpayConfig v2 = new VnpayConfig(
        true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
        "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html", "SHA512", "SHA512");
    PaymentService v2Service = new PaymentService(
        RULES,
        playerRepository,
        orderRepository,
        gameService,
        new VnpaySignatureService(v2),
        v2);
    when(playerRepository.findByPlayerKey("v2"))
        .thenReturn(Optional.of(player("v2", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "v2", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());
    when(orderRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    CreatePaymentResponse response = v2Service.createOrder("v2");

    assertThat(response.payUrl())
        .startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
    assertThat(response.payUrl()).contains("vnp_SecureHashType=SHA512");
    // SHA512 -> hash 128 hex ky tu.
    Map<String, String> params = parseQuery(response.payUrl());
    String hash = params.remove("vnp_SecureHash");
    assertThat(hash).hasSize(128);
  }

  /**
   * Race 2 tab: hai lần gọi {@code createOrder} CÙNG LÚC phải chỉ tạo MỘT
   * đơn PENDING.
   *
   * <p>Nếu không khoá theo {@code playerKey} thì cả hai cùng đọc "chưa có đơn
   * PENDING" rồi cùng tạo ⇒ 2 đơn ⇒ client chỉ nhớ 1 {@code pendingOrderId}
   * ⇒ poll nhầm đơn ⇒ đếm ngược rồi báo "hết hạn" trong khi đơn thật đã PAID,
   * và người chơi có thể phải trả tiền hai lần.
   */
  @Test
  void haiTabBamCungLucChiTaoMotDonPending() throws Exception {
    when(playerRepository.findByPlayerKey("race"))
        .thenReturn(Optional.of(player("race", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));

    java.util.concurrent.atomic.AtomicReference<PaymentOrder> saved =
        new java.util.concurrent.atomic.AtomicReference<>();
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "race", PaymentStatus.PENDING))
        .thenAnswer(
            invocation -> {
              // Nới rộng cửa sổ: hai thread cùng nằm trong "vùng quyết định"
              // nếu KHÔNG có khoá. Có khoá thì thread thứ hai phải chờ.
              Thread.sleep(60);
              return Optional.ofNullable(saved.get());
            });
    when(orderRepository.save(any()))
        .thenAnswer(
            invocation -> {
              PaymentOrder order = invocation.getArgument(0);
              saved.compareAndSet(null, order);
              return order;
            });

    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(() -> service.createOrder("race").orderId());
      var second = pool.submit(() -> service.createOrder("race").orderId());
      String id1 = first.get();
      String id2 = second.get();

      assertThat(id1).isEqualTo(id2);
    } finally {
      pool.shutdownNow();
    }

    // Đúng MỘT lần ghi đơn — không phải hai.
    verify(orderRepository, org.mockito.Mockito.times(1)).save(any());
  }
}
