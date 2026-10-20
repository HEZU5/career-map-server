package vn.careermap.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.config.VnpayConfig;
import vn.careermap.domain.PaymentOrder;
import vn.careermap.domain.PaymentStatus;
import vn.careermap.repo.PaymentOrderRepository;
import vn.careermap.repo.PlayerRepository;
import vn.careermap.service.GameService;
import vn.careermap.service.PaymentService;
import vn.careermap.service.VnpaySignatureService;

/**
 * Kiểm chứng HỢP ĐỒNG HTTP của endpoint thanh toán — đi qua đúng pipeline
 * Spring MVC thật (DispatcherServlet → ExceptionHandlerExceptionResolver → advice
 * → HttpMessageConverter).
 *
 * <p>Trước đây cột "IPN luôn trả 200" của review CHƯA TỪNG được kiểm chứng ở
 * tầng HTTP — toàn bộ test IPN gọi service trực tiếp.
 */
@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

  private static final VnpayConfig VNPAY =
      new VnpayConfig(
          true, "TMNCODE", "secret", "https://app.example/return", "https://app.example/ipn");
  private static final VnpaySignatureService SIGNER = new VnpaySignatureService(VNPAY);

  @Autowired MockMvc mvc;

  @MockitoBean PaymentOrderRepository orderRepository;
  @MockitoBean PlayerRepository playerRepository;
  @MockitoBean GameService gameService;

  @TestConfiguration
  static class ServiceConfig {
    @Bean
    GameRulesConfig rules() {
      return new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, true, 5L);
    }

    @Bean
    VnpayConfig vnpayConfig() {
      return VNPAY;
    }

    @Bean
    VnpaySignatureService signatureService() {
      return SIGNER;
    }

    @Bean
    PaymentService paymentService(
        GameRulesConfig rules,
        PlayerRepository players,
        PaymentOrderRepository orders,
        GameService game,
        VnpaySignatureService signatureService,
        VnpayConfig config) {
      return new PaymentService(rules, players, orders, game, signatureService, config);
    }
  }

  private static Map<String, String> signedParams(String txnRef) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("vnp_TxnRef", txnRef);
    params.put("vnp_TransactionStatus", "01");
    params.put("vnp_Amount", "5000000");
    params.put("vnp_SecureHash", SIGNER.sign(params));
    return params;
  }

  private static MockHttpServletRequestBuilder ipnPost(Map<String, String> params) {
    var builder = post("/api/payments/ipn").contentType(MediaType.APPLICATION_FORM_URLENCODED);
    params.forEach(builder::param);
    return builder;
  }

  // ── IPN: luôn 200 cho lỗi NGHIỆP VỤ ───────────────────────────────────────

  @Test
  void ipnSaiChuKyTra200VaRspCode97() throws Exception {
    mvc.perform(
            ipnPost(
                Map.of(
                    "vnp_TxnRef", "order1",
                    "vnp_TransactionStatus", "01",
                    "vnp_Amount", "5000000",
                    "vnp_SecureHash", "chu-ky-sai")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"RspCode\":\"97\"")));

    verify(orderRepository, never()).save(any());
  }

  @Test
  void ipnOrderKhongTonTaiTra200VaRspCode01() throws Exception {
    when(orderRepository.findById("missing")).thenReturn(Optional.empty());

    mvc.perform(ipnPost(signedParams("missing")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"RspCode\":\"01\"")));
  }

  @Test
  void ipnHopLeTra200VaDonSangPaid() throws Exception {
    PaymentOrder order = PaymentOrder.create("order1", "p", "R12345", 50000, 15);
    when(orderRepository.findById("order1")).thenReturn(Optional.of(order));
    when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    mvc.perform(ipnPost(signedParams("order1")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"RspCode\":\"00\"")));

    assertThat(order.getStatus()).isEqualTo(PaymentStatus.PAID);
    assertThat(order.getPaidAt()).isNotNull();
  }

  /** Thiếu param ⇒ KHÔNG được 400 (400 khiến VNPay coi là lỗi và retry mãi). */
  @Test
  void ipnThieuParamVanTra200() throws Exception {
    mvc.perform(post("/api/payments/ipn").contentType(MediaType.APPLICATION_FORM_URLENCODED))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"RspCode\"")));
  }

  /**
   * Content-Type sai ⇒ KHÔNG được 415.
   *
   * <p>415 là lỗi phía server theo góc nhìn VNPay ⇒ VNPay retry mãi một request
   * vốn dĩ không bao giờ thành công. Phải trả 200 để gateway nhận "đã xử lý".
   */
  @Test
  void ipnContentTypeSaiKhongBao415() throws Exception {
    mvc.perform(
            post("/api/payments/ipn")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vnp_TxnRef\":\"order1\"}"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"RspCode\"")));
  }

  /**
   * Lỗi HẠ TẦNG (DB) ⇒ 5xx để VNPay retry, KHÔNG phải 200.
   *
   * <p>Body PHẢI có {@code RspCode} — gateway VNPay quyết định retry dựa trên
   * RspCode trong body, không phải HTTP status. 99 ⇒ retry tối đa 10 lần.
   */
  @Test
  void ipnLoiHaTauTra5xxDeVnPayRetry() throws Exception {
    when(orderRepository.findById("order1"))
        .thenThrow(new DataAccessResourceFailureException("connection reset"));

    mvc.perform(ipnPost(signedParams("order1")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().string(containsString("\"RspCode\":\"99\"")));
  }

  /**
   * IPN của API 2.1.0 được VNPay gọi bằng phương thức {@code GET}.
   *
   * <p>Endpoint phải nhận CẢ GET lẫn POST, nếu không thì mọi IPN của API 2.1.0
   * đều 405 ⇒ đơn kẹt PENDING ⇒ hết hạn EXPIRED dù tiền đã vào.
   */
  @Test
  void ipnNhanCaGetVaPost() throws Exception {
    PaymentOrder order = PaymentOrder.create("order1", "p", "R12345", 50000, 15);
    when(orderRepository.findById("order1")).thenReturn(Optional.of(order));
    when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    mvc.perform(get("/api/payments/ipn").param("vnp_TxnRef", "order1")
            .param("vnp_TransactionStatus", "01")
            .param("vnp_Amount", "5000000")
            .param("vnp_SecureHash", SIGNER.sign(signedParams("order1"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"RspCode\":\"00\"")));

    assertThat(order.getStatus()).isEqualTo(PaymentStatus.PAID);
  }

  // ── Lịch sử: KHÔNG lộ nội dung báo cáo của người khác ─────────────────────

  /**
   * {@code playerKey} đoán được ({@code host-<mã phòng>}) và mã phòng công khai
   * ⇒ lịch sử KHÔNG được chứa {@code riasecJson} (nội dung báo cáo đã trả
   * tiền — kể cả đơn CHƯA thanh toán) và cũng KHÔNG chứa {@code hollandCode}
   * (1 chữ cái đủ để đoán ra loại báo cáo của người khác).
   */
  @Test
  void lichSuKhongTraNoiDungBaoCao() throws Exception {
    PaymentOrder paid = PaymentOrder.create("paid", "p", "R12345", 50000, 15);
    paid.markPaid(java.time.Instant.now());
    paid.setRiasecJson("{\"R\":4,\"I\":2,\"A\":1,\"S\":6,\"E\":3,\"C\":0}");
    when(orderRepository.findByPlayerKeyOrderByCreatedAtDesc("p")).thenReturn(List.of(paid));

    mvc.perform(get("/api/payments/history").param("playerKey", "p"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].orderId").value("paid"))
        .andExpect(jsonPath("$[0].hollandCode").doesNotExist())
        .andExpect(jsonPath("$[0].riasecJson").doesNotExist())
        .andExpect(content().string(not(containsString("riasecJson"))))
        .andExpect(content().string(not(containsString("hollandCode"))));
  }

  /** Thiếu {@code playerKey} ⇒ 400 (không tra được đơn nào). */
  @Test
  void lichSuThieuPlayerKeyTra400() throws Exception {
    mvc.perform(get("/api/payments/history")).andExpect(status().isBadRequest());
  }

  /** Đơn của người khác ⇒ 404, không lộ {@code riasecJson}. */
  @Test
  void traTrangThaiDonCuaNguoiKhacTra404KhongLoNoiDung() throws Exception {
    when(orderRepository.findByIdAndPlayerKey("order1", "khac")).thenReturn(Optional.empty());

    mvc.perform(get("/api/payments/order1").param("playerKey", "khac"))
        .andExpect(status().isNotFound())
        .andExpect(content().string(not(containsString("riasecJson"))));
  }

  /** Chủ đơn thì đọc được nội dung báo cáo (kèm mã Holland). */
  @Test
  void chuDonDocDuocNoiDungBaoCao() throws Exception {
    PaymentOrder paid = PaymentOrder.create("mine", "p", "R12345", 50000, 15);
    paid.markPaid(java.time.Instant.now());
    paid.setRiasecJson("{\"R\":4,\"I\":2,\"A\":1,\"S\":6,\"E\":3,\"C\":0}");
    when(orderRepository.findByIdAndPlayerKey("mine", "p")).thenReturn(Optional.of(paid));

    mvc.perform(get("/api/payments/mine").param("playerKey", "p"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hollandCode").value("S"))
        .andExpect(jsonPath("$.riasecJson").value(containsString("\"S\":6")))
        .andExpect(jsonPath("$.createdAt").exists());
  }
}
