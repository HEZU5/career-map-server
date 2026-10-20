package vn.careermap.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.careermap.domain.BonusEffect;

/**
 * BẢNG HIỆU ỨNG CỦA 20 LÁ CƠ HỘI / THỬ THÁCH — nguồn sự thật duy nhất ở server.
 *
 * <p>Trước đây client tự khai {@code winPoints} rồi gửi lên, server tin theo ⇒
 * lỗ hổng. Và hiệu ứng thật của lá ("Tiến thêm 2 ô", "Chọn 1 người chơi", "Chơi
 * thêm 1 lượt", "Cả bàn cùng nhận +1") bị viết ra rồi bỏ qua. Nay server tra
 * bảng này theo {@code cardId} và tự áp hiệu ứng.
 *
 * <p><b>cardId CÓ DẤU.</b> Chuỗi ở đây phải GIỐNG HỆT chuỗi trong danh mục
 * lá phía client ({@code lib/features/game/data/career_cards.dart}): {@code
 * "CƠ HỘI 01"}, {@code "THỬ THÁCH 01"}... "CƠ HỘI" (thiếu dấu) là lá KHÁC và
 * bị từ chối. File Java này phải lưu UTF-8 không BOM.
 *
 * <p><b>allowedChoices.</b> Rỗng ⇒ lá đó KHÔNG dùng tham số {@code choice}
 * (client để {@code null}). Lá {@code CƠ HỘI 04} dùng tham số {@code
 * targetPlayerKey} chứ không dùng {@code choice}, nên {@code allowedChoices}
 * của nó cũng rỗng.
 */
@Component
public class CardEffectRegistry {

  /** Lựa chọn "+3 Điểm Chiến thắng" cho lá {@code CƠ HỘI 10}. */
  public static final String CHOICE_PLUS_3 = "PLUS_3";

  /** Lựa chọn "đi thêm 2 ô" cho lá {@code CƠ HỘI 10}. */
  public static final String CHOICE_MOVE_2 = "MOVE_2";

  private final Map<String, CardEffectSpec> specs = new LinkedHashMap<>();

  public CardEffectRegistry() {
    // ── 10 lá CƠ HỘI ───────────────────────────────────────────────────────
    put("CƠ HỘI 01", BonusEffect.NONE, 2);
    put("CƠ HỘI 02", BonusEffect.EXTRA_ROLL, 2);
    put("CƠ HỘI 03", BonusEffect.MOVE_2, 2);
    put("CƠ HỘI 04", BonusEffect.PICK_PLAYER_PLUS1, 1);
    put("CƠ HỘI 05", BonusEffect.NONE, 3);
    put("CƠ HỘI 06", BonusEffect.EXTRA_ROLL, 2);
    put("CƠ HỘI 07", BonusEffect.NONE, 2);
    put("CƠ HỘI 08", BonusEffect.ALL_PLAYERS_PLUS1, 1);
    put("CƠ HỘI 09", BonusEffect.MOVE_3, 2);
    put("CƠ HỘI 10", BonusEffect.PLUS3_OR_MOVE_2, 3, Set.of(CHOICE_PLUS_3, CHOICE_MOVE_2));

    // ── 10 lá THỬ THÁCH ─────────────────────────────────────────────────────
    put("THỬ THÁCH 01", BonusEffect.NONE, 2);
    put("THỬ THÁCH 02", BonusEffect.NONE, 2);
    put("THỬ THÁCH 03", BonusEffect.NONE, 2);
    put("THỬ THÁCH 04", BonusEffect.NONE, 3);
    put("THỬ THÁCH 05", BonusEffect.NONE, 2);
    put("THỬ THÁCH 06", BonusEffect.NONE, 2);
    put("THỬ THÁCH 07", BonusEffect.NONE, 3);
    put("THỬ THÁCH 08", BonusEffect.NONE, 2);
    put("THỬ THÁCH 09", BonusEffect.NONE, 2);
    put("THỬ THÁCH 10", BonusEffect.NONE, 3);
  }

  private void put(String cardId, BonusEffect effect, long winPoints) {
    put(cardId, effect, winPoints, Set.of());
  }

  private void put(String cardId, BonusEffect effect, long winPoints, Set<String> allowedChoices) {
    specs.put(cardId, new CardEffectSpec(effect, winPoints, allowedChoices));
  }

  /**
   * Tra hiệu ứng của một lá bài.
   *
   * @param cardId mã lá (có dấu); null/rỗng ⇒ {@code null}
   * @return đặc tả hiệu ứng, hoặc {@code null} nếu lá lạ
   */
  public CardEffectSpec effectOf(String cardId) {
    if (cardId == null) {
      return null;
    }
    return specs.get(cardId.trim());
  }

  /** Danh sách mã lá server biết — dùng cho test và đối chiếu với client. */
  public Set<String> cardIds() {
    return Collections.unmodifiableSet(specs.keySet());
  }

  /**
   * Đặc tả hiệu ứng của một lá.
   *
   * @param effect hiệu ứng phải áp
   * @param winPoints số điểm lá mang theo theo danh mục (lá di chuyển KHÔNG
   *     cộng điểm — số này chỉ là giá trị gốc của lá)
   * @param allowedChoices các lựa chọn hợp lệ của lá; rỗng ⇒ lá không cần chọn
   */
  public record CardEffectSpec(BonusEffect effect, long winPoints, Set<String> allowedChoices) {
    public CardEffectSpec {
      allowedChoices = allowedChoices == null ? Set.of() : Set.copyOf(allowedChoices);
    }

    /** Lá này có bắt buộc người chơi chọn không? */
    public boolean requiresChoice() {
      return !allowedChoices.isEmpty();
    }
  }
}
