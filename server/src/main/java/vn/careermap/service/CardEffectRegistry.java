package vn.careermap.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.careermap.domain.BonusEffect;
import vn.careermap.domain.TileType;

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
 *
 * <p><b>Lá giả của ô ⭐ ({@link #STAR_CARD_ID}).</b> Ô ⭐ cho +1 Điểm Chiến
 * thắng. Trước đây ô ⭐ không mở lượt trả lời ⇒ client tự cộng +1 ở máy ⇒
 * bảng xếp hạng trên server sai. Nay ô ⭐ cũng mở lượt trả lời và server cấp
 * lá giả này (hiệu ứng NONE, 1 điểm) ⇒ điểm ô sao do server quyết.
 */
@Component
public class CardEffectRegistry {

  /** Lựa chọn "+3 Điểm Chiến thắng" cho lá {@code CƠ HỘI 10}. */
  public static final String CHOICE_PLUS_3 = "PLUS_3";

  /** Lựa chọn "đi thêm 2 ô" cho lá {@code CƠ HỘI 10}. */
  public static final String CHOICE_MOVE_2 = "MOVE_2";

  /** Tiền tố mã lá CƠ HỘI — dùng để tách bộ lá theo loại ô. */
  public static final String CHANCE_PREFIX = "CƠ HỘI ";

  /** Tiền tố mã lá THỬ THÁCH — dùng để tách bộ lá theo loại ô. */
  public static final String CHALLENGE_PREFIX = "THỬ THÁCH ";

  /**
   * Mã LÁ GIẢ của ô ⭐ — KHÔNG phải một trong 20 lá Cơ hội / Thử thách.
   *
   * <p>Ô ⭐ cho +1 Điểm Chiến thắng. Trước đây ô ⭐ KHÔNG mở lượt trả lời
   * (xem {@code TurnCoordinator#needsAnswerAt}) ⇒ server không hề biết người
   * chơi dừng ở ô sao ⇒ client phải TỰ cộng +1 ở máy ⇒ <b>bảng xếp hạng trên
   * server sai</b>.
   *
   * <p>Nay ô ⭐ cũng mở lượt trả lời và server cấp lá giả này: hiệu ứng NONE,
   * 1 điểm — cùng cơ chế "lá do server cấp" với ô Cơ hội / Thử thách, nên
   * điểm ô sao cũng do server quyết, không có chỗ cho client tự khai.
   *
   * <p>Mã KHÔNG có dấu ("SAO") để không lẫn với danh mục 20 lá phía client
   * ({@code lib/features/game/data/career_cards.dart}).
   */
  public static final String STAR_CARD_ID = "SAO";

  private final Map<String, CardEffectSpec> specs = new LinkedHashMap<>();

  /**
   * Lá giả của ô ⭐: không có hiệu ứng phụ, chỉ +1 Điểm Chiến thắng.
   *
   * <p>Cố ý KHÔNG nằm trong {@link #specs} ⇒ {@link #cardIds()} vẫn trả về đúng
   * 20 lá Cơ hội / Thử thách để đối chiếu với danh mục phía client.
   */
  private final CardEffectSpec starSpec = new CardEffectSpec(BonusEffect.NONE, 1, Set.of());

  /** Bộ lá CƠ HỘI — rút khi người chơi rơi vào ô {@code CHANCE}. */
  private final List<String> chanceDeck = new ArrayList<>();

  /** Bộ lá THỬ THÁCH — rút khi người chơi rơi vào ô {@code CHALLENGE}. */
  private final List<String> challengeDeck = new ArrayList<>();

  private final SecureRandom random = new SecureRandom();

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
    if (cardId.startsWith(CHANCE_PREFIX)) {
      chanceDeck.add(cardId);
    } else if (cardId.startsWith(CHALLENGE_PREFIX)) {
      challengeDeck.add(cardId);
    }
  }

  /**
   * RÚT 1 lá theo loại ô — SERVER là nguồn sự thật về "người chơi rút được lá
   * nào".
   *
   * <p>Trước đây client tự rút ở máy rồi tự khai {@code cardId} lên server ⇒
   * người chơi tự chọn được lá đẹp nhất (MOVE_3, EXTRA_ROLL miễn phí). Nay
   * server rút ngay khi người chơi rơi vào ô thẻ và lưu mã lá vào phòng (xem
   * {@code Room#getPendingCardId()}); client chỉ hiển thị theo lá server cấp.
   *
   * @param tileType loại ô người chơi vừa rơi vào
   * @return mã lá (có dấu), {@link #STAR_CARD_ID} cho ô ⭐, hoặc {@code null} nếu
   *     ô đó không phải ô thẻ (ô RIASEC, ô START)
   */
  public String drawCardFor(TileType tileType) {
    if (tileType == TileType.STAR) {
      // Ô ⭐ ⇒ lá GIẢ +1 điểm (không thuộc bộ Cơ hội / Thử thách).
      return STAR_CARD_ID;
    }
    List<String> deck = deckFor(tileType);
    if (deck.isEmpty()) {
      return null;
    }
    return deck.get(random.nextInt(deck.size()));
  }

  private List<String> deckFor(TileType tileType) {
    if (tileType == TileType.CHANCE) {
      return chanceDeck;
    }
    if (tileType == TileType.CHALLENGE) {
      return challengeDeck;
    }
    return List.of();
  }

  /** Bộ 10 lá CƠ HỘI — dùng để đối chiếu và cho test. */
  public List<String> chanceCardIds() {
    return List.copyOf(chanceDeck);
  }

  /** Bộ 10 lá THỬ THÁCH — dùng để đối chiếu và cho test. */
  public List<String> challengeCardIds() {
    return List.copyOf(challengeDeck);
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
    String key = cardId.trim();
    CardEffectSpec spec = specs.get(key);
    if (spec != null) {
      return spec;
    }
    // Lá GIẢ của ô ⭐ không nằm trong bảng 20 lá nhưng vẫn phải tra được
    // (hiệu ứng NONE, 1 điểm) — cùng đường với 20 lá thật.
    return STAR_CARD_ID.equals(key) ? starSpec : null;
  }

  /**
   * Danh sách mã lá CƠ HỘI / THỬ THÁCH server biết — dùng cho test và đối
   * chiếu với client.
   *
   * <p>KHÔNG gồm {@link #STAR_CARD_ID}: đó là lá giả của ô ⭐, không thuộc
   * danh mục 20 lá phía client.
   */
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

    /**
     * Số Điểm Chiến thắng lá mang khi CHỈ CẦN CỘNG ĐIỂM.
     *
     * <p>Lá di chuyển (MOVE_2 / MOVE_3) KHÔNG cộng điểm — hiệu ứng của chúng là
     * dời quân, không phải cộng điểm. Cùng quy tắc với
     * {@code GameService.applyBonusEffect} để hai đường không lệch nhau.
     */
    public long awardWinPoints() {
      return switch (effect) {
        case MOVE_2, MOVE_3 -> 0L;
        default -> winPoints;
      };
    }
  }
}
