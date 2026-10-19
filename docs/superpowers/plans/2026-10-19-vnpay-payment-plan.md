# VNPay Payment + Holland Report — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thay VietQR bằng VNPay sandbox để người đủ điều kiện mua được báo cáo Holland riêng, có xác nhận tự động qua IPN, đếm ngược 15 phút, lịch sử thanh toán.

**Architecture:** Server tự tính ai đủ điều kiện và ai thắng; client không còn tự quyết. Đơn hàng lưu bảng `payment_orders`, VNPay bắn IPN về xác nhận. Flutter chuyển hẹn sang vnpay.vn rồi quay lại một trang chờ tự poll.

**Tech Stack:** Spring Boot 3.4 / Java 21 / JPA / PostgreSQL · Flutter web / provider / shared_preferences

**Spec:** `docs/superpowers/specs/2026-10-19-vnpay-payment-design.md`

## Global Constraints

- Thuật toán chữ ký VNPay: **HMAC-SHA256**, khoá là `HashSecret`. KHÔNG dùng SHA512.
- Khoá tra đơn là `vnp_TxnRef` (ta sinh). `vnp_TxnNo` do VNPay gán, không dùng để tra.
- `HashSecret` chỉ nằm ở server, đọc qua biến môi trường. Không hardcode, không vào Flutter, không commit.
- IPN **luôn trả HTTP 200**, bắt mọi exception trong thân hàm (có `ConflictAdvice` sẽ trả 5xx và VNPay retry vô hạn).
- Số tiền: `50000` VND. Hạn đơn: `15` phút.
- Điều kiện thắng: `completedLaps >= 1 && totalAnswered >= 12`.
- Câu thông báo cho người chưa đủ điều kiện giữ **nguyên chữ**, không sửa: `'Bạn đã chơi rất tốt — vẫn chưa đủ điều kiện chiến thắng. Cố gắng hơn ở những lượt sau nhé! 🌟'`
- Ngưỡng mới **phải** đặt trong `GameRulesConfig`, không hardcode trong service.
- Test backend dùng JUnit 5 + AssertJ thuần, không Spring context, không DB. Theo mẫu `server/src/test/java/vn/careermap/service/RoomLifecycleTest.java`.

## Review Focus

Năm rủi ro dễ sót nhất — mỗi dòng gắn với test ở task tương ứng:

1. **IPN tới sau khi đơn đã `EXPIRED`** (người trả lúc phút 14:59, IPN tới phút 15:30). Tiền đã vào → phải nhận `PAID`. Test ở Task 6.
2. **IPN sai chữ ký hoặc `orderId` không tồn tại.** Phải trả 200 chứ không được ném exception ra ngoài. Test ở Task 6.
3. **Server đang ngủ khi IPN tới** (Render free). Test ở Task 1 cho endpoint `/health`; vận hành bằng UptimeRobot.
4. **Điểm RIASEC biến mất trước khi tạo đơn** — phòng bị xoá cascade sau 30 phút. Test ở Task 3.
5. **Flutter mở `/ket-qua-thanh-toan` mà không có router** → trắng, không chạy. Test ở Task 8.

---

### Task 1: Cấu hình luật chơi + thanh toán

**Files:**
- Modify: `server/src/main/java/vn/careermap/config/GameRulesConfig.java`
- Modify: `server/src/main/resources/application.properties`
- Modify: `server/src/test/java/vn/careermap/service/RoomLifecycleTest.java:24-25`
- Modify: `server/src/main/java/vn/careermap/service/GameSnapshotBuilder.java`

**Interfaces:**
- Produces: `GameRulesConfig` constructor mới nhận thêm 4 tham số cuối `long paymentAmountVnd, long paymentTtlMinutes, int winMinLaps, int winMinAnswers` → tổng 13 tham số.
- Produces: getter `getPaymentAmountVnd()`, `getPaymentTtlMinutes()`, `getWinMinLaps()`, `getWinMinAnswers()`.

- [ ] **Step 1: Viết test fail**

Thêm vào `server/src/test/java/vn/careermap/config/GameRulesConfigTest.java` (file mới):

```java
@Test
void paymentAmountLaNamTramKhong() {
  assertThat(RULES.getPaymentAmountVnd()).isEqualTo(50000L);
}

@Test
void nguongThangLaMotVongVaMuoiHaiCau() {
  assertThat(RULES.getWinMinLaps()).isEqualTo(1);
  assertThat(RULES.getWinMinAnswers()).isEqualTo(12);
}
```

với `RULES = new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12);`

- [ ] **Step 2: Chạy để thấy fail**

Run: `cd server && ./mvnw test -Dtest=GameRulesConfigTest`
Expected: FAIL — không tìm thấy constructor 13 tham số.

- [ ] **Step 3: Sửa GameRulesConfig**

Thêm 4 field cuối + 4 `@Value` vào constructor, theo đúng kiểuu hiện có:
`@Value("${payment.amount-vnd:50000}")`, `@Value("${payment.ttl-minutes:15}")`, `@Value("${game.win-min-laps:1}")`, `@Value("${game.win-min-answers:12}")`.

- [ ] **Step 4: Thêm vào application.properties**

```properties
game.win-min-laps=${WIN_MIN_LAPS:1}
game.win-min-answers=${WIN_MIN_ANSWERS:12}
payment.amount-vnd=${PAYMENT_AMOUNT_VND:50000}
payment.ttl-minutes=${PAYMENT_TTL_MINUTES:15}
```

- [ ] **Step 5: Sửa chỗ gọi constructor cũ**

`RoomLifecycleTest.java:24-25` đổi thành 13 tham số như Step 1. Grep toàn repo tìm chỗ gọi khác và sửa hết.

- [ ] **Step 6: Trả 4 giá trị này qua `GET /api/game/config`**

Đọc cách `GameSnapshotBuilder` đang đóng gói luật chơi, thêm 4 trường mới theo đúng tên/khoá đang dùng.

- [ ] **Step 7: Chạy test**

Run: `cd server && ./mvnw test`
Expected: PASS toàn bộ.

- [ ] **Step 8: Commit**

```bash
git add server/
git commit -m "feat(config): payment amount, ttl va nguong thang vao GameRulesConfig"
```

---

### Task 2: Server tự tính người thắng + chặn quyền host

**Files:**
- Modify: `server/src/main/java/vn/careermap/service/GameService.java:166-192`
- Modify: `server/src/main/java/vn/careermap/web/dto/FinishRequest.java`
- Modify: `server/src/main/java/vn/careermap/domain/Room.java`
- Test: `server/src/test/java/vn/careermap/service/GameFinishTest.java` (mới)

**Interfaces:**
- Consumes: `GameRulesConfig.getWinMinLaps()`, `getWinMinAnswers()`.
- Produces: `GameService.finish(String playerId, FinishRequest)` tự gán `room.setWinnerKey(...)`, không đọc từ request.
- Produces: `FinishRequest(String playerId)` — xoá trường `winnerKey`.

- [ ] **Step 1: Viết test fail**

`GameFinishTest.java` (JUnit + AssertJ thuần, dựng `Room`/`Player` trực tiếp như `RoomLifecycleTest`):

```java
@Test
void khongPhaiChuPhongKhongKetThucDuoc()  // guest gọi finish -> throw, room vẫn WAITING
@Test
void chuPhongKetThucDuoc()               // owner gọi finish -> room FINISHED
@Test
void nguoiDatDieuKienDauTienLaNguoiThang()
@Test
void ketThucRoiVanChoPhepGoiLai()        // gọi finish 2 lần, lần 2 không nổi
```

Test 3: hai người cùng `totalAnswered=12`, `completedLaps=1` → `room.getWinnerKey()` bằng `joinOrder` nhỏ hơn.

- [ ] **Step 2: Chạy để thấy fail**

Run: `cd server && ./mvnw test -Dtest=GameFinishTest`
Expected: FAIL — `finish()` chưa chặn host, chưa tự tính.

- [ ] **Step 3: Sửa `GameService.finish()`**

Thứ tự bắt buộc trong thân hàm:
1. `resolvePlayer` → `player.getRoom()` → nếu null, 400.
2. **Kiểm tra host TRƯỚC** nhánh `if (room.isFinished()) return ...` — dùng `playerKey.equals(room.effectiveHostKey())`, nếu không thì `throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ chủ phòng mới kết thúc được ván")`.
3. Nếu đã finished thì return snapshot sớm.
4. Tự tính: duyệt `room.getPlayers()`, lấy người đầu tiên có `completedLaps >= winMinLaps && totalAnswered >= winMinAnswers` theo thứ tự `Room.getPlayers()` (đã `@OrderBy("playOrder ASC, joinOrder ASC")`). Không ai đạt → `winnerKey = null`.

- [ ] **Step 4: Rút gọn `FinishRequest` thành `record FinishRequest(String playerId) {}`**

Grep chỗ gọi còn lại và sửa.

- [ ] **Step 5: Gán `RoomStatus.PLAYING`**

Trong `GameService` khi phòng bắt đầu ván (sau khi host bấm bắt đầu) — tìm chỗ gán `TurnState`/`RoomStatus.WAITING` đầu tiên và đổi sang `PLAYING`. Kiểm bằng test: phòng đã bắt đầu thì `room.getStatus() == RoomStatus.playing`.

- [ ] **Step 6: Chạy test**

Run: `cd server && ./mvnw test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add server/
git commit -m "feat(game): server tu tinh nguoi thang va chan quyen ket thuc cho khong phai host"
```

---

### Task 3: Chụp điểm RIASEC lúc kết thúc ván

**Files:**
- Modify: `server/src/main/java/vn/careermap/domain/Room.java`
- Modify: `server/src/main/java/vn/careermap/service/GameService.java` (trong `finish()`)
- Test: thêm vào `GameFinishTest.java`

**Interfaces:**
- Produces: `Room.getRiasecSnapshot()` trả `Map<String,Integer>` (key = code R/I/A/S/E/C, value = điểm), rỗng nếu chưa chụp. `Room.setRiasecSnapshot(Map<String,Integer>)`.

- [ ] **Step 1: Viết test fail**

```java
@Test
void ketThucVanLuuDiemRiasecCuaMoiNguoi()  // mỗi player có scores riêng -> snapshot giữ đúng
@Test
void phongBiXoaThiDiemVanCon()             // gọi snapshot sau khi phòng finished -> vẫn có dữ liệu
```

- [ ] **Step 2: Chạy để thấy fail**

Run: `cd server && ./mvnw test -Dtest=GameFinishTest`
Expected: FAIL — chưa có snapshot.

- [ ] **Step 3: Thêm field vào `Room`**

```java
@ElementCollection
@CollectionTable(name = "room_riasec_snapshot", joinColumns = @JoinColumn(name = "room_id"))
@MapKeyColumn(name = "riasec_code")
@Column(name = "points", nullable = false)
private Map<String, Integer> riasecSnapshot = new LinkedHashMap<>();
```

Cộng dồn `points` của từng player (không ghi đè).

- [ ] **Step 4: Gọi chụp trong `finish()` trước khi broadcast**

Đặt sau khi xác định winner, trước `broadcaster.gameFinished(...)`.

- [ ] **Step 5: Chạy test**

Run: `cd server && ./mvnw test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/
git commit -m "feat(game): chup diem RIASEC khi ket thuc van truoc khi phong bi don dep"
```

---

### Task 4: Entity + repository PaymentOrder

**Files:**
- Create: `server/src/main/java/vn/careermap/domain/PaymentOrder.java`
- Create: `server/src/main/java/vn/careermap/domain/PaymentStatus.java`
- Create: `server/src/main/java/vn/careermap/repo/PaymentOrderRepository.java`
- Test: `server/src/test/java/vn/careermap/domain/PaymentOrderTest.java` (mới)

**Interfaces:**
- Produces: `PaymentOrder` fields `id` (String = `vnp_TxnRef`), `playerKey`, `roomCode`, `amount`, `status` (`PaymentStatus`), `createdAt`, `expiresAt`, `paidAt`.
- Produces: `PaymentStatus { PENDING, PAID, EXPIRED }` enum, `@Enumerated(EnumType.STRING)`.
- Produces: `PaymentOrderRepository.findByIdAndPlayerKey(String, String)`, `findByPlayerKeyOrderByCreatedAtDesc(String)`, `findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(String, PaymentStatus)`.

- [ ] **Step 1: Viết test fail**

```java
@Test
void donMoiMacDinhLaPendingVaCoHan()
@Test
void hanDungKhiTao()          // expiresAt = createdAt + ttl
```

- [ ] **Step 2: Chạy để thấy fail**

Run: `cd server && ./mvnw test -Dtest=PaymentOrderTest`
Expected: FAIL — class chưa tồn tại.

- [ ] **Step 3: Tạo `PaymentStatus.java`** — enum ba hằng.

- [ ] **Step 4: Tạo `PaymentOrder.java`**

Bám đúng quy ước entity sẵn có: `@Entity @Table(name="payment_orders")`, cột `nullable = false`, `@Enumerated(EnumType.STRING)`. Không có migration — `ddl-auto=update` tự tạo bảng.

- [ ] **Step 5: Tạo `PaymentOrderRepository.java`** — ba method ở Interfaces, mỗi method một dòng.

- [ ] **Step 6: Chạy test**

Run: `cd server && ./mvnw test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add server/
git commit -m "feat(payment): them entity PaymentOrder va repository"
```

---

### Task 5: Ký đơn VNPay + tạo đơn

**Files:**
- Create: `server/src/main/java/vn/careermap/config/VnpayConfig.java`
- Create: `server/src/main/java/vn/careermap/service/VnpaySignatureService.java`
- Create: `server/src/main/java/vn/careermap/service/PaymentService.java`
- Create: `server/src/main/java/vn/careermap/web/PaymentController.java`
- Create: `server/src/main/java/vn/careermap/web/dto/CreatePaymentRequest.java`, `CreatePaymentResponse.java`
- Test: `server/src/test/java/vn/careermap/service/VnpaySignatureTest.java`, `PaymentServiceTest.java` (mới)

**Interfaces:**
- Produces: `VnpaySignatureService.sign(Map<String,String> params)` → hex `String` (HMAC-SHA256). Bỏ `vnp_SecureHash` và giá trị null/rỗng khỏi input, sắp xếp key alphabet, nối `key=value` bằng `&`.
- Produces: `PaymentService.createOrder(String playerKey)` → `CreatePaymentResponse(orderId, payUrl, expiresAt)`.
- `POST /api/payments/create` body `{ "playerKey": "..." }`.

- [ ] **Step 1: Viết test fail — chữ ký**

```java
@Test
void chuKyDungThuAToanSha256()   // vector cố định: secret "secret", data "a=1&b=2" -> hex dài 64 ký tự
@Test
void boQuaSecureHashVaGiaTriRong()
```

Hex HMAC-SHA256 dài **64** ký tự. Nếu test ra 128 ký tự thì đang dùng nhầm SHA512.

- [ ] **Step 2: Viết test fail — tạo đơn**

```java
@Test
void taoDonChoNguoiDuDieuKien()   // completedLaps=1, totalAnswered=12 -> trả payUrl
@Test
void nguoiChuaDuKhongTaoDuoc()    // totalAnswered=11 -> ResponseStatusException 403
@Test
void khongPhaiNguoiThatRaKhongTaoDuoc()
@Test
void taoLaiThiTraLaiDonCungDangCho()  // đơn PENDING chưa hạn -> không tạo đơn mới
@Test
void payUrlCoVnpTxnRefLaIdCuaDon()
```

- [ ] **Step 3: Chạy để thấy fail**

Run: `cd server && ./mvnw test -Dtest=VnpaySignatureTest,PaymentServiceTest`
Expected: FAIL — chưa có class.

- [ ] **Step 4: Tạo `VnpayConfig.java`**

`@Component`, đọc 5 `@Value`: `vnpay.enabled`, `vnpay.tmn-code`, `vnpay.hash-secret`, `vnpay.return-url`, `vnpay.ipn-url`. Đặt key trong `application.properties` với dạng `${VNPAY_*:}` và giá trị mặc định rỗng.

- [ ] **Step 5: Tạo `VnpaySignatureService.java`**

`javax.crypto.Mac` với `"HmacSHA256"`, `SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256")`, hex lowercase.

- [ ] **Step 6: Tạo `PaymentService.java`**

`createOrder(playerKey)`:
- Nạp player, kiểm tra `completedLaps >= winMinLaps && totalAnswered >= winMinAnswers`, không đạt → 403.
- Tìm đơn `PENDING` gần nhất còn hạn qua `findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc`; nếu `expiresAt` còn hạn thì trả lại.
- Không có thì tạo đơn, `id` = UUID không dấu gạch ngắn.
- Dựng map `vnp_*`: `vnp_TmnCode`, `vnp_TxnRef`, `vnp_Amount` (nhân 100), `vnp_Command=pay_web`, `vnp_CreateDate`, `vnp_ExpireDate`, `vnp_CurrCode=VND`, `vnp_Locale=vn`, `vnp_ReturnUrl`, `vnp_IpnUrl`, `vnp_TxnExpireDate`.
- Ký, ghép query string, `payUrl` = `https://sandbox.vnpayment.vn/paymentgateway/paymenturl.htm?...`.

- [ ] **Step 7: Tạo `PaymentController` + 2 DTO**

`CreatePaymentRequest(String playerKey)`, `CreatePaymentResponse(String orderId, String payUrl, Instant expiresAt)`.

- [ ] **Step 8: Chạy test**

Run: `cd server && ./mvnw test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add server/
git commit -m "feat(payment): ky chu ky VNPay HMAC-SHA256 va tao don thanh toan"
```

---

### Task 6: Endpoint IPN

**Files:**
- Modify: `server/src/main/java/vn/careermap/service/PaymentService.java`
- Modify: `server/src/main/java/vn/careermap/web/PaymentController.java`
- Test: `server/src/test/java/vn/careermap/service/PaymentIpnTest.java` (mới)

**Interfaces:**
- Produces: `PaymentService.handleIpn(Map<String,String> params)` → `String` (thân trả về cho VNPay). Không ném exception ra ngoài.
- `POST /api/payments/ipn`, consumes `application/x-www-form-urlencoded`.

- [ ] **Step 1: Viết test fail**

```java
@Test
void ipnHopLeThiDonThanhPAID()
@Test
void ipnSaiChuKyKhongDoiTrangThai()      // vẫn trả "Transaction Status: 01"-nội dung, đơn vẫn PENDING
@Test
void ipnOrderIdKhongTonTaiKhongNemLoi()    // trả bình thường, không throw
@Test
void ipnDenSauKhiDonHetHanVanThanhPAID()   // đơn EXPIRED + tiền đã tới -> PAID
@Test
void ipnGoiLaiNhieuLanKhongSai()           // gọi 3 lần -> vẫn PAID, paidAt không đổi lần 2
```

- [ ] **Step 2: Chạy để thấy fail**

Run: `cd server && ./mvnw test -Dtest=PaymentIpnTest`
Expected: FAIL.

- [ ] **Step 3: Cài `handleIpn`**

Toàn bộ thân hàm bọc `try { ... } catch (Exception e) { log.warn(...); }`, luôn trả chuỗi `Transaction Status: 01`-style mà VNPay coi là 2xx.

Thứ tự:
1. Tính lại chữ ký từ các tham số nhận được (loại `vnp_SecureHash`), so với `vnp_SecureHash`. Sai → thoát sớm, trả 200.
2. `vnp_TransactionStatus` phải bằng `"01"`, nếu không thì thoát sớm.
3. Tra đơn bằng `vnp_TxnRef`.
4. Nếu đơn đã `PAID` → trả 200, không đổi gì.
5. Đặt `status=PAID`, `paidAt=Instant.now()`, lưu.

- [ ] **Step 4: Thêm endpoint**

```java
@PostMapping("/api/payments/ipn")
@ResponseStatus(HttpStatus.OK)
public String ipn(@RequestParam MultiValueMap<String, String> params) {
  return paymentService.handleIpn(toMap(params));
}
```

- [ ] **Step 5: Chạy test**

Run: `cd server && ./mvnw test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/
git commit -m "feat(payment): endpoint IPN xac nhan thanh toan tu VNPay"
```

---

### Task 7: Tra trạng thái đơn + lịch sử

**Files:**
- Modify: `server/src/main/java/vn/careermap/service/PaymentService.java`
- Modify: `server/src/main/java/vn/careermap/web/PaymentController.java`
- Create: `server/src/main/java/vn/careermap/web/dto/PaymentStatusResponse.java`, `PaymentHistoryItem.java`
- Test: `server/src/test/java/vn/careermap/service/PaymentStatusTest.java` (mới)

**Interfaces:**
- Produces: `PaymentService.getStatus(String orderId, String playerKey)` → `PaymentStatusResponse`.
- Produces: `PaymentService.history(String playerKey)` → `List<PaymentHistoryItem>`, tối đa 100.
- `GET /api/payments/{orderId}?playerKey=` và `GET /api/payments/history?playerKey=`.
- `PaymentStatusResponse(String orderId, String status, long amount, Instant expiresAt, String hollandCode)`.
- `PaymentHistoryItem(String orderId, String status, long amount, Instant createdAt, Instant expiresAt, String hollandCode)`.

- [ ] **Step 1: Viết test fail**

```java
@Test
void donHetHanThiTraVeExpired()       // đọc đơn quá hạn -> status EXPIRED (lazy, lưu luôn)
@Test
void donCuaNguoiKhacKhongXemDuoc()    // orderId của người khác -> 404
@Test
void lichSuMoiNhatTruoc()             // 3 đơn -> thứ tự createdAt giảm dần
@Test
void lichSuGhiMaHolland()             // đơn PAID có hollandCode
```

`hollandCode` tính từ điểm RIASEC lưu trong `riasecSnapshot`: nhóm có điểm cao nhất → `code`. Hoà thì lấy theo thứ tự `R, I, A, S, E, C`.

- [ ] **Step 2: Chạy để thây fail**

Run: `cd server && ./mvnw test -Dtest=PaymentStatusTest`
Expected: FAIL.

- [ ] **Step 3: Cài `getStatus` + `history`**

`getStatus`: nạp đơn, so `playerKey`, nếu `status == PENDING && now.isAfter(expiresAt)` thì đặt `EXPIRED` và lưu — **không** dùng `@Scheduled` vì Render free ngủ nên lịch không chạy đều.

- [ ] **Step 4: Thêm 2 endpoint + 2 DTO**

- [ ] **Step 5: Chạy test**

Run: `cd server && ./mvnw test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/
git commit -m "feat(payment): tra trang thai don va lich su thanh toan"
```

---

### Task 8: Flutter — router + mang orderId qua chuyển hướng

**Files:**
- Modify: `lib/main.dart`
- Modify: `lib/app.dart`
- Modify: `lib/core/storage/session_store.dart`
- Create: `lib/features/game/presentation/payment_page.dart`
- Modify: `pubspec.yaml`
- Test: `test/payment_route_test.dart` (mới)

**Interfaces:**
- Produces: `SessionStore.savePendingOrderId(String)` / `SessionStore.getPendingOrderId` / `SessionStore.clearPendingOrder()`, theo đúng pattern 3 hằng `_k*` sẵn có.
- Produces: route `/ket-qua-thanh-toan` → `PaymentPage`.

- [ ] **Step 1: Viết test fail**

```dart
testWidgets('mo duong dan ket qua thanh toan ra trang cho', (tester) async {
  await tester.pumpWidget(const MaterialApp(initialRoute: '/ket-qua-thanh-toan', ...));
  expect(find.byType(PaymentPage), findsOneWidget);
});
```

- [ ] **Step 2: Chạy để thây fail**

Run: `cd .. && flutter test test/payment_route_test.dart`
Expected: FAIL — không có route.

- [ ] **Step 3: Bật chiến lược URL trong `main.dart`**

Thêm `usePathUrlStrategy()` từ `package:flutter_web_plugins/url_strategy.dart` trước `runApp`. Thêm dependency `flutter_web_plugins` vào `pubspec.yaml` nếu chưa có.

- [ ] **Step 4: Đăng ký route trong `app.dart`**

`routes: { '/ket-qua-thanh-toan': (_) => PaymentPage(session: session) }`, giữ nguyên `home:` hiện tại.

- [ ] **Step 5: Thêm 3 hằng + 3 hàm vào `SessionStore`**

- [ ] **Step 6: Tạo `PaymentPage` tối thiểu**

Hiện tiêu đề + đang tải. Task 9 sẽ đầy đủ.

- [ ] **Step 7: Chạy test**

Run: `cd .. && flutter test test/payment_route_test.dart`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A lib/ test/ pubspec.yaml
git commit -m "feat(payment): them route trang ket qua thanh toan"
```

> Lưu ý: `lib/` **không** được Git track trong repo này. Chỉ commit nếu người dùng yêu cầu; nếu không thì bỏ bước commit này.

---

### Task 9: Flutter — trang chờ thanh toán, đếm ngược

**Files:**
- Create: `lib/features/game/data/payment_api.dart`
- Modify: `lib/features/game/presentation/payment_page.dart`
- Test: `test/payment_wait_test.dart` (mới)

**Interfaces:**
- Produces: `PaymentApi.createOrder(String playerKey)` → `Future<CreateOrderResult>`; `PaymentApi.status(String orderId, String playerKey)` → `Future<PaymentState>`.
- `CreateOrderResult(String orderId, String payUrl, DateTime expiresAt)`.
- `PaymentState(String status, int amount, DateTime expiresAt, String hollandCode)`.

- [ ] **Step 1: Viết test fail**

```dart
testWidgets('don da thanh toan thi ra trang danh gia', ...)
testWidgets('con cho thi dem nguoc', ...)
testWidgets('het 15 phut thi ve man hinh chinh', ...)
```

- [ ] **Step 2: Chạy để thây fail**

Run: `flutter test test/payment_wait_test.dart`
Expected: FAIL.

- [ ] **Step 3: Tạo `payment_api.dart`**

Theo đúng cách `game_api.dart` đang gọi REST (dùng chung `_ensureServerAwake` nếu tiện). JSON parse tay, không thêm package.

- [ ] **Step 4: Cài vòng lặp poll trong `PaymentPage`**

Mỗi 2 giây gọi `status()`. `PAID` → điều hướng sang trang đánh giá. `PENDING` → vẽ đồng hồ đếm ngược từ `expiresAt`, mỗi giây. Quá giờ hoặc nhận `EXPIRED` → `Navigator.popUntil((r) => r.isFirst)`.

- [ ] **Step 5: Chạy test**

Run: `flutter test test/payment_wait_test.dart`
Expected: PASS.

- [ ] **Step 6: Commit**

---

### Task 10: Flutter — trang đánh giá Holland

**Files:**
- Create: `lib/features/game/presentation/holland_report_page.dart`
- Modify: `lib/features/game/presentation/payment_page.dart`

**Interfaces:**
- Consumes: `PaymentState.hollandCode` + điểm 6 nhóm.
- Produces: `HollandReportPage(Map<String,int> scores, String hollandCode)`.

- [ ] **Step 1: Viết test fail**

```dart
testWidgets('bieu do hien du 6 nhom va ma Holland', ...)
```

- [ ] **Step 2: Chạy để thây fail** → FAIL.

- [ ] **Step 3: Tạo trang**

Dùng `tokens.dart` sẵn có (`Gap`, `Rad`, `AppColors`, `TextStyle` chuẩn của dự án). Vẽ 6 thanh ngang tương đối theo điểm cao nhất, tô đậm nhóm cao nhất, in mã Holland 3 chữ cái.

- [ ] **Step 4: Nối từ `PaymentPage`** khi nhận `PAID`.

- [ ] **Step 5: Chạy test** → PASS.

- [ ] **Step 6: Commit**

---

### Task 11: Flutter — nút mua + điều kiện thắng + lịch sử

**Files:**
- Modify: `lib/features/game/presentation/game_page.dart`
- Create: `lib/features/game/presentation/payment_history_page.dart`
- Modify: `lib/features/welcome/welcome_page.dart`

**Interfaces:**
- Consumes: `PaymentApi.createOrder`.
- Produces: `GET /api/game/check-winner` được gọi từ client khi vào màn kết quả.

- [ ] **Step 1: Viết test fail**

```dart
testWidgets('nguoi du dieu kien thay nut mua bao cao', ...)
testWidgets('nguoi chua du dieu kien thay thong bao cu', ...)
testWidgets('thong bao giu nguyen chu', ...)   // so khớp đúng chuỗi trong Global Constraints
```

- [ ] **Step 2: Chạy để thây fail** → FAIL.

- [ ] **Step 3: Gọi `check-winner` khi vào màn kết quả**

Trong `_showResultScreen`, gọi endpoint thay vì tự suy `isWinner` từ `winnerKey`.

- [ ] **Step 4: Rẽ nhánh UI**

Đủ → nút "Nhận báo cáo (50.000đ)", bấm gọi `createOrder`, lưu `orderId` vào `SessionStore`, chuyển hẹn `payUrl`.
Chưa đủ → `Padding` với **đúng chuỗi** ở Global Constraints, không sửa.

- [ ] **Step 5: Tạo trang lịch sử + mục trên màn hình chính**

Liệt kê `PAID` (nút xem lại) và `PENDING` còn hạn (đếm ngược + nút "Thanh toán tiếp").

- [ ] **Step 6: Chạy test** → PASS.

- [ ] **Step 7: Commit**

---

### Task 12: Gỡ VietQR + sửa test cũ

**Files:**
- Delete: `lib/features/game/data/vietqr_service.dart`
- Modify: `lib/core/config/api_config.dart`
- Modify: `pubspec.yaml`
- Modify: `test/roll_button_and_payment_test.dart:92-101`

**Interfaces:**
- Produces: không còn tham chiếu `VietQrService`, `vietQr*`, `qr_flutter` trong mã nguồn.

- [ ] **Step 1: Chạy test cũ để thấy fail**

Run: `flutter test test/roll_button_and_payment_test.dart`
Expected: FAIL ở assert `'Quét QR để xem kết quả chi tiết'` và `'Thanh toán: 50.000 VND'`.

- [ ] **Step 2: Viết lại test theo luồng mới**

Thay hai assert QR bằng: đủ điều kiện → thấy nút mua báo cáo; chưa đủ → thấy đúng câu thông báo.

- [ ] **Step 3: Xoá `vietqr_service.dart`**

- [ ] **Step 4: Gỡ khối VietQR khỏi `api_config.dart`**

Xoá `vietQrBaseUrl`, `vietQrClientId`, `vietQrApiKey`, `vietQrAccountNo`, `vietQrAccountName`, `vietQrAcqId`. Giữ nguyên phần `baseUrl`/`socketUrl`.

- [ ] **Step 5: Gỡ `qr_flutter` khỏi `pubspec.yaml`** nếu grep xác nhận không còn chỗ nào dùng.

- [ ] **Step 6: Chạy toàn bộ test**

Run: `flutter analyze && flutter test`
Expected: analyze sạch, test pass (trừ test mạng cần server).

- [ ] **Step 7: Commit**

```bash
git add -A lib/ test/ pubspec.yaml
git commit -m "refactor: bo VietQR, dung luong VNPay"
```