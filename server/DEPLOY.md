# Deploy backend `career-map-server` lên Render

Tài liệu này nằm trong `server/` vì Render **chỉ đọc `render.yaml` ở GỐC repo**,
mà file `render.yaml` của dự án nằm trong `server/`. ⇒ **KHÔNG deploy bằng
Blueprint** (Render sẽ không thấy file `render.yaml` ở gốc repo). Phải tạo
**Web Service thủ công** và trỏ Dockerfile trong `server/`.

---

## 1. Tạo Web Service thủ công (không dùng Blueprint)

Trong Render Dashboard:

1. **New + Web Service** → Connect repo GitHub → chọn branch `main`.
2. **Runtime / Build Method**: `Docker`.
   - Dockerfile path: `./server/Dockerfile` (đường dẫn TƯƠNG ĐỐI so với gốc repo).
   - Docker context: `./server` - `server/Dockerfile` copy `.mvn`, `mvnw`,
     `pom.xml`, `src` vào context BẮT BUỘC là thư mục `server/`.
3. **Instance Type**: `Free`.
4. **Region**: `Singapore` (gần Việt Nam nhất trong danh sách Render).
5. **Health Check Path**: `/health`.

## 2. Biến môi trường BẮT BUỘC

### 2.1. Database (Neon)

| Key | Giá trị | Ghi chú |
|---|---|---|
| `PGHOST` | `<project>-pooler.<region>.aws.neon.tech` | Dùng **pooler** cho Neon free plan |
| `PGPORT` | `5432` | |
| `PGDATABASE` | tên DB trên Neon | |
| `PGUSER` | user DB | |
| `PGPASSWORD` | mật khẩu DB | |
| `PGSSLMODE` | `require` | Neon bắt buộc SSL |

### 2.2. VNPay - 5 biến BẮT BUỘC để bán được

| Key | Ví dụ | Bắt buộc? | Vì sao |
|---|---|---|---|
| `VNPAY_ENABLED` | `true` | **CO** | `false` → client ẩn nút "Nhận báo cáo", không ai mua được |
| `VNPAY_TMN_CODE` | mã merchant của VNPay | **CO** | `vnp_TmnCode` trong payUrl |
| `VNPAY_HASH_SECRET` | khoá bí mật của VNPay | **CO** | khoá HMAC ký đơn - KHÔNG hardcode, KHÔNG commit |
| `VNPAY_RETURN_URL` | `https://career-map-2026-b08f1.web.app/ket-qua-thanh-toan` | **CO** | nơi VNPay đưa người chơi về sau khi trả |
| `VNPAY_IPN_URL` | `https://<server>.onrender.com/api/payments/ipn` | **CO** | nơi VNPay báo kết quả server-to-server — **phải ĐĂNG KÝ ở terminal VNPay**, xem khối bên dưới |

> ### ⚠️ PHẢI ĐĂNG KÝ IPN URL Ở TERMINAL VNPAY (không phải chỉ đặt biến)
>
> Ở API 2.1.0, **IPN URL là cấu hình ở CẤP TERMINAL/MERCHANT**, KHÔNG truyền
> theo từng giao dịch. Nếu terminal chưa được đăng ký IPN URL thì **VNPay
> không gọi IPN về server** ⇒ đơn treo PENDING ⇒ hết 15 phút thành EXPIRED
> **dù người chơi đã trả 50.000đ**.
>
> - **Sandbox**: đăng ký tại
>   `https://sandbox.vnpayment.vn/merchantv2/Account/TerminalEdit.htm`
>   với giá trị `https://career-map-server.onrender.com/api/payments/ipn`.
> - **Production**: phải **LIÊN HỆ VNPay** để đăng ký (tự đăng ký ở sandbox
>   không có hiệu lực ở production).
> - Giá trị phải **KHÔNG dấu tiếng Việt, KHÔNG khoảng trắng** — giống
>   `VNPAY_RETURN_URL`.
> - `vnp_IpnUrl` **vẫn được gửi trong payUrl** (gateway cần nó để gọi IPN) —
>   nhưng ở API 2.1.0 tham số này **KHÔNG được băm vào chữ ký** (cả ta lẫn
>   gateway đều loại nó ⇒ chữ ký vẫn khớp, xem §2.3) — và **không thay thế**
>   cho việc đăng ký ở terminal.

> **`VNPAY_RETURN_URL` KHÔNG kèm query string.** VNPay sẽ append
> `vnp_Amount`, `vnp_TxnRef`, `vnp_SecureHash` vào URL này. App đã bóc phần
> `path` (bỏ query) nên vẫn tới đúng trang "Kết quả thanh toán".
>
> **URL KHÔNG được có dấu tiếng Việt.** Server tự bỏ dấu + bỏ khoảng trắng cho
> `vnp_ReturnUrl`/`vnp_IpnUrl` trước khi gửi, nhưng nên đặt URL sạch ngay từ
> đầu để không phải phụ thuộc hành vi đó.

### 2.3. VNPay - chọn BỘ GIAO THỨC (biến môi trường, KHÔNG cần build lại)

VNPay có HAI bộ giao thức khác nhau. Chúng KHÔNG chỉ khác ở URL:

| | API 2.1.0 (mặc định) | API CŨ (đã bị thu hồi) |
|---|---|---|
| URL gateway | `https://sandbox.vnpayment.vn/paymentv2/vpcpay.html` | `https://sandbox.vnpayment.vn/paymentgateway/paymenturl.htm` |
| `vnp_Command` | `pay` | `pay_web` |
| `vnp_Version` | **bắt buộc** `2.1.0` | không gửi |
| `vnp_OrderType` | **bắt buộc** (mặc định `other`) | không gửi |
| `vnp_SecureHashType` | **KHÔNG được gửi** (tài liệu VNPay) | được gửi (2.0.x) |
| Thuật toán chữ ký | HMAC-SHA512 | HMAC-SHA256 |
| `vnp_TransactionStatus` báo THÀNH CÔNG | `00` | `01` |
| `vnp_IpAddr` | **bắt buộc** (thiếu ⇒ gateway từ chối) | tuỳ chọn |
| `vnp_IpnUrl` trong chuỗi băm | **KHÔNG băm** (nhưng VẪN gửi đi) | CÓ băm |

> **CÁCH TÍNH CHỮ KÝ (API 2.1.0)** — chuỗi băm = các tham số `vnp_*` **CÓ
> GIÁ TRỊ**, sắp xếp alphabet, **URL-encode CẢ key lẫn value**, nối
> `key=value` bằng `&`, HMAC-SHA512 bằng `VNPAY_HASH_SECRET` (128 hex).
>
> **BA tham số bị LOẠI khỏi chuỗi băm:**
>
> 1. `vnp_SecureHash` — chính là chữ ký (luôn loại).
> 2. `vnp_SecureHashType` — API 2.1.0 không được gửi tham số này.
> 3. `vnp_IpnUrl` — **chỉ ở API 2.1.0**. Gateway 2.1.0 **KHÔNG băm** tham số
>    này (đã kiểm chứng thực nghiệm trên sandbox: dò 31 biến thể `payUrl`,
>    biến thể "gửi `vnp_IpnUrl` nhưng KHÔNG băm" là biến thể **DUY NHẤT**
>    được gateway chấp nhận). Ở giao thức cũ 2.0.x tham số này **CÓ** thuộc
>    tập băm.
>
> - `vnp_IpnUrl` **VẪN được gửi đi** trong payUrl (gateway cần nó để gọi IPN)
>   ⇒ ở API 2.1.0: **chuỗi băm = chuỗi query BỎ cặp `vnp_IpnUrl=...` đi**.
> - Băm kèm `vnp_IpnUrl` ở API 2.1.0 ⇒ gateway trả **"Sai chữ ký"** ⇒ **mọi
>   đơn bị từ chối**.
> - Bỏ hẳn `vnp_IpnUrl` khỏi query ⇒ mất IPN ⇒ đơn treo PENDING ⇒ hết 15
>   phút thành EXPIRED **dù người chơi đã trả 50.000đ**.

> **API CŨ ĐÃ CHẾT.** `paymentgateway/paymenturl.htm` trả HTTP 404 (VNPay đã
> thu hồi) ⇒ mặc định của server đã chuyển sang API 2.1.0. Muốn quay lại
> API cũ thì phải đặt ĐỦ bộ biến cũ (xem bảng bên dưới).

| Key | Mặc định | Khi nào đổi |
|---|---|---|
| `VNPAY_BASE_URL` | `https://sandbox.vnpayment.vn/paymentv2/vpcpay.html` | URL gateway (sandbox ⇄ production) |
| `VNPAY_COMMAND` | `pay` | `pay_web` khi dùng API cũ |
| `VNPAY_VERSION` | `2.1.0` | *(rỗng = không gửi)* khi dùng API cũ |
| `VNPAY_ORDER_TYPE` | `other` | *(rỗng = không gửi)* khi dùng API cũ |
| `VNPAY_HASH_ALGORITHM` | *(rỗng = tự chọn theo phiên bản: 2.1.0 ⇒ SHA512, API cũ ⇒ SHA256)* | chỉ đặt khi cần ghim thuật toán |
| `VNPAY_HASH_TYPE` | *(rỗng = không gửi)* | `SHA256`/`SHA512` cho API 2.0.x. **Để rỗng khi dùng API 2.1.0** |

Chấp nhận cả `SHA512` và `HmacSHA512` cho `VNPAY_HASH_ALGORITHM`.

> **Mặc định mới ĐÃ ĐÚNG — KHÔNG cần đặt thêm biến.** Server tự chọn
> HMAC-SHA512 khi đang ở API 2.1.0, tự bỏ `vnp_SecureHashType` và tự bỏ
> `vnp_IpnUrl` khỏi **chuỗi băm** (nhưng vẫn gửi `vnp_IpnUrl` đi trong
> payUrl). Nhưng nếu
> Render đang CÓ sẵn biến cũ (`VNPAY_BASE_URL` trỏ `paymentgateway/...`,
> `VNPAY_COMMAND=pay_web`, `VNPAY_HASH_ALGORITHM=HmacSHA256`) thì phải XOÁ
> chúng, nếu không server vẫn dùng bộ tham số cũ ⇒ 404 / sai chữ ký.
>
> Chuyển sang API 2.1.0 = đổi CẢ BỘ biến, KHÔNG phải đổi 1 biến.
> Thiếu `vnp_Version`/`vnp_OrderType` ⇒ gateway từ chối đơn. Gửi thừa
> `vnp_SecureHashType` ở 2.1.0 ⇒ lệch bộ tham số mà gateway dùng để tính
> hash ⇒ đơn bị từ chối. Server đã tự chặn 2 trường hợp sai này (thiếu
> version/order-type thì báo thiếu biến; 2.1.0 thì không gửi `vnp_SecureHashType`),
> nhưng vẫn phải đặt đủ bộ biến ở Render.

> **CẦN QUAY LẠI API CŨ (`pay_web`)? PHẢI đặt ĐỦ cả 4 biến sau** (giá trị
> rỗng là RỖNG THẬT — KHÔNG phải "không đặt"):
>
> ```
> VNPAY_COMMAND=pay_web
> VNPAY_VERSION=
> VNPAY_ORDER_TYPE=
> VNPAY_HASH_ALGORITHM=HmacSHA256
> ```
>
> - `VNPAY_VERSION=` + `VNPAY_ORDER_TYPE=` phải để **RỖNG**: server chỉ tự điền
>   mặc định `2.1.0` / `other` khi đang ở API 2.1.0.
> - `VNPAY_HASH_ALGORITHM=HmacSHA256` ghim TAY (không để trống) để chắc chắn
>   ký đúng thuật toán của API cũ.
> - **KHÔNG được CHỈ set `VNPAY_COMMAND=pay_web`**: `vnpay.version` mặc định là
>   `2.1.0` ⇒ `isApi21()` vẫn `true` (nó nhận ra 2.1.0 qua `vnp_Version`) ⇒
>   server vẫn ký SHA512 và vẫn gửi `vnp_Version`/`vnp_OrderType` ⇒ gateway
>   từ chối đơn.
> - Ngoài ra phải trỏ `VNPAY_BASE_URL` về endpoint cũ
>   (`https://sandbox.vnpayment.vn/paymentgateway/paymenturl.htm`) — nhưng
>   endpoint đó **đã 404** ⇒ đường quay lại API cũ chỉ là **QUY ƯỚC**, KHÔNG
>   phải đường dự phòng thực sự: bộ tham số 2.0.x (hash **thô** + gửi
>   `vnp_SecureHashType`) **không được server hỗ trợ** nữa ⇒ kể cả endpoint
>   còn sống thì chữ ký cũng lệch ⇒ không dùng được để rollback.

### 2.4. CORS (tuỳ chọn)

| Key | Mặc định |
|---|---|
| `CORS_ALLOWED_ORIGINS` | `http://localhost:*,http://127.0.0.1:*,https://career-map-2026-b08f1.web.app,https://career-map-2026-b08f1.firebaseapp.com` |

### 2.5. Công cụ cộng điểm để test ván online

`GAME_DEBUG_TOOLS_ENABLED` mặc định `false`. Chỉ đặt `true` khi chạy backend local hoặc staging để hiện menu cộng điểm và thử màn kết quả; **không bật ở production**. Cờ này cần khởi động lại backend và client đọc lại `/api/game/config`.

## 3. Trước khi bật `VNPAY_ENABLED=true`

1. **Phải chạy 1 giao dịch THẬT trên sandbox** để xác nhận gateway chấp nhận
   chữ ký. Nếu gateway báo lỗi chữ ký:
   - kiểm tra `vnp_SecureHash` trong payUrl có đúng 128 hex (API 2.1.0) hay
     64 hex (API cũ) không;
   - kiểm tra `VNPAY_HASH_ALGORITHM` có khớp bộ giao thức không (để trống ⇒ tự
     chọn theo phiên bản: 2.1.0 ⇒ SHA512);
   - **đừng đổi lệch một biến**: xem bảng ở §2.3.
2. **IPN URL đã được ĐĂNG KÝ ở terminal VNPay chưa?** (xem khối cảnh báo ở
   §2.2 — sandbox: `https://sandbox.vnpayment.vn/merchantv2/Account/TerminalEdit.htm`,
   production: phải liên hệ VNPay; giá trị đăng ký:
   `https://career-map-server.onrender.com/api/payments/ipn`).
   Ở API 2.1.0, IPN URL là cấu hình ở CẤP TERMINAL/MERCHANT, KHÔNG truyền
   theo từng giao dịch ⇒ terminal chưa đăng ký thì **VNPay không gọi IPN về
   server** ⇒ đơn treo PENDING ⇒ hết 15 phút thành EXPIRED **dù người chơi đã
   trả 50.000đ**.
   - Phân biệt "IPN tới nơi khác" với "IPN không tới": payUrl **có**
     `vnp_IpnUrl=` (xem §2.2) ⇒ nếu IPN có tới mà log KHÔNG có dòng IPN thì
     kiểm tra terminal đã đăng ký đúng URL chưa; ngược lại, payUrl **không**
     có `vnp_IpnUrl` là dấu hiệu server đang cấu hình sai.
3. Kiểm tra `vnp_ReturnUrl` trong payUrl (mở payUrl, xem query string) có trỏ
   về `/ket-qua-thanh-toan` không.
4. Kiểm tra payUrl có ĐỦ tham số bắt buộc của API 2.1.0 không:
   `vnp_Version=2.1.0`, `vnp_Command=pay`, `vnp_OrderType=other`, `vnp_IpAddr`
   (thiếu `vnp_IpAddr` là gateway trả "Dữ liệu gửi sang không đúng định dạng"),
   `vnp_TmnCode`, `vnp_TxnRef`, `vnp_Amount`, `vnp_CurrCode`, `vnp_CreateDate`,
   `vnp_OrderInfo`, `vnp_ReturnUrl`, `vnp_Locale`, `vnp_ExpireDate` (tài liệu
   chính thức ghi `vnp_ExpireDate` là **BẮT BUỘC** cho `vnp_Command=pay`; code
   ta có gửi ✓), `vnp_IpnUrl` (vẫn phải CÓ trong payUrl — xem §2.2) — và KHÔNG
   có `vnp_SecureHashType`.
5. Kiểm tra log Render có dòng `[FINISH] phong=...` khi kết thúc ván, và có log
   IPN (`IPN xử lý thất bại...`) khi VNPay gọi.
6. Nếu đơn **không** chuyển PAID sau khi thanh toán: tìm trong log dòng
   `IPN VNPay SAI CHỮ KÝ (RspCode=97)` — dòng đó in ra **toàn bộ tham số IPN
   nhận được** (giá trị chữ ký được che `***`) để dựng lại chuỗi băm và so với
   chuỗi server đã gửi trong payUrl. Lưu ý: theo tài liệu VNPay, `RspCode=97`
   ⇒ VNPay **CÓ retry** (tối đa 10 lần, cách nhau 5 phút — cùng nhóm với
   01/04/99) ⇒ nếu nguyên nhân lệch chữ ký được sửa, một lần retry sau có thể
   thành công; nếu không sửa được, đơn vẫn PENDING ⇒ hết 15 phút thành EXPIRED
   dù tiền đã vào ⇒ phải xử lý tay.

   **Cách dựng lại chuỗi băm từ dòng log đó** (đúng thứ tự
   `VnpaySignatureService` dùng khi băm):

   1. Lấy các tham số in trong log, **bỏ `vnp_SecureHash`,
      `vnp_SecureHashType` và `vnp_IpnUrl`** — đây là **3 tham số KHÔNG
      được băm** (xem §2.3). IPN thường không có `vnp_IpnUrl` nhưng nếu có
      thì vẫn phải bỏ (API 2.1.0).
   2. **BỎ QUA tham số có giá trị RỖNG.** Log in ra dạng `key=` nhưng tham số
      rỗng **KHÔNG** được tính vào chuỗi băm. (Nói cách khác: chữ ký tính trên
      các tham số **CÓ GIÁ TRỊ**, không phải "đúng mọi tham số in ra".)
   3. Sắp xếp key theo alphabet (tăng dần).
   4. **URL-ENCODE LẠI cả key và value** (UTF-8). Log in giá trị **đã giải mã**
      (Spring decode `@RequestParam`), còn chuỗi băm dùng bản **đã encode**
      (ví dụ `Bao cao Holland` → `Bao+cao+Holland`, `&` → `%26`,
      `+` → `%2B`). Bỏ bước này thì chữ ký dựng ra LUÔN lệch ⇒ tưởng gateway
      sai chữ ký trong khi thực ra là do cách dựng.
   5. Nối `key=value` bằng `&`, rồi HMAC bằng `VNPAY_HASH_SECRET` theo
      `vnpay.hash-algorithm` (2.1.0 ⇒ HmacSHA512 ⇒ 128 hex; API cũ ⇒
      HmacSHA256 ⇒ 64 hex) và so với **chuỗi băm server đã gửi trong `payUrl`**
      (`vnp_SecureHash` trong payUrl) — **KHÔNG lấy `vnp_SecureHash` từ dòng
      log**: giá trị đó bị che `***` nên không lấy được từ log (đó là lý do
      bước này so với chuỗi trong `payUrl`, khớp với câu ở đầu mục 6).
   6. **Giá trị dài hơn 120 ký tự bị CẮT trong log** (thấy dấu
      `...(cắt bớt N ký tự)`). Tham số dài (vd `vnp_ReturnUrl`, `vnp_QRCode`)
      ⇒ giá trị trong log **đã bị cắt** ⇒ **không dựng lại được** chuỗi băm
      cho tham số đó ⇒ chữ ký dựng ra LỆCH ⇒ tưởng gateway sai chữ ký. Khi
      gặp trường hợp này, lấy giá trị **đúng** (không bị cắt) từ `payUrl` (hoặc
      từ gateway) rồi dựng lại chuỗi băm.
7. Kiểm tra **IPN có được trả `RspCode` hợp lệ**: gateway VNPay quyết định retry
   dựa trên `RspCode` TRONG BODY (không phải HTTP status):
   - `00`, `02` ⇒ kết thúc luồng;
   - `01`, `04`, `97`, `99` hoặc timeout ⇒ VNPay retry tối đa 10 lần, cách nhau
     5 phút.

## 4. Monitor UptimeRobot (bắt buộc với Render free)

Render free **ngủ** sau ~15 phút không có traffic ⇒ IPN tới lúc server đang ngủ
sẽ fail ⇒ đơn kẹt PENDING ⇒ người chơi mất tiền.

- Tạo monitor: `GET https://<server>.onrender.com/health`, khoảng 5 phút/lần.
- Kiểm tra server thật sự thức: `curl -i https://<server>.onrender.com/health`.

## 5. Lệnh kiểm tra sau khi deploy

```bash
curl -i https://<server>.onrender.com/health
curl -i https://<server>.onrender.com/api/game/config
curl -i -X POST https://<server>.onrender.com/api/payments/ipn \
     -H 'Content-Type: application/x-www-form-urlencoded' \
     --data 'vnp_TxnRef=khong-ton-tai&vnp_TransactionStatus=00&vnp_Amount=5000000&vnp_SecureHash=sai'
# API 2.1.0 gọi IPN bằng GET:
curl -i 'https://<server>.onrender.com/api/payments/ipn?vnp_TxnRef=khong-ton-tai&vnp_TransactionStatus=00&vnp_Amount=5000000&vnp_SecureHash=sai'
```

Lệnh IPN cuối phải trả **HTTP 200** với `{"RspCode":"97",...}` (chữ ký sai) -
KHÔNG được 415, KHÔNG được 400, KHÔNG được 405.

## 6. Lưu ý về database

- `spring.jpa.hibernate.ddl-auto=update` ⇒ schema tự cập nhật, **không có
  migration**. Bảng `room_riasec_snapshot` (bản cũ, chụp điểm theo phòng) đã
  bị thay bằng `room_player_riasec_snapshot` (chụp điểm **theo từng người
  chơi**). Bảng cũ còn nằm trong DB nhưng không được dùng - có thể xoá
  tay: `DROP TABLE room_riasec_snapshot;`
- Cột `payment_orders.room_code` nay cho phép NULL (đơn tạo sau khi phòng đã
  bị dọn vẫn ghi được thay vì lỗi 500).

## 7. Chuyển khoản SePay (thay cho VNPay)

SePay nhận tiền vào tài khoản ngân hàng bạn đã liên kết. Trên Render, tạo các biến môi trường sau (không đưa webhook secret vào mã nguồn):

| Biến | Giá trị |
| --- | --- |
| `PAYMENT_PROVIDER` | `SEPAY` |
| `PAYMENT_ENABLED` | `true` |
| `SEPAY_BANK` | Mã ngân hàng mà SePay/VietQR yêu cầu (ví dụ `MB`, `VCB`) |
| `SEPAY_ACCOUNT_NUMBER` | Số tài khoản ngân hàng đã liên kết |
| `SEPAY_ACCOUNT_HOLDER` | Tên chủ tài khoản |
| `SEPAY_PAYMENT_CODE_PREFIX` | `CM` (phải trùng cấu hình mã thanh toán tại SePay) |
| `SEPAY_WEBHOOK_SECRET` | Secret HMAC-SHA256 riêng cho webhook SePay |
| `SEPAY_MEMO_PREFIX` | Để trống; chỉ đặt nếu ngân hàng của bạn bắt buộc tiền tố |

Trong SePay, bật nhận diện mã thanh toán với tiền tố `CM`, hậu tố 8 ký tự chữ hoa/số. Tạo webhook loại **tiền vào** cho tài khoản đã liên kết, xác thực **HMAC-SHA256**, URL:

`https://<render-service>/api/payments/sepay/webhook`

App tạo QR VietQR với số tiền và nội dung riêng cho từng đơn; không chỉnh nội dung khi chuyển khoản. Khi callback có chữ ký hợp lệ, đúng mã đơn và đúng số tiền, server tự cập nhật đơn sang PAID. Giữ `PAYMENT_PROVIDER` ở `vnpay` để tiếp tục dùng VNPay.
