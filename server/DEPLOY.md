# Deploy backend `career-map-server` lên Render

Tài liệu này nằm trong `server/` vì Render **chỉ đọc `render.yaml` ở GỐC repo**,
mà file `render.yaml` của dự án nằm trong `server/`. ⇒ **KHÔNG deploy bằng
Blueprint** (Render sẽ không thấy file `render.yaml` ở gốc repo). Phải tạo
**Web Service thủ công** và trỏ Dockerfile trong `server/`.

---

## 1. Tạo Web Service thủ công (không dùng Blueprint)

Trong Render Dashboard:

1. **New + → Web Service** → Connect repo GitHub → chọn branch `main`.
2. **Runtime / Build Method**: `Docker`.
   - Dockerfile path: `./server/Dockerfile` (đường dẫn TƯƠNG ĐỐI so với gốc repo).
   - Docker context: `./server` — `server/Dockerfile` copy `.mvn`, `mvnw`,
     `pom.xml`, `src` ⇒ context BẮT BUỘC là thư mục `server/`.
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

### 2.2. VNPay — 5 biến BẮT BUỘC để bán được báo cáo

| Key | Ví dụ | Bắt buộc? | Vì sao |
|---|---|---|---|
| `VNPAY_ENABLED` | `true` | **CÓ** | `false` ⇒ client ẩn nút "Nhận báo cáo", không ai mua được |
| `VNPAY_TMN_CODE` | mã merchant của VNPay | **CÓ** | `vnp_TmnCode` trong payUrl |
| `VNPAY_HASH_SECRET` | khoá bí mật của VNPay | **CÓ** | khoá HMAC ký đơn — KHÔNG hardcode, KHÔNG commit |
| `VNPAY_RETURN_URL` | `https://career-map-2026-b08f1.web.app/ket-qua-thanh-toan` | **CÓ** | nơi VNPay đưa người chơi về sau khi trả |
| `VNPAY_IPN_URL` | `https://<server>.onrender.com/api/payments/ipn` | **CÓ** | nơi VNPay báo kết quả server-to-server |

> **`VNPAY_RETURN_URL` KHÔNG kèm query string.** VNPay tự append
> `vnp_Amount`, `vnp_TxnRef`, `vnp_SecureHash`… vào URL này. App đã bóc phần
> `path` (bỏ query) nên vẫn tới đúng trang "Kết quả thanh toán".

### 2.3. VNPay — 3 biến TUỲ CHỌN (đổi protocol KHÔNG cần build lại)

| Key | Mặc định | Khi nào đổi |
|---|---|---|
| `VNPAY_BASE_URL` | `https://sandbox.vnpayment.vn/paymentgateway/paymenturl.htm` | Dùng API mới `paymentv2/vpcpay.html` (và production `https://vnpayment.vn/...`) |
| `VNPAY_HASH_ALGORITHM` | `HmacSHA256` | Đặt `SHA512` nếu gateway của bạn yêu cầu HMAC-SHA512 (hash 128 hex ký tự) |
| `VNPAY_HASH_TYPE` | *(rỗng)* | Đặt `SHA256`/`SHA512` để gửi kèm `vnp_SecureHashType` cho gateway |

Chấp nhận cả `SHA512` và `HmacSHA512` cho `VNPAY_HASH_ALGORITHM`.

### 2.4. CORS (tuỳ chọn)

| Key | Mặc định |
|---|---|
| `CORS_ALLOWED_ORIGINS` | `http://localhost:*,http://127.0.0.1:*,https://career-map-2026-b08f1.web.app,https://career-map-2026-b08f1.firebaseapp.com` |

## 3. Trước khi bật `VNPAY_ENABLED=true`

1. **Phải chạy 1 giao dịch THẬT trên sandbox** để xác nhận gateway chấp nhận
   chữ ký. Nếu gateway báo lỗi chữ ký:
   - đổi `VNPAY_HASH_ALGORITHM=SHA512` **và** `VNPAY_BASE_URL` sang
     `https://sandbox.vnpayment.vn/paymentv2/vpcpay.html` ⇒ **không cần sửa code**.
2. Kiểm tra `vnp_ReturnUrl` trong payUrl (mở payUrl, xem query string) có trỏ
   về `/ket-qua-thanh-toan` không.
3. Kiểm tra log Render có dòng `[FINISH] phong=...` khi kết thúc ván, và log IPN
   (`IPN xử lý thất bại...`) khi VNPay gọi.

## 4. Monitor UptimeRobot (bắt buộc với Render free)

Render free **ngủ** sau ~15 phút không có traffic ⇒ IPN tới lúc server đang ngủ
sẽ fail ⇒ đơn kẹt PENDING ⇒ người chơi mất tiền.

- Tạo monitor: `GET https://<server>.onrender.com/health`, khoảng 5 phút/lần.
- Kiểm tra server thật sự tỉnh: `curl -i https://<server>.onrender.com/health`.

## 5. Lệnh kiểm tra sau khi deploy

```bash
curl -i https://<server>.onrender.com/health
curl -i https://<server>.onrender.com/api/game/config
curl -i -X POST https://<server>.onrender.com/api/payments/ipn \
     -H 'Content-Type: application/x-www-form-urlencoded' \
     --data 'vnp_TxnRef=khong-ton-tai&vnp_TransactionStatus=01&vnp_Amount=5000000&vnp_SecureHash=sai'
```

Lệnh IPN cuối phải trả **HTTP 200** với `{"RspCode":"97",...}` (chữ ký sai) —
KHÔNG được 415, KHÔNG được 400.

## 6. Lưu ý về database

- `spring.jpa.hibernate.ddl-auto=update` ⇒ schema tự cập nhật, **không có
  migration**. Bảng `room_riasec_snapshot` (bản cũ, chụp điểm theo phòng) đã
  bị thay bằng `room_player_riasec_snapshot` (chụp điểm **theo từng người
  chơi**). Bảng cũ còn nằm trong DB nhưng không còn được dùng — có thể xoá
  tay: `DROP TABLE room_riasec_snapshot;`
- Cột `payment_orders.room_code` nay cho phép NULL (đơn tạo sau khi phòng đã
  bị dọn vẫn ghi được thay vì lỗi 500).
