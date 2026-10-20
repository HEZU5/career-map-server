package vn.careermap.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Luật chơi + luật thanh toán nằm gọn trong MỘT file cấu hình.
 *
 * <p>Không dựng Spring context, không cần database: constructor nhận thẳng giá
 * trị nên test được cả luật mặc định lẫn phần đóng gói gửi cho client.
 *
 * <p>Lưu ý: constructor tay ở đây KHÔNG ghim được giá trị mặc định thật — đổi
 * {@code application.properties} hay đổi default trong {@code @Value} thì file này
 * vẫn xanh. Việc đó do {@code GameRulesConfigDefaultsTest} đảm nhiệm.
 */
class GameRulesConfigTest {
  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, false);

  @Test
  void paymentAmountLaNamTramKhong() {
    assertThat(RULES.getPaymentAmountVnd()).isEqualTo(50000L);
  }

  @Test
  void hanDonLaMuoiLamPhut() {
    assertThat(RULES.getPaymentTtlMinutes()).isEqualTo(15L);
  }

  @Test
  void nguongThangLaMotVongVaMuoiHaiCau() {
    assertThat(RULES.getWinMinLaps()).isEqualTo(1);
    assertThat(RULES.getWinMinAnswers()).isEqualTo(12);
  }

  @Test
  void vnpayMacDinhLaTat() {
    assertThat(RULES.isVnpayEnabled()).isFalse();
  }

  @Test
  void vnpayBatKhiDuocMoKhoa() {
    GameRulesConfig rules =
        new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, true);

    assertThat(rules.isVnpayEnabled()).isTrue();
  }

  @Test
  void snapshotChoClientCungBaoNamTruongThanhToan() {
    GameRulesSnapshot snapshot = RULES.snapshot();

    assertThat(snapshot.paymentAmountVnd()).isEqualTo(50000L);
    assertThat(snapshot.paymentTtlMinutes()).isEqualTo(15L);
    assertThat(snapshot.winMinLaps()).isEqualTo(1);
    assertThat(snapshot.winMinAnswers()).isEqualTo(12);
  }

  @Test
  void snapshotChoClientBietVnpayDangBatHayTat() {
    // Client ẩN nút "Nhận báo cáo" khi VNPay chưa bật — phải biết qua
    // GET /api/game/config, không hard-code bên client.
    assertThat(RULES.snapshot().vnpayEnabled()).isFalse();

    GameRulesConfig bat =
        new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12, true);
    assertThat(bat.snapshot().vnpayEnabled()).isTrue();
  }

  @Test
  void snapshotVanGiuNguyenCacKhoaCuChoClient() {
    GameRulesSnapshot snapshot = RULES.snapshot();

    // Client đang đếm ngược theo turnRollS/answerTimeoutS — mất khoá là hỏng luồng.
    assertThat(snapshot.turnRollS()).isEqualTo(15L);
    assertThat(snapshot.answerTimeoutS()).isEqualTo(60L);
    assertThat(snapshot.awayAfterS()).isEqualTo(120L);
    assertThat(snapshot.heartbeatS()).isEqualTo(5L);
    assertThat(snapshot.offlineDetectS()).isEqualTo(15L);
    assertThat(snapshot.fastAutoRollS()).isEqualTo(2L);
    assertThat(snapshot.emptyRoomTtlMin()).isEqualTo(10L);
    assertThat(snapshot.finishedRoomTtlMin()).isEqualTo(30L);
    assertThat(snapshot.offlinePolicy()).isEqualTo("skip");
  }
}
