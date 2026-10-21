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
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, false, 5L);
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
        .startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
    assertThat(response.payUrl()).contains("vnp_TxnRef=" + order.getId());
    assertThat(response.payUrl()).contains("vnp_Amount=5000000");
    assertThat(response.payUrl()).contains("vnp_CurrCode=VND");
    assertThat(response.payUrl()).contains("vnp_Locale=vn");
    assertThat(response.payUrl()).contains("vnp_Command=pay");
    assertThat(response.payUrl()).contains("vnp_Version=2.1.0");
    assertThat(response.payUrl()).contains("vnp_OrderType=other");
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

  /**
   * API 2.1.0 coi {@code vnp_IpAddr} là tham số BẮT BUỘC: thiếu nó ⇒ gateway
   * trả "Dữ liệu gửi sang không đúng định dạng" ⇒ đơn bị từ chối.
   *
   * <p>Vì vậy khi không đọc được IP client thì dùng fallback {@code 127.0.0.1}
   * (giá trị IP trong mẫu chính thức của VNPay) — KHÔNG được bỏ hẳn tham số.
   */
  @Test
  void payUrlFallbackIpAddrKhiKhongBietIp() {
    when(playerRepository.findByPlayerKey("noip"))
        .thenReturn(Optional.of(player("noip", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "noip", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());
    when(orderRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    CreatePaymentResponse response = service.createOrder("noip", "");

    assertThat(response.payUrl()).contains("vnp_IpAddr=127.0.0.1");
  }

  /**
   * Header {@code X-Forwarded-For} do CLIENT kiểm soát ⇒ ký tự lạ không được
   * lọt vào {@code vnp_IpAddr} (và vào cả chữ ký). IP sai dạng ⇒ dùng fallback
   * {@code 127.0.0.1} (vẫn phải CÓ tham số — API 2.1.0 bắt buộc).
   */
  @Test
  void payUrlLoaiKyTuLaTrongIpAddr() {
    when(playerRepository.findByPlayerKey("ip2"))
        .thenReturn(Optional.of(player("ip2", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "ip2", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());
    when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    CreatePaymentResponse response = service.createOrder("ip2", "203.0.113.7<script>");

    assertThat(response.payUrl()).contains("vnp_IpAddr=127.0.0.1");
    assertThat(response.payUrl()).doesNotContain("script");
  }

  /** IP đúng dạng thì vẫn được gửi. */
  @Test
  void payUrlGuiIpDungDang() {
    when(playerRepository.findByPlayerKey("ip3"))
        .thenReturn(Optional.of(player("ip3", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "ip3", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());
    when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    CreatePaymentResponse response = service.createOrder("ip3", "203.0.113.7");

    assertThat(response.payUrl()).contains("vnp_IpAddr=203.0.113.7");
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
    // API 2.1.0 (vnp_Command=pay) KHÔNG được gửi vnp_SecureHashType dù biến
    // môi trường có đặt — gateway 2.1.0 tự tính hash theo HMAC-SHA512.
    assertThat(response.payUrl()).doesNotContain("vnp_SecureHashType");
    // SHA512 -> hash 128 hex ky tu.
    Map<String, String> params = parseQuery(response.payUrl());
    String hash = params.remove("vnp_SecureHash");
    assertThat(hash).hasSize(128);
  }

  // ── Bộ giao thức API 2.1.0 (giống production) ─────────────────────────────

  /**
   * Cấu hình GIỐNG HỆT production: base/command/version/order-type lấy mặc
   * định (API 2.1.0) và {@code hash-algorithm} để RỖNG ⇒
   * {@link VnpayConfig} tự chọn HMAC-SHA512 theo phiên bản API (Ruling B).
   */
  private static VnpayConfig api21Config() {
    return new VnpayConfig(
        true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
        VnpayConfig.DEFAULT_BASE_URL, "", "",
        VnpayConfig.DEFAULT_COMMAND, VnpayConfig.DEFAULT_VERSION, VnpayConfig.DEFAULT_ORDER_TYPE);
  }

  /**
   * API 2.1.0 ký bằng HMAC-SHA512 ⇒ {@code vnp_SecureHash} dài 128 hex (API cũ
   * SHA256 chỉ 64 hex). Gateway báo "Sai chữ ký" nếu thuật toán lệch.
   */
  @Test
  void chuKyApi21Dai128Hex() {
    VnpayConfig api21 = api21Config();
    PaymentService api21Service = new PaymentService(
        RULES,
        playerRepository,
        orderRepository,
        gameService,
        new VnpaySignatureService(api21),
        api21);
    when(playerRepository.findByPlayerKey("sha512"))
        .thenReturn(Optional.of(player("sha512", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "sha512", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());
    when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    CreatePaymentResponse response = api21Service.createOrder("sha512", "203.0.113.7");

    Map<String, String> params = parseQuery(response.payUrl());
    String hash = params.remove("vnp_SecureHash");
    assertThat(hash).hasSize(128).matches("[0-9a-f]{128}");
  }

  /**
   * payUrl của API 2.1.0 phải có ĐỦ tham số bắt buộc — thiếu bất kỳ tham số
   * nào thì gateway trả "Dữ liệu gửi sang không đúng định dạng" và từ chối đơn.
   *
   * <p>Đây là bộ tham số đã dò thực tế trên sandbox: thiếu {@code vnp_IpAddr}
   * cũng bị từ chối y như vậy.
   */
  @Test
  void payUrlApi21CoDayDuThamSoBatBuoc() {
    VnpayConfig api21 = api21Config();
    PaymentService api21Service = new PaymentService(
        RULES,
        playerRepository,
        orderRepository,
        gameService,
        new VnpaySignatureService(api21),
        api21);
    when(playerRepository.findByPlayerKey("daydu"))
        .thenReturn(Optional.of(player("daydu", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "daydu", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());
    when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    // KHÔNG truyền IP client -> vnp_IpAddr vẫn phải có mặt (fallback).
    CreatePaymentResponse response = api21Service.createOrder("daydu");

    assertThat(response.payUrl())
        .startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
    assertThat(response.payUrl()).contains("vnp_Version=2.1.0");
    assertThat(response.payUrl()).contains("vnp_Command=pay");
    assertThat(response.payUrl()).contains("vnp_OrderType=other");
    assertThat(response.payUrl()).contains("vnp_IpAddr=127.0.0.1");
    assertThat(response.payUrl()).contains("vnp_Locale=vn");
    assertThat(response.payUrl()).contains("vnp_CreateDate=");
    assertThat(response.payUrl()).contains("vnp_OrderInfo=");
    assertThat(response.payUrl()).contains("vnp_ReturnUrl=");
    assertThat(response.payUrl()).contains("vnp_Amount=5000000");
    assertThat(response.payUrl()).contains("vnp_CurrCode=VND");
    assertThat(response.payUrl()).contains("vnp_TxnRef=");
    assertThat(response.payUrl()).contains("vnp_TmnCode=TMNCODE");
    // API 2.1.0 KHÔNG được gửi vnp_SecureHashType.
    assertThat(response.payUrl()).doesNotContain("vnp_SecureHashType");
  }

  /**
   * API 2.1.0 ({@code vnp_Command=pay}) là MỘT BỘ GIAO THỨC RIÊNG: bắt buộc
   * {@code vnp_Version} + {@code vnp_OrderType}, và KHÔNG được gửi
   * {@code vnp_SecureHashType} (tài liệu "Chuyển đổi phiên bản và thuật toán mã
   * hoá": 2.1.0 không gửi tham số này).
   */
  @Test
  void payUrlApi21CoVersionVaOrderTypeVaKhongCoSecureHashType() {
    VnpayConfig api21 = new VnpayConfig(
        true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn",
        "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html", "SHA512", "SHA512",
        "pay", "2.1.0", "other");
    PaymentService api21Service = new PaymentService(
        RULES,
        playerRepository,
        orderRepository,
        gameService,
        new VnpaySignatureService(api21),
        api21);
    when(playerRepository.findByPlayerKey("api21"))
        .thenReturn(Optional.of(player("api21", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "api21", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());
    when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    CreatePaymentResponse response = api21Service.createOrder("api21");

    assertThat(response.payUrl()).contains("vnp_Command=pay");
    assertThat(response.payUrl()).contains("vnp_Version=2.1.0");
    assertThat(response.payUrl()).contains("vnp_OrderType=other");
    assertThat(response.payUrl()).doesNotContain("vnp_SecureHashType");
  }

  /**
   * Mọi giá trị gửi lên VNPay phải là tiếng Việt KHÔNG DẤU, không khoảng trắng
   * (chuẩn VNPay). {@code vnp_OrderInfo} cố định là ASCII; URL bị bỏ dấu.
   */
  @Test
  void payUrlChiGuiGiaTriHopLeChuanVnPay() {
    VnpayConfig coDau = new VnpayConfig(
        true, "TMNCODE", "secret",
        "https://app.example/kết-quá-thanh-toán",
        "https://app.example/api/payments/ipn");
    PaymentService service2 = new PaymentService(
        RULES,
        playerRepository,
        orderRepository,
        gameService,
        new VnpaySignatureService(coDau),
        coDau);
    when(playerRepository.findByPlayerKey("sau"))
        .thenReturn(Optional.of(player("sau", 1, 12)));
    when(gameService.checkWinner(any())).thenReturn(new WinnerResponse(true, 12, 8));
    when(orderRepository.findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
            "sau", PaymentStatus.PENDING))
        .thenReturn(Optional.empty());
    when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    CreatePaymentResponse response = service2.createOrder("sau");

    // vnp_OrderInfo: ASCII, khong dau, khong khoang trang.
    assertThat(response.payUrl()).contains("vnp_OrderInfo=Bao+cao+Holland");
    // URL co dau da duoc bo dau.
    assertThat(response.payUrl())
        .contains("vnp_ReturnUrl=https%3A%2F%2Fapp.example%2Fket-qua-thanh-toan");
    // Khong con ky tu co dau trong toan bo payUrl.
    assertThat(response.payUrl()).doesNotContain("ế");
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
