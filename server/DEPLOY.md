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
| `VNPAY_IPN_URL` | `https://<server>.onrender.com/api/payments/ipn` | **CO** | nơi VNPay báo kết quả server-to-server |

> **`VNPAY_RETURN_URL` KHÔNG kèm query string.** VNPay sẽ append
> `vnp_Amount`, `vnp_TxnRef`, `vnp_SecureHash` vào URL này. App đã bóc phần
> `path` (bỏ query) nên vẫn tới đúng trang "Kết quả thanh toán".
>
> **URL KHÔNG được có dấu tiếng Việt.** Server tự bỏ dấu + bỏ khoảng trắng cho
> `vnp_ReturnUrl`/`vnp_IpnUrl` trước khi gửi, nhưng nên đặt URL sạch ngay từ
> đầu để không phải phụ thuộc hành vi đó.

### 2.3. VNPay - chọn BỘ GIAO THỨC (biến môi trường, KHÔNG cần build lại)

VNPay có HAI bộ giao thức khác nhau. Chúng KHÔNG chỉ khác ở URL:

| | API CŨ (mặc định) | API MỚI 2.1.0 |
|---|---|---|
| URL gateway | `https://sandbox.vnpayment.vn/paymentgateway/paymenturl.htm` | `https://sandbox.vnpayment.vn/paymentv2/vpcpay.html` |
| `vnp_Command` | `pay_web` | `pay` |
| `vnp_Version` | không gửi | **bắt buộc** `2.1.0` |
| `vnp_OrderType` | không gửi | **bắt buộc** (ví dụ `other`) |
| `vnp_SecureHashType` | được gửi (2.0.x) | **KHÔNG được gửi** (tài liệu VNPay) |
| Thuật toán chữ ký | HMAC-SHA256 | HMAC-SHA512 |
| `vnp_TransactionStatus` báo THÀNH CÔNG | `01` | `00` |

| Key | Mặc định | Khi nào đổi |
|---|---|---|
| `VNPAY_BASE_URL` | `https://sandbox.vnpayment.vn/paymentgateway/paymenturl.htm` | URL gateway (sandbox ⇄ production) |
| `VNPAY_COMMAND` | `pay_web` | `pay` khi dùng API 2.1.0 |
| `VNPAY_VERSION` | *(rỗng = không gửi)* | `2.1.0` khi dùng API 2.1.0 |
| `VNPAY_ORDER_TYPE` | *(rỗng = không gửi)* | ví dụ `other` khi dùng API 2.1.0 |
| `VNPAY_HASH_ALGORITHM` | `HmacSHA256` | `SHA512` khi dùng API 2.1.0 |
| `VNPAY_HASH_TYPE` | *(rỗng = không gửi)* | `SHA256`/`SHA512` cho API 2.0.x. **Để rỗng khi dùng API 2.1.0** |

Chấp nhận cả `SHA512` và `HmacSHA512` cho `VNPAY_HASH_ALGORITHM`.

> **Chuyển sang API 2.1.0 = đổi CẢ BỘ biến, KHÔNG phải đổi 1 biến.**
> Thiếu `vnp_Version`/`vnp_OrderType` ⇒ gateway từ chối đơn. Gửi thừa
> `vnp_SecureHashType` ở 2.1.0 ⇒ lệch bộ tham số mà gateway dùng để tính
> hash ⇒ đơn bị từ chối. Server đã tự chặn 2 trường hợp sai này (thiếu
> version/order-type thì báo thiếu biến; 2.1.0 thì không gửi `vnp_SecureHashType`),
> nhưng vẫn phải đặt đủ bộ biến ở Render.

### 2.4. CORS (tuỳ chọn)

| Key | Mặc định |
|---|---|
| `CORS_ALLOWED_ORIGINS` | `http://localhost:*,http://127.0.0.1:*,https://career-map-2026-b08f1.web.app,https://career-map-2026-b08f1.firebaseapp.com` |

## 3. Trước khi bật `VNPAY_ENABLED=true`

1. **Phải chạy 1 giao dịch THẬT trên sandbox** để xác nhận gateway chấp nhận
   chữ ký. Nếu gateway báo lỗi chữ ký:
   - kiểm tra `vnp_SecureHash` trong payUrl có đúng 64 hex (API cũ) hay 128 hex
     (API 2.1.0) không;
   - kiểm tra `VNPAY_HASH_ALGORITHM` có khớp bộ giao thức không;
   - **đừng đổi lệch một biến**: xem bảng ở §2.3.
2. Kiểm tra `vnp_ReturnUrl` trong payUrl (mở payUrl, xem query string) có trỏ
   về `/ket-qua-thanh-toan` không.
3. Kiểm tra log Render có dòng `[FINISH] phong=...` khi kết thúc ván, và có log
   IPN (`IPN xử lý thất bại...`) khi VNPay gọi.
4. Kiểm tra **IPN có được trả `RspCode` hợp lệ**: gateway VNPay quyết định retry
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
     --data 'vnp_TxnRef=khong-ton-tai&vnp_TransactionStatus=01&vnp_Amount=5000000&vnp_SecureHash=sai'
# API 2.1.0 gọi IPN bằng GET:
curl -i 'https://<server>.onrender.com/api/payments/ipn?vnp_TxnRef=khong-ton-tai&vnp_TransactionStatus=01&vnp_Amount=5000000&vnp_SecureHash=sai'
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
