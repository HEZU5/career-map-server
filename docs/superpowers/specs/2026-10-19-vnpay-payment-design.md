# Thiết kế: Thanh toán VNPay + Đánh giá Holland

Ngày: 2026-10-19 · Trạng thái: chờ duyệt
Phạm vi: backend `server/` (Spring Boot) + frontend `lib/` (Flutter web)

> Bản này đã qua một vòng review độc lập. Các lỗi chí mạng đã sửa:
> HMAC **SHA256** (không phải SHA512), tra đơn bằng **`vnp_TxnRef`** (không phải `vnp_TxnNo`),
> dùng `/health` có sẵn (không tạo mới), chụp điểm lúc `finish()`.

---

## 1. Vì sao làm

VietQR chỉ sinh ảnh QR chứa số tài khoản — app không có đường nào biết tiền đã về.
Thêm nữa, khoá API của VietQR bị đặt nhầm làm *tên* của `dart-define`
(`api_config.dart:58-59`) nên luôn rỗng → mã QR **chưa bao giờ chạy được**.

Thay bằng VNPay sandbox: có IPN xác nhận tự động, kèm đếm ngược, lịch sử, đánh giá.

## 2. Quyết định đã chốt

| Việc | Chọn | Vì sao |
|---|---|---|
| Nhà cung cấp | VNPay **sandbox** | Có IPN thật, không cần hộ kinh doanh (dự án sinh viên) |
| Tên miền | Giữ `career-map-2026-b08f1.web.app` | Đủ HTTPS công khai. Tên riêng chỉ cần khi đi tiền thật |
| Số tiền | 50.000đ | Giữ nguyên con số hiện tại |
| Hạn thanh toán | 15 phút | Người dùng đã chốt |
| Ai được mua báo cáo | **Mỗi người tự đủ điều kiện** — mục 8 | Không giành chỗ thắng duy nhất |

## 3. Giữ ấm backend

Render free ngủ sau ~15 phút không có request, khởi động lại mất 30–60 giây.

**Đã có sẵn, không viết thêm:**
- `GET /health` (`HealthController.java`) — Render đã dùng làm `healthCheckPath`.
- App đã có `_ensureServerAwake()` (`game_api.dart:85`), chờ 75s, cache 5 phút.

**Rủi ro còn lại:** không ai mở web thì service vẫn ngủ. VNPay bắn IPN đúng lúc đó →
không đợi được → ghi đơn là *chưa thanh toán*.

**Cách làm:** UptimeRobot (miễn phí) gọi `GET /health` mễ5 phút. Pinger để **bên ngoài** —
app vắng khách đúng lúc cần giữ ấm.

## 4. Luật thắng — phải sửa

Hiện **không có luật thắng**: client tự gửi `winnerKey`, server ghi thẳng
(`GameService.java:183-184`), và `finish()` không kiểm tra quyền host (đổi lại
`RoomService.closeRoom` có — `RoomService.java:346-353`). Điều này gắn tiền với kết
quả, nên phải sửa.

**Sửa:**
- Server tự tính người thắng, không nhận từ client. "Thắng" = **đạt điều kiện trước** (mục 8).
- Chặn người không phải host gọi `finish`. Kiểm tra phải đặt **trước** nhánh
  `if (room.isFinished()) return ...` (`GameService.java:178`), nếu không người còn lại
  vẫn gọi được.
- `POST /api/game/check-winner` **giữ lại và nối vào** — đó chính là điều kiện ở mục 8.

**Quy tắc xếp hạng: chốt ở mục 8** (còn đang mở).

### 4b. Sửa nút "Kết thúc sớm" — phải làm CÙNG đợt

Hiện có một nút không dùng được và một nút thiếu kiểm soát:
- Landscape (`game_page.dart:1427`) khoá theo `roomStatus == RoomStatus.playing`, mà
  `RoomStatus.PLAYING` **chưa bao giờ được gán** → nút không bao giờ hiện.
- Portrait (`game_page.dart:1543-1548`) chỉ khoá theo `game.online` → hiện cho tất cả,
  không khoá host.

Nếu bật kiểm tra host ở server mà chưa sửa nút → người không phải host bấm được, nhận
403, còn host thì không có nút để bấm → **phòng không kết thúc được → không ai thắng →
không ai thanh toán**. Sửa cả hai cùng lúc với mục 4.

`GameController.amHost` đã có sẵn (`game_controller.dart:343`) nhưng chưa dùng ở đâu.

## 5. Backend — bảng đơn hàng

Entity `PaymentOrder`, bảng `payment_orders`. **Không có migration**: `pom.xml` không
có Flyway/Liquibase, `application.properties:30` để `ddl-auto=update` → bảng tự sinh.

Cột, đặt kiểu theo đúng quy ước các entity sẵn có (`@Enumerated(EnumType.STRING)` +
`length`, xem `Room.java:91-93`):

| Cột | Kiểu | Ghi chú |
|---|---|---|
| `id` | String | = **`vnp_TxnRef`**, ta sinh, duy nhất |
| `playerKey` | String | ai đặt đơn |
| `roomCode` | String | phòng ván đó |
| `amount` | long | 50.000, server tự đặt |
| `status` | enum | `PENDING` / `PAID` / `EXPIRED` |
| `riasecJson` | text | chụp 6 điểm RIASEC lúc `finish()` |
| `createdAt` / `expiresAt` / `paidAt` | Instant | hạn = `createdAt + 15 phút` |

## 6. Backend — API

**`POST /api/payments/create`** — vào `{ playerKey }`, ra `{ orderId, payUrl, expiresAt }`.
- Kiểm tra người gọi **là người thắng** của phòng hiện tại (`room.getWinnerKey()`),
  nếu không thì 403.
- Đơn `PENDING` chưa hết hạn của cùng người thì trả lại chính đơn đó.

**`GET /api/payments/{id}?playerKey=`** — ra `{ status, amount, expiresAt, hollandCode }`.
Đọc tới đâu thì quy đổi `PENDING` → `EXPIRED` luôn nếu quá hạn (**lazy**, không dùng
`@Scheduled`: Render free ngủ, lịch không chạy đều).

**`POST /api/payments/ipn`** ← URL VNPay gọi
- Verify `vnp_SecureHash`.
- **Luôn trả HTTP 200** và bắt mọi lỗi trong thân hàm. `ConflictAdvice`
  (`ConflictAdvice.java:16`) là `@RestControllerAdvice` — để exception nỏ ra thì
  VNPay cứ retry mãi.
- IPN tới sau `EXPIRED` vẫn nhận `PAID` (tiền đã vào). Không bao giờ trả 4xx.

**`GET /api/payments/history?playerKey=`** — đơn mới nhất trước, tối đa 100.

### Chữ ký VNPay — làm đúng hoặc hỏng

- Thuật toán: **HMAC-SHA256** với `HashSecret`.
- Ký lúc tạo đơn và xác minh IPN đều dùng chung cách: sắp xếp mọi key `vnp_*` theo
  alphabet (loại `vnp_SecureHash`, loại giá trị null/rỗng), nối `key=value` bằng `&`,
  HMAC-SHA256 rồi hex.
- **`vnp_TxnRef` là khoá do ta sinh và cũng là thứ IPN gửi lại.** Tìm đơn bằng nó.
  (`vnp_TxnNo` là số VNPAY tự gán — tra bằng cái này sẽ không tìm thấy đơn nào.)
- **Bắt buộc đối chiếu với tài liệu sandbox trước khi code.** Sai thuật toán thì
  không đơn nào bao giờ sang `PAID`.

### Cấu hình

Biến môi trường, **không hardcode**: `VNPAY_ENABLED`, `VNPAY_TMN_CODE`,
`VNPAY_HASH_SECRET`, `VNPAY_RETURN_URL`, `VNPAY_IPN_URL`. Sandbox dùng
`https://sandbox.vnpayment.vn`.

Đưa `payment.amount` và `payment.ttlMinutes` vào `GameRulesConfig` (theo đúng cách
`GameRulesConfig.java:46-55` đang làm cho luật chơi) và cho client đọc qua
`GET /api/game/config` — nếu không đồng hồ đếm ngược sẽ lệch với server.

Chia theo convention sẵn có: `domain/` + `repo/` + `service/` + `web/` (+ `web/dto/`).

## 7. Luồng phía người chơi

```
Ván kết thúc → server tự tính người thắng, chụp điểm RIASEC
   ↓
Thấy nút mua báo cáo (50.000đ)
   ↓
POST /api/payments/create → lưu orderId vào SessionStore → chuyển hẹn sang VNPay
   ↓
Trả xong → vnpay.vn quay lại /ket-qua-thanh-toan
   ↓
Trang này lấy orderId từ SessionStore, poll GET /api/payments/{id} mỗi 2 giây
   ↓
PAID    → hiện trang đánh giá
PENDING → đếm ngược (15:00)
hết giờ → EXPIRED, tự văng ra màn hình chính
```

**Hai việc phải làm thêm phía Flutter, nếu quên thì trang quay lại sẽ không chạy:**

1. **Chưa có router.** `app.dart:44` là `MaterialApp(home: WelcomePage(...))`, không có
   `routes`/`onGenerateRoute`, và không chỗ nào đụng `Uri.base`. `firebase.json` có
   rewrite `** → /index.html` nên URL sẽ ra shell rồi vào thẳng `WelcomePage`.
   Cần `usePathUrlStrategy()` trong `main.dart` + một route trong `app.dart`.
2. **`orderId` không tự vượt qua chuyển hướng.** Lưu vào `SessionStore` trước khi
   chuyển hẹn (theo đúng pattern `session_store.dart:57-93`; `playerKey` đã sống sót
   qua chuyển hẹng nhờ localStorage).

**Bấm "Về màn hình chính":** đơn `PENDING` vẫn nằm trong DB, hiện ở lịch sử kèm nút
"Thanh toán tiếp".

**Khi `VNPAY_ENABLED=false`** (chưa cấu hình): ẩn nút mua, hiện bảng điểm RIASEC miễn
phí. Không để người dùng bấm vào một trang chết.

## 8. Điều kiện thắng + Trang đánh giá Holland (đã chốt)

**Nguồn:** `riasecJson` chụp sẵn lúc `finish()`.

Phải chụp lúc đó vì phòng bị **xoá hẳn** sau 30 phút: `RoomJanitor.deleteFinished` xoá
`Room` → cascade xoá `Player` → cascade xoá `Score`. Điểm RIASEC và `winPoints` biến mất
hoàn toàn. Đây là ràng buộc thứ tự, không phải ghi chú.

**Cách tính:** cộng 6 nhóm RIASEC, nhóm cao nhất là mã Holland. Vẽ cả 6 nhóm dạng
thanh ngang để thấy tỉ lệ.

**Chưa làm:** mô tả tính cách nghề nghiệp chi tiết theo mã 3 chữ cái. Cần bảng nội
dung riêng, ngoài phạm vi lần này.

### Đã chốt: điều kiện chiến thắng (mỗi người độc lập)

**Mỗi người chơi tự đủ điều kiện thì mua được báo cáo riêng của mình.** Không giành
nhau một chỗ thắng duy nhất.

**Điều kiện (đã có sẵn trong code, chưa ai dùng):**
```
completedLaps >= 1 && totalAnswered >= 12     // GameService.java:221
```

Cơ sở của con số 12: bàn có **24 ô RIASEC = 4 câu mỗi vòng** (`board.dart:33`), nên 12
câu = 3 vòng = **mỗi nhóm RIASEC được hỏi 2 lần** — mức tối thiểu để báo cáo có ý
nghĩa. `totalAnswered` đáng tin vì được cộng ở *mọi* lần trả lời (`GameService.java:149`),
khác với `winPoints` chỉ cộng ở thẻ Cơ hởi/Thử thách và gần như luôn bằng 0 khi chơi
bình thường.

**Hệ quả:**

| Trạng thái | Người chơi thấy |
|---|---|
| Đủ điều kiện | Nút mua báo cáo 50.000đ → quay sang VNPay |
| Chưa đủ điều kiện | Câu thông báo **giữ nguyên chữ**, không sửa, không đổi hướng |

Câu thông báo cho người chưa đủ là:
> "Bạn đã chơi rất tốt — vẫn chưa đủ điều kiện chiến thắng. Cố gắng hơn ở những lượt sau nhé! 🌟"

**Người đầu tiên đạt đủ điều kiện** được ghi làm người thắng của phòng và công bố —
giữ đúng tinh thần "có luật thắng trong file". Người đạt điều kiện sau vẫn mua được
báo cáo của riêng mình.

**Cách hiện thực:** `POST /api/game/check-winner` **không xoá mà nối lại**. Nó đã làm
đúng việc này, chỉ là client chưa gọi bao giờ. Bỏ `WIN_ANSWERS` khỏi hằng số cục bộ và
chuyển ngưỡng vào `GameRulesConfig` để client đọc chung.

## 9. Lịch sử thanh toán

Thêm vào màn hình chính:
- `PAID`: ngày, số tiền, mã Holland, nút xem lại đánh giá.
- `PENDING` chưa hạn: đếm ngược, nút "Thanh toán tiếp".
- `EXPIRED`: ghi rõ đã hạn, không có nút.

Dọn đơn cũ: làm **lazy trong lúc đọc lịch sử**, không dùng `@Scheduled` (Render ngủ).

## 10. Bảo mật

- `HashSecret` **chỉ ở server**, tuyệt đối không vào Flutter.
- IPN phải verify chữ ký, không tin body.
- Server tự đặt `amount`, không nhận từ client.
- Server tự tính người thắng, không nhận `winnerKey`.
- Kiểm tra người gọi `create` là người thắng.
- **Hạn chế đã biết, chưa sửa:** `playerKey` là chuỗi đoán được
  (`host-H24680`), và server vẫn nhận `points`/`winPoints` từ client
  (`GameService.java:141-148`) — nghĩa là tự chỉnh điểm trong browser rồi mua báo cáo
  vẫn được. Sửa cần tài khoản người dùng, nằm ngoài phạm vi lần này. Chấp nhận cho
  sandbox.
- CORS: `/api/payments/**` đã nằm trong `/api/**` và origin Firebase đã được cho phép
  (`CorsConfig.java:36`, `application.properties:28`). **Không thêm wildcard.**

## 11. Gỡ VietQR

Xoá `lib/features/game/data/vietqr_service.dart`, phần config trong `api_config.dart`,
khối QR trong hộp thoại kết quả, bỏ `qr_flutter` nếu không dùng chỗ khác.

**Thứ tự:** làm *sau* mục 4, vì hộp thoại kết quả được sửa cả hai lần — sửa mục 4 trước
rồi mới gỡ, tránh khoảng thời gian người thắng không có cả QR lẫn nút thanh toán.

**Test sẽ hỏng:** `test/roll_button_and_payment_test.dart:92-101` đang assert
`'Quét QR để xem kết quả chi tiết'` và `'Thanh toán: 50.000 VND'` → phải viết lại.
Test cho host check mới: theo mẫu `server/src/test/java/vn/careermap/service/RoomLifecycleTest.java`.

## 12. Test phải sửa

- `test/roll_button_and_payment_test.dart:92-101` đang assert `'Quét QR để xem kết quả chi tiết'`
  và `'Thanh toán: 50.000 VND'` → phải viết lại theo luồng mới.
- Thêm test cho: host check ở `finish()` (mẫu `RoomLifecycleTest.java`), lazy expire đơn,
  IPN trả 200 kể cả khi sai chữ ký, IPN tới sau `EXPIRED` vẫn ra `PAID`.
- Thêm test điều kiện thắng: đúng 12 câu + 1 vòng thì đủ; 11 câu thì chưa.

## 13. Việc phải làm (phía người dùng, chạy song song)

- [ ] Đăng ký sandbox `https://sandbox.vnpayment.vn/devreg/` → lấy `TmnCode`, `HashSecret`
- [ ] Tạo monitor UptimeRobot: `GET https://career-map-server.onrender.com/health`, 5 phút
- [ ] Thêm biến môi trường VNPay vào Render

Không có 2 việc đầu thì backend dựng được nhưng không tạo được đơn thật.

## 14. Ngoài phạm vi lần này

- Tài khoản người dùng (đăng nhập / đăng ký)
- Lỗi session nhiều tab
- Hai lỗi card "hai người" / "tất cả người chơi" chưa hiện thực
  (`career_cards.dart:863,887`)