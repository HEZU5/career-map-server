package vn.careermap.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chuyển điểm RIASEC ⇄ chuỗi JSON dùng chung cho cả phòng lẫn đơn thanh toán.
 *
 * <p>Định dạng JSON: {@code {"R":4,"I":0,"A":2,"S":6,"E":1,"C":3}} — LUÔN có đủ
 * 6 khoá R/I/A/S/E/C (nhóm chưa ai bị hỏi thì để 0) để bên đọc không phải đoán
 * có thiếu khoá hay không.
 *
 * <p>Vì sao cần một chỗ duy nhất: ảnh chụp trong phòng ({@link
 * Room#captureRiasecSnapshot()}) và ảnh chụp gắn vào đơn thanh toán ({@code
 * PaymentOrder.riasecJson}) PHẢI có cùng định dạng — nếu lệch nhau thì mã
 * Holland tính ở hai nơi sẽ khác nhau.
 */
public final class RiasecScores {

  private RiasecScores() {}

  /**
   * Chuyển danh sách điểm của MỘT người chơi thành JSON 6 nhóm.
   *
   * @param scores điểm của riêng người chơi đó (có thể rỗng)
   * @return JSON {@code {"R":..,"I":..,...}}, hoặc {@code null} nếu không có điểm
   */
  public static String toJson(List<Score> scores) {
    if (scores == null || scores.isEmpty()) {
      return null;
    }
    Map<String, Integer> snapshot = new LinkedHashMap<>();
    for (RiasecCategory category : RiasecCategory.values()) {
      snapshot.put(category.name(), 0);
    }
    for (Score score : scores) {
      if (score == null || score.getCategory() == null) {
        continue;
      }
      snapshot.merge(score.getCategory().name(), score.getPoints(), Integer::sum);
    }
    return toJson(snapshot);
  }

  /**
   * Chuyển map điểm (mã nhóm → tổng điểm) thành JSON.
   *
   * @param snapshot map điểm, có thể rỗng
   * @return JSON {@code {"R":..,"I":..,...}}, hoặc {@code null} nếu rỗng
   */
  public static String toJson(Map<String, Integer> snapshot) {
    if (snapshot == null || snapshot.isEmpty()) {
      return null;
    }
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Integer> entry : snapshot.entrySet()) {
      if (!first) {
        sb.append(",");
      }
      first = false;
      sb.append("\"").append(entry.getKey()).append("\":")
          .append(entry.getValue() == null ? 0 : entry.getValue());
    }
    return sb.append("}").toString();
  }
}
