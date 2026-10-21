package vn.careermap.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.careermap.config.VnpayConfig;

/**
 * ENDPOINT TẠM — dò quy tắc băm chữ ký mà gateway VNPay sandbox thật sự chấp nhận.
 *
 * <p><b>KHÔNG dùng ở production và PHẢI XOÁ Ở TASK SAU.</b> Endpoint này tự tính
 * chữ ký theo NHIỀU quy tắc khác nhau (mỗi biến thể một quy tắc) rồi trả về
 * payUrl tương ứng. Mở từng URL trên sandbox: biến thể nào render FORM NHẬP THẺ
 * thì đó là quy tắc gateway thật sự dùng.
 *
 * <p>Vì sao cần: {@code VnpaySignatureService} (HMAC-SHA512, key+value URL-encode)
 * bị gateway từ chối với "Sai chữ ký" ⇒ hoặc bộ tham số băm sai, hoặc cách
 * canonicalize sai, hoặc thuật toán sai (terminal sandbox có thể vẫn ở giao thức
 * 2.0.x: nối chuỗi {@code secret + hashdata} + bắt buộc gửi
 * {@code vnp_SecureHashType}).
 *
 * <p>Quy tắc bất biến: chuỗi dùng để TÍNH HASH phải giống HỆT chuỗi dùng
 * trong QUERY gửi đi (trừ biến thể cố ý dò sai lệch). Endpoint KHÔNG dùng
 * {@code VnpaySignatureService} — mục đích là dò từng quy tắc riêng.
 *
 * <p>An toàn: endpoint KHÔNG trả về {@code hashSecret}, chỉ trả payUrl đã ký.
 * Mỗi biến thể dùng {@code vnp_TxnRef} KHÁC NHAU ⇒ không đụng đơn thật.
 */
@RestController
@RequestMapping("/api/payments")
public class VnPayVariantProbeController {

  private static final DateTimeFormatter VN_DATE_TIME =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

  /** Mô tả đơn CÓ khoảng trắng (giống hệt {@code PaymentService}). */
  private static final String ORDER_INFO_SPACES = "Bao cao Holland";

  /** Mô tả đơn KHÔNG khoảng trắng (VNPay yêu cầu "không ký tự đặc biệt"). */
  private static final String ORDER_INFO_NO_SPACES = "BaocaoHolland";

  private static final String PROBE_AMOUNT = "5000000";
  private static final String PROBE_COMMAND = "pay";
  private static final String PROBE_VERSION = "2.1.0";
  private static final String PROBE_ORDER_TYPE = "other";
  private static final String PROBE_CURR_CODE = "VND";
  private static final String PROBE_LOCALE = "vn";
  private static final String PROBE_IP = "14.230.135.24";
  private static final int EXPIRE_MINUTES = 15;

  private final VnpayConfig config;

  public VnPayVariantProbeController(VnpayConfig config) {
    this.config = config;
  }

  /**
   * Trả về payUrl của TẤT CẢ biến thể quy tắc băm.
   *
   * @return mảng {@code {name, url}} — mỗi biến thể một quy tắc băm khác nhau
   */
  @GetMapping("/probe-variants")
  public List<ProbeVariant> probeVariants() {
    if (!config.isConfigured()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "VNPay chưa cấu hình đủ (thiếu " + String.join(", ", config.missingSettings()) + ")");
    }
    Instant now = Instant.now();
    List<ProbeVariant> result = new ArrayList<>(RULES.size());
    for (Rule rule : RULES) {
      result.add(build(rule, now));
    }
    return result;
  }

  /** Dựng payUrl cho MỘT biến thể theo quy tắc của nó. */
  private ProbeVariant build(Rule rule, Instant now) {
    Map<String, String> queryParams = new TreeMap<>();
    queryParams.put("vnp_TmnCode", nz(config.getTmnCode()));
    // TxnRef KHÁC NHAU cho mỗi biến thể ⇒ không đụng đơn thật nào.
    queryParams.put("vnp_TxnRef", "probe" + UUID.randomUUID().toString().replace("-", ""));
    queryParams.put("vnp_Amount", PROBE_AMOUNT);
    queryParams.put("vnp_Command", PROBE_COMMAND);
    queryParams.put("vnp_CreateDate", VN_DATE_TIME.format(now));
    queryParams.put("vnp_ExpireDate", VN_DATE_TIME.format(now.plusSeconds(EXPIRE_MINUTES * 60L)));
    queryParams.put("vnp_CurrCode", PROBE_CURR_CODE);
    queryParams.put("vnp_Locale", PROBE_LOCALE);
    queryParams.put("vnp_OrderInfo", rule.orderInfo);
    queryParams.put("vnp_OrderType", PROBE_ORDER_TYPE);
    queryParams.put("vnp_ReturnUrl", nz(config.getReturnUrl()));
    queryParams.put("vnp_IpAddr", PROBE_IP);
    queryParams.put("vnp_IpnUrl", nz(config.getIpnUrl()));
    queryParams.put("vnp_Version", PROBE_VERSION);

    if (!rule.queryIpnUrl) {
      queryParams.remove("vnp_IpnUrl");
    }
    if (!rule.queryExpireDate) {
      queryParams.remove("vnp_ExpireDate");
    }
    if (rule.queryHashType != null) {
      queryParams.put("vnp_SecureHashType", rule.queryHashType);
    }

    // Tập tham số dùng để BĂM (có thể khác tập gửi đi — đó là điều đang dò).
    Map<String, String> hashParams = new TreeMap<>(queryParams);
    hashParams.remove("vnp_SecureHash");
    if (!rule.hashTypeInHash) {
      hashParams.remove("vnp_SecureHashType");
    }
    if (!rule.hashIpnUrl) {
      hashParams.remove("vnp_IpnUrl");
    }
    if (!rule.hashExpireDate) {
      hashParams.remove("vnp_ExpireDate");
    }

    String hashData = canonical(hashParams, rule.rawValues, rule.rawKeys);
    String hash = rule.concatSecret
        ? digestHex(rule.algorithm,
            rule.secretLast ? hashData + config.getHashSecret() : config.getHashSecret() + hashData)
        : hmacHex(rule.algorithm, hashData, config.getHashSecret());

    return new ProbeVariant(rule.name, config.getBaseUrl() + "?" + queryString(queryParams, hash));
  }

  /**
   * Chuỗi đưa vào thuật toán: {@code key=value&key=value}, key theo alphabet.
   *
   * @param rawValues  {@code true} = dùng giá trị THÔ (không URL-encode)
   * @param rawKeys    {@code true} = dùng key THÔ (không URL-encode)
   */
  private static String canonical(Map<String, String> params, boolean rawValues, boolean rawKeys) {
    return params.entrySet().stream()
        .map(e -> (rawKeys ? e.getKey() : encode(e.getKey()))
            + "="
            + (rawValues ? e.getValue() : encode(e.getValue())))
        .collect(Collectors.joining("&"));
  }

  /** Query gửi đi: key + value URL-encode, {@code vnp_SecureHash} cuối chuỗi. */
  private static String queryString(Map<String, String> params, String hash) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : params.entrySet()) {
      sb.append(encode(e.getKey())).append('=').append(encode(e.getValue())).append('&');
    }
    sb.append("vnp_SecureHash=").append(hash);
    return sb.toString();
  }

  private static String encode(String raw) {
    return URLEncoder.encode(raw, StandardCharsets.UTF_8);
  }

  /** HMAC ({@code HmacSHA256} / {@code HmacSHA512}) — hex lowercase. */
  private static String hmacHex(String algorithm, String data, String secret) {
    try {
      Mac mac = Mac.getInstance(algorithm);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
      return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Không khởi tạo được " + algorithm, e);
    }
  }

  /** Digest thuần ({@code SHA-256} / {@code MD5}) — dùng cho giao thức 2.0.x. */
  private static String digestHex(String algorithm, String data) {
    try {
      MessageDigest md = MessageDigest.getInstance(algorithm);
      return HexFormat.of().formatHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Không khởi tạo được " + algorithm, e);
    }
  }

  private static String nz(String value) {
    return value == null ? "" : value.trim();
  }

  /**
   * Một biến thể = một quy tắc băm.
   *
   * <p>Mặc định: HMAC-SHA512, hashdata URL-encode cả key lẫn value, đủ tham số
   * trong cả query lẫn hashdata, không gửi {@code vnp_SecureHashType}.
   */
  private static final class Rule {

    private final String name;
    private String algorithm = "HmacSHA512";
    private boolean concatSecret;
    private boolean secretLast;
    private boolean rawValues;
    private boolean rawKeys;
    private String queryHashType;
    private boolean hashTypeInHash;
    private boolean queryIpnUrl = true;
    private boolean hashIpnUrl = true;
    private boolean queryExpireDate = true;
    private boolean hashExpireDate = true;
    private String orderInfo = ORDER_INFO_SPACES;

    Rule(String name) {
      this.name = name;
    }

    Rule alg(String value) {
      this.algorithm = value;
      return this;
    }

    /** Thuật toán NỐI CHUỖI (giao thức 2.0.x): {@code digest(secret + hashdata)}. */
    Rule concat() {
      this.concatSecret = true;
      return this;
    }

    /** Nối theo thứ tự ngược: {@code digest(hashdata + secret)}. */
    Rule dataLast() {
      this.concatSecret = true;
      this.secretLast = true;
      return this;
    }

    Rule rawValues() {
      this.rawValues = true;
      return this;
    }

    Rule rawKeys() {
      this.rawKeys = true;
      return this;
    }

    /** Gửi kèm {@code vnp_SecureHashType} trong query (KHÔNG băm vào). */
    Rule hashType(String value) {
      this.queryHashType = value;
      return this;
    }

    /** Băm CẢ {@code vnp_SecureHashType} vào hashdata. */
    Rule hashTypeInHash() {
      this.hashTypeInHash = true;
      return this;
    }

    /** Bỏ {@code vnp_IpnUrl} khỏi cả query lẫn hashdata. */
    Rule noIpnUrl() {
      this.queryIpnUrl = false;
      this.hashIpnUrl = false;
      return this;
    }

    /** Vẫn gửi {@code vnp_IpnUrl} nhưng KHÔNG băm vào. */
    Rule noIpnUrlInHash() {
      this.hashIpnUrl = false;
      return this;
    }

    /** Bỏ {@code vnp_ExpireDate} khỏi cả query lẫn hashdata. */
    Rule noExpireDate() {
      this.queryExpireDate = false;
      this.hashExpireDate = false;
      return this;
    }

    /** Vẫn gửi {@code vnp_ExpireDate} nhưng KHÔNG băm vào. */
    Rule noExpireDateInHash() {
      this.hashExpireDate = false;
      return this;
    }

    Rule orderInfo(String value) {
      this.orderInfo = value;
      return this;
    }
  }

  /**
   * Danh sách biến thể.
   *
   * <p>A–O: bộ biến thể BẮT BUỘC của task dò quy tắc băm.
   *
   * <p>P–W: biến thể BỔ SUNG (chẩn đoán) — cùng logic với K–N nhưng nối chuỗi
   * theo thứ tự ngược ({@code digest(hashdata + secret)}), băm cả
   * {@code vnp_SecureHashType}, hoặc bỏ cả {@code vnp_ExpireDate} lẫn
   * {@code vnp_IpnUrl} khỏi hashdata (phỏng đoán gateway chỉ băm tập tham số
   * chính thức của API 2.1.0).
   *
   * <p>GA–GI: biến thể BỔ SUNG (chẩn đoán) — cùng quy tắc với B (băm mọi tham số
   * trừ {@code vnp_IpnUrl}) nhưng chỉ khác GIÁ TRỊ {@code vnp_OrderInfo}, để
   * kiểm tra gateway có xử lý đặc biệt giá trị này hay không.
   */
  private static final List<Rule> RULES = List.of(
      // --- Bộ bắt buộc ---
      new Rule("A_control"),
      new Rule("B_hash_no_ipnurl").noIpnUrlInHash(),
      new Rule("C_no_ipnurl").noIpnUrl(),
      new Rule("D_sha256").alg("HmacSHA256"),
      new Rule("E_sha256_with_type").alg("HmacSHA256").hashType("SHA256"),
      new Rule("F_raw_values").rawValues(),
      new Rule("G_no_spaces_orderinfo").orderInfo(ORDER_INFO_NO_SPACES),
      new Rule("H_no_expire_in_hash").noExpireDateInHash(),
      new Rule("I_encode_only_value").rawKeys(),
      new Rule("J_no_ipnurl_sha256").noIpnUrl().alg("HmacSHA256"),
      new Rule("K_concat_sha256_raw").concat().alg("SHA-256").rawValues(),
      new Rule("L_concat_md5_raw").concat().alg("MD5").rawValues().hashType("MD5"),
      new Rule("M_concat_sha256_enc").concat().alg("SHA-256").hashType("SHA256"),
      new Rule("N_concat_md5_enc").concat().alg("MD5").hashType("MD5"),
      new Rule("O_concat_sha256_raw_noexpire").concat().alg("SHA-256").rawValues().noExpireDate(),
      // --- Bộ bổ sung (chẩn đoán) ---
      new Rule("P_concat_sha256_raw_datalast").dataLast().alg("SHA-256").rawValues(),
      new Rule("Q_concat_md5_raw_datalast").dataLast().alg("MD5").rawValues().hashType("MD5"),
      new Rule("R_concat_sha256_enc_datalast").dataLast().alg("SHA-256").hashType("SHA256"),
      new Rule("S_concat_md5_enc_datalast").dataLast().alg("MD5").hashType("MD5"),
      new Rule("T_hmac_sha256_type_in_hash").alg("HmacSHA256").hashType("SHA256").hashTypeInHash(),
      new Rule("U_hmac_sha512_type_in_hash").hashType("SHA512").hashTypeInHash(),
      new Rule("V_hmac_sha512_no_expire_no_ipn").noExpireDate().noIpnUrl(),
      new Rule("W_hmac_sha512_hash_no_expire_no_ipn").noExpireDateInHash().noIpnUrlInHash(),
      // --- Bộ chẩn đoán vnp_OrderInfo (cùng quy tắc với B, chỉ khác OrderInfo) ---
      new Rule("GA_oi_spaces_control").noIpnUrlInHash().orderInfo(ORDER_INFO_SPACES),
      new Rule("GB_oi_nospace").noIpnUrlInHash().orderInfo(ORDER_INFO_NO_SPACES),
      new Rule("GC_oi_two_spaces").noIpnUrlInHash().orderInfo("Bao  cao Holland"),
      new Rule("GD_oi_space_short").noIpnUrlInHash().orderInfo("Bao cao"),
      new Rule("GE_oi_nospace_long").noIpnUrlInHash().orderInfo("BaocaoHollandBaoCaoHollandTest"),
      new Rule("GF_oi_diacritics").noIpnUrlInHash().orderInfo("Báo cáo Holland"),
      new Rule("GH_oi_hyphen").noIpnUrlInHash().orderInfo("Bao-cao-Holland"),
      new Rule("GI_oi_plus_literal").noIpnUrlInHash().orderInfo("Bao+cao+Holland"));

  /** Một biến thể trả về cho client: tên + payUrl hoàn chỉnh. */
  public record ProbeVariant(String name, String url) {}
}
