package vn.careermap.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Body lỗi phải vừa đủ cho người chơi, vừa KHÔNG lộ chi tiết hạ tầng.
 *
 * <p>Luật: CHỈ {@link org.springframework.web.server.ResponseStatusException}
 * (lỗi do code chủ động ném, luôn kèm câu tiếng Việt) mới có field
 * {@code message}. Lỗi 500 do exception không lường trước giữ body mặc định
 * của Spring — KHÔNG có {@code message}, nên không lộ JDBC URL + user DB, tên
 * bảng SQL, tên biến môi trường.
 *
 * <p>Dùng {@code @WebMvcTest} (giống {@code HealthControllerTest}) — không
 * database, không service thật.
 */
@WebMvcTest(controllers = FailingTestController.class)
class ResponseStatusExceptionAdviceTest {

  @Autowired MockMvc mvc;

  @Test
  void loiChuDongDongTraVeCauTingViet() throws Exception {
    mvc.perform(get("/test/forbidden"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.error").value("Forbidden"))
        .andExpect(jsonPath("$.message").value("Chưa đủ điều kiện mở báo cáo Holland"));
  }

  @Test
  void loi503CungTraCauTingViet() throws Exception {
    mvc.perform(get("/test/unavailable"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value(503))
        .andExpect(jsonPath("$.error").value("Service Unavailable"))
        .andExpect(jsonPath("$.message").value("Thanh toán VNPay đang tạm thời không khả dụng."));
  }

  /**
   * Lỗi 500 (exception KHÔNG lường trước) KHÔNG bị advice bắt — advice chỉ
   * bắt {@code ResponseStatusException}. Lỗi vẫn nổi lên tầng trên để
   * container render trang lỗi mặc định.
   */
  @Test
  void loi500KhongDuocAdviceBat() {
    assertThatThrownBy(() -> mvc.perform(get("/test/crash")))
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(FailingTestController.SECRET)
        .hasMessageContaining("VNPAY_HASH_SECRET is null");
  }

  /**
   * Body lỗi 500 mặc định của Spring KHÔNG có field {@code message} và không
   * chứa nội dung exception nội bộ.
   */
  @Test
  void bodyLoi500KhongCoMessageVaKhongLeChiTiet() throws Exception {
    mvc.perform(
            get("/error")
                .accept(MediaType.APPLICATION_JSON)
                .with(
                    request -> {
                      request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
                      request.setAttribute(
                          RequestDispatcher.ERROR_EXCEPTION,
                          new IllegalStateException(FailingTestController.SECRET));
                      request.setAttribute(
                          RequestDispatcher.ERROR_REQUEST_URI, "/api/payments/create");
                      return request;
                    }))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.error").value("Internal Server Error"))
        .andExpect(jsonPath("$.message").doesNotExist())
        .andExpect(content().string(not(containsString("message"))))
        .andExpect(content().string(not(containsString("hunter2"))))
        .andExpect(content().string(not(containsString("jdbc:postgresql"))));
  }
}
