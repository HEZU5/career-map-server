package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Công thức "bước đi này có hoàn thành một vòng không".
 *
 * <p>Bảng 53 ô, quân đi NGƯỢC chiều mũi tên: từ {@code pos} đi {@code value} ô
 * thì các ô đi qua là {@code pos-1, pos-2, ...}. Quân CHẠM ô START (ô 0) khi
 * {@code pos - value <= 0} — đáp đúng ô START khi bằng 0, đi xuyên qua ô START
 * khi nhỏ hơn 0.
 *
 * <p>NHƯNG lần RỜI START đầu tiên KHÔNG tính: quân bắt đầu ván đang đứng ở ô
 * START nên bước đi đầu tiên là đi KHỎI START, chưa phải hoàn thành vòng nào.
 * Vì vậy {@code completedLaps} chỉ nhích khi quân quay LẠI chạm ô START — tức
 * "lần thứ 2".
 *
 * <p>Hàm này là nguồn sự thật DUY NHẤT cho {@code TurnCoordinator.rollAndMove},
 * {@code GameService.rollSolo} VÀ {@code GameService.moveForward} (lá dời quân
 * MOVE_2/MOVE_3).
 *
 * <p>Lưu ý: {@code completesLap} là hàm THUẦN (stateless) nên trạng thái "đã
 * về START rồi rời START" không biểu được bằng tham số. Ca đó được kiểm
 * chứng bằng đi THẬT trong {@code CompletedLapsTest.vongMoiChuaXongThiKhongCongThemVong}
 * (quân ở ô 1 → sang ô 0 → đi tiếp từ ô 0 ⇒ tổng vòng = 1, không phải 2) —
 * đừng thêm lại một bản sao thuần của {@code roiStartLanDauKhongTinhVong} ở đây.
 *
 * <p>Thuần JUnit + AssertJ, không Spring context, không database.
 */
class CompletesLapRuleTest {

  @Test
  void roiStartLanDauKhongTinhVong() {
    // Ô 0 - 1 = -1 ⇒ có "đi qua" ô START, nhưng quân đang ĐỨNG ở START ⇒ đây là
    // lần rời START đầu tiên ⇒ chưa hoàn thành vòng nào.
    assertThat(TurnCoordinator.completesLap(0, 1)).isFalse();
  }

  @Test
  void roiStartLanDauBangBuocLonKhongTinhVong() {
    // Bước lớn cũng vậy: 0 - 6 = -6 ⇒ rời START, chưa tính vòng.
    assertThat(TurnCoordinator.completesLap(0, 6)).isFalse();
  }

  @Test
  void dapDungOStartThiTinhVong() {
    // Ô 1 - 1 = 0 ⇒ đáp ĐÚNG ô START ⇒ lần chạm START thứ 2 ⇒ có vòng.
    assertThat(TurnCoordinator.completesLap(1, 1)).isTrue();
  }

  @Test
  void diQuaStartThiTinhVong() {
    // Ô 2 - 3 = -1 ⇒ đi XUYÊN QUA ô START ⇒ có vòng.
    assertThat(TurnCoordinator.completesLap(2, 3)).isTrue();
  }

  @Test
  void diQuaStartVoiBuocLonThiTinhVong() {
    // Ô 3 - 5 = -2 ⇒ đi xuyên qua ô START ⇒ có vòng.
    assertThat(TurnCoordinator.completesLap(3, 5)).isTrue();
  }

  @Test
  void dapDungOStartVoiBuocHaiThiTinhVong() {
    // Ô 2 - 2 = 0 ⇒ đáp ĐÚNG ô START với bước 2 ⇒ có vòng. Đây đúng là ca
    // của `GameService.moveForward` (lá MOVE_2) — luật cũ (`raw < 0`) bỏ sót
    // chính ca này.
    assertThat(TurnCoordinator.completesLap(2, 2)).isTrue();
  }

  @Test
  void khongChamStartThiKhongTinhVong() {
    // Ô 52 - 3 = 49 ⇒ không chạm START.
    assertThat(TurnCoordinator.completesLap(52, 3)).isFalse();
  }

  @Test
  void dungOiTruocStartNhungChuaChamThiKhongTinhVong() {
    // Ô 51 - 2 = 49 ⇒ không chạm START.
    assertThat(TurnCoordinator.completesLap(51, 2)).isFalse();
  }
}
