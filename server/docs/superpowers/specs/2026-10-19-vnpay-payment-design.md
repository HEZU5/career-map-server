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

**`GET` hoặc `POST /api/payments/ipn`** ← URL VNPay gọi (API 2.1.0 gọi bằng
`GET`, API cũ gọi bằng `POST` — nhận CẢ HAI).
- Verify `vnp_SecureHash`.
- Lỗi **nghiệp vụ** (chữ ký sai, đơn không tồn tại, giao dịch không thành công,
  đã PAID, sai số tiền) → trả HTTP **200** kèm `RspCode` tương ứng.
- Lỗi **hạ tầng** (DB lạnh, connection reset) → trả HTTP **503** + body
  `{"RspCode":"99","Message":"..."}` để VNPay retry.
  *Ruling mới (đảo ngược ràng buộc cũ "luôn trả HTTP 200"):* trả 200 khi lỗi
  hạ tầng nghĩa là VNPay **không** retry ⇒ đơn kẹt PENDING ⇒ hết 15 phút thành
  EXPIRED dù tiền ĐÃ vào. Xem `PaymentService.handleIpn` +
  `PaymentInfrastructureAdvice`.
- **VNPay quyết định retry dựa trên `RspCode` TRONG BODY**, không phải HTTP
  status: `00`/`02` ⇒ kết thúc luồng; `01`/`04`/`97`/`99`/timeout ⇒ retry tối
  đa 10 lần, cách nhau 5 phút. ⇒ mọi body lỗi hạ tầng PHẢI có `RspCode`.
- Giao dịch **thất bại** (status khác mã thành công) trả `RspCode=02`
  (mã KẾT THÚC luồng) chứ không phải `99`: retry 10 lần một giao dịch đã
  thất bại ở ngân hàng thì không bao giờ thành công.
- **Không** khai báo `consumes` ⇒ request sai Content-Type trả 200 thay vì 415
  (415 khiến VNPay retry mãi một request vốn dĩ không bao giờ thành công).
- IPN tới sau `EXPIRED` vẫn nhận `PAID` (tiền đã vào). Không bao giờ trả 4xx cho
  lỗi nghiệp vụ.

**`GET /api/payments/history?playerKey=`** — **CHỈ metadata** (orderId, status,
amount, createdAt, expiresAt), **KHÔNG** có `riasecJson` và **KHÔNG** có
`hollandCode`.
- Vì sao: `playerKey` đoán được (`host-<mã phòng>`) và mã phòng nằm trong danh
  sách phòng **công khai** ⇒ trả kèm nội dung báo cáo là lộ báo cáo của người
  khác (kể cả đơn CHƯA thanh toán). `hollandCode` (1 chữ cái) cũng đủ để đoán
  ra loại báo cáo của người khác ⇒ bỏ luôn (client không còn gọi endpoint này).
- Nội dung báo cáo chỉ đọc được qua `GET /api/payments/{id}?playerKey=` — đã có
  sẵn và **đã** kiểm tra chủ đơn (`findByIdAndPlayerKey`).
- **Client** tự quản lý danh sách `orderId` của chính thiết bị này
  (`SessionStore.myOrderIds`) và tra từng đơn bằng endpoint có kiểm tra chủ đơn
  ⇒ không cần endpoint lịch sử công khai để xem lại báo cáo đã trả.

### Chữ ký VNPay — làm đúng hoặc hỏng

- Thuật toán: **HMAC-SHA256** với `HashSecret` (API cũ). API 2.1.0 dùng
  **HMAC-SHA512** — đổi bằng biến môi trường, không sửa code.
- Ký lúc tạo đơn và xác minh IPN đều dùng chung cách: sắp xếp mọi key `vnp_*` theo
  alphabet (loại `vnp_SecureHash`, loại giá trị null/rỗng), **URL-encode CẢ key
  lẫn value** (`URLEncoder` UTF-8), nối `key=value` bằng `&`, HMAC rồi hex.
- **BẤT BIẾN CỐT LÕI: dạng dùng để HASH phải BẰNG dạng dùng trong QUERY.**
  Hash trên giá trị thô trong khi query gửi giá trị đã encode ⇒ gateway từ
  chối mọi đơn, mọi IPN bị trả `RspCode=97` ⇒ đơn kẹt `PENDING` ⇒ hết hạn
  `EXPIRED` dù tiền đã vào. Demo chính thức của VNPay (Java/PHP/C#) đều
  URL-encode cả hai vế cho cả `hashData` lẫn `query`.
- **Mọi giá trị gửi lên VNPay phải là tiếng Việt KHÔNG DẤU, không khoảng trắng.**
  `vnp_OrderInfo` cố định là ASCII (`"Bao cao Holland"`); URL có dấu sẽ hỏng.
- **`vnp_TxnRef` là khoá do ta sinh và cũng là thứ IPN gửi lại.** Tìm đơn bằng nó.
  (`vnp_TxnNo` là số VNPAY tự gán — tra bằng cái này sẽ không tìm thấy đơn nào.)
- **Bắt buộc đối chiếu với tài liệu sandbox trước khi code.** Sai thuật toán thì
  không đơn nào bao giờ sang `PAID`.

### Cấu hình

Biến môi trường, **không hardcode**: `VNPAY_ENABLED`, `VNPAY_TMN_CODE`,
`VNPAY_HASH_SECRET`, `VNPAY_RETURN_URL`, `VNPAY_IPN_URL`. Sandbox dùng
`https://sandbox.vnpayment.vn`.

Chọn BỘ GIAO THỨC VNPay (xem `server/DEPLOY.md` §2.3):

| Biến | API cũ (mặc định) | API 2.1.0 |
|---|---|---|
| `VNPAY_BASE_URL` | `.../paymentgateway/paymenturl.htm` | `.../paymentv2/vpcpay.html` |
| `VNPAY_COMMAND` | `pay_web` | `pay` |
| `VNPAY_VERSION` | *(rỗng)* | `2.1.0` (bắt buộc) |
| `VNPAY_ORDER_TYPE` | *(rỗng)* | ví dụ `other` (bắt buộc) |
| `VNPAY_HASH_ALGORITHM` | `HmacSHA256` | `SHA512` |
| `VNPAY_HASH_TYPE` | tuỳ chọn | **để rỗng** (2.1.0 không gửi `vnp_SecureHashType`) |

Mã `vnp_TransactionStatus` báo THÀNH CÔNG cũng khác nhau: API cũ `01`, API 2.1.0
`00` — nhầm là tiền đã vào mà đơn vẫn kẹt `PENDING`.

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

**Nguồn:** `riasecJson` chụp sẵn lúc `finish()` — **theo từng người chơi**
(`room_player_riasec_snapshot`: `playerKey → JSON 6 điểm`).

Phải chụp lúc đó vì phòng bị **xoá hẳn** sau 30 phút: `RoomJanitor.deleteFinished` xoá
`Room` → cascade xoá `Player` → cascade xoá `Score`. Điểm RIASEC và `winPoints` biến mất
hoàn toàn. Đây là ràng buộc thứ tự, không phải ghi chú.

Vì sao **theo từng người**: báo cáo Holland là của riêng người mua. Ảnh chụp cộng dồn
cả bàn thì mã Holland của một người bị tính từ điểm của người khác — sai hoàn toàn về
mặt nghiệp vụ. `PaymentService.createOrder` đọc đúng dòng của người mua
(`room.getRiasecSnapshotOf(playerKey)`).

**Hệ quả phải nói rõ với người chơi:** phòng bị dọn ⇒ mất ảnh chụp ⇒ **không** tạo
được báo cáo. Server trả 403 với câu tiếng Việt ("Ván đã kết thúc quá lâu — dữ liệu báo
cáo không còn") thay vì im lặng tạo báo cáo rỗng. Ván **chưa** kết thúc thì cũng không
bán (403 "Ván chưa kết thúc") — điểm lúc đó còn dở dang.

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

Cơ sở của con số 12: bàn có **24 ô RIASEC trên tổng 53 ô** (`board.dart:55`) ⇒
trung bình **~24 câu hỏi mỗi vòng**, và 6 nhóm RIASEC xen kẽ nhau nên 12 câu đã cho
**mỗi nhóm khoảng 2 câu** — mức tối thiểu để báo cáo có ý nghĩa.

Vì vậy **điều kiện gắn thực sự là `completedLaps >= 1`** (phải đi hết một vòng 53 ô,
tức khoảng 15 lượt tung với xúc xắc trung bình 3.5); `totalAnswered >= 12` thì đạt
sớm hơn nhiều — khoảng nửa vòng đầu. Cả hai ngưỡng đều chỉnh được bằng biến môi
trường `WIN_MIN_LAPS` / `WIN_MIN_ANSWERS` mà không cần build lại.

`totalAnswered` đáng tin vì được cộng ở *mọi* lần trả lời (`GameService.java:149`),
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

**Nguồn dữ liệu: danh sách `orderId` của chính thiết bị này** (client tự quản lý
trong `SessionStore.myOrderIds`), mỗi đơn tra bằng
`GET /api/payments/{id}?playerKey=` — endpoint này **kiểm tra chủ đơn**.
Không dùng `GET /api/payments/history` (công khai theo `playerKey` đoán được) để lấy
nội dung báo cáo.

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

**Trạng thái sau vòng sửa cuối (xem `server/DEPLOY.md` và báo cáo Task 11):**

Backend (`server/`, 147 test, 0 fail):

| Nhóm | File test | Phủ |
|---|---|---|
| HTTP thanh toán | `web/PaymentControllerTest.java` | IPN sai chữ ký → 200 + `RspCode=97`; đơn không tồn tại → 200 + `01`; IPN hợp lệ → 200 + `00` + đơn `PAID`; thiếu param → 200; **Content-Type sai → không 415**; lỗi hạ tầng → **5xx**; lịch sử **không** có `riasecJson`; đơn của người khác → 404 |
| HTTP kết thúc ván | `web/GameFinishControllerTest.java` | host → 200; khách → 403; `FinishRequest` **không** có field `winnerKey` |
| Nghiệp vụ đơn | `service/PaymentServiceTest.java` | ván chưa kết thúc → 403; phòng đã bị dọn → 403; không có ảnh chụp → 403; ảnh chụp **đúng của người mua**; 2 tab bấm cùng lúc → **chỉ 1 đơn PENDING**; không gửi `vnp_TxnExpireDate`; có `vnp_OrderInfo` + `vnp_IpAddr`; base-url/thuật toán đổi được bằng cấu hình |
| Chữ ký | `service/VnpaySignatureTest.java` | SHA256 → 64 hex; SHA512 → 128 hex; tên ngắn `SHA512` ≡ `HmacSHA512` |
| IPN | `service/PaymentIpnTest.java` | lỗi hạ tầng phải **ném ra** (không trả 200) |
| Ảnh chụp | `service/GameFinishTest.java` | ảnh chụp **per-player**, không cộng dồn cả bàn |

Frontend (`test/`, không commit):

| Nhóm | File test | Phủ |
|---|---|---|
| Deep-link VNPay | `payment_route_test.dart` | `defaultRouteNameTestValue` **có query string** → vẫn tới `PaymentPage` |
| Lịch sử | `payment_history_test.dart` | tra theo `orderId` của chính thiết bị, không theo `playerKey` |
| Phiên | `session_store_test.dart` | `myOrderIds`, không ghi đè đơn PENDING còn hạn |
| Kết thúc ván | `finish_request_test.dart` | client **không** gửi `winnerKey` |
| Màn kết quả | `game_result_offer_test.dart` | chưa đủ điều kiện thì **không** lộ mã Holland |

## 13. Việc phải làm (phía người dùng, chạy song song)

- [ ] Đăng ký sandbox `https://sandbox.vnpayment.vn/devreg/` → lấy `TmnCode`, `HashSecret`
- [ ] Tạo monitor UptimeRobot: `GET https://career-map-server.onrender.com/health`, 5 phút
- [ ] Thêm biến môi trường VNPay vào Render — **5 biến bắt buộc**:
      `VNPAY_ENABLED=true`, `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`,
      `VNPAY_RETURN_URL=https://career-map-2026-b08f1.web.app/ket-qua-thanh-toan`,
      `VNPAY_IPN_URL=https://<server>.onrender.com/api/payments/ipn`
- [ ] Deploy backend bằng **Web Service thủ công** (KHÔNG dùng Blueprint: `render.yaml`
      nằm trong `server/` mà Render chỉ đọc `render.yaml` ở gốc repo) — xem `server/DEPLOY.md`
- [ ] Chạy **1 giao dịch thật** trên sandbox để xác nhận chữ ký đúng. Nếu gateway yêu cầu
      SHA512: đổi `VNPAY_HASH_ALGORITHM=SHA512` + `VNPAY_BASE_URL=.../paymentv2/vpcpay.html`
      ⇒ **không** cần sửa code, không cần build lại

Không có 2 việc đầu thì backend dựng được nhưng không tạo được đơn thật.

## 14. Ngoài phạm vi lần này

- Tài khoản người dùng (đăng nhập / đăng ký)
- Lỗi session nhiều tab
- Hai lỗi card "hai người" / "tất cả người chơi" chưa hiện thực
  (`career_cards.dart:863,887`)