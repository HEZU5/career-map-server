# Plan — VNPay API 2.1.0 + luật thắng (chạm START lần 2, 15 câu hỏi)

> Ngày: 2026-10-22. Nhánh `main` (người dùng đã cho phép làm trực tiếp trên `main`).
> Backend commit + push bình thường. **Frontend KHÔNG commit** (deploy-only).

## Vấn đề đã xác minh trên production

1. **Thanh toán 404.** `https://sandbox.vnpayment.vn/paymentgateway/paymenturl.htm` (API cũ,
   `vnp_Command=pay_web`) → HTTP 404 "Rất tiếc, trang bạn tìm kiếm không tồn tại".
   API cũ **đã bị VNPay thu hồi**. Endpoint đúng: `https://sandbox.vnpayment.vn/paymentv2/vpcpay.html`.
2. **Thuật toán hash sai.** API 2.1.0 dùng **HMAC-SHA512**; server đang ký SHA256 → "Sai chữ ký".
3. **`vnp_IpAddr` bị bỏ sót.** Gateway coi `vnp_IpAddr` là BẮT BUỘC; server bỏ tham số này khi
   không đọc được `X-Forwarded-For` → "Dữ liệu gửi sang không đúng định dạng".
4. **Luật thắng.** `completedLaps` cộng ngay ở lần tung ĐẦU (vì `raw = pos - value < 0` khi
   `pos == 0`) ⇒ điều kiện "1 vòng" thành vô nghĩa. Đền đòi: chỉ lần **chạm START thứ 2** mới tính.
5. **15 câu hỏi**, không phải 12.

## Global Constraints

- Backend: Java 17 / Spring Boot, Maven (`.\mvnw.cmd`), JUnit 5 + Mockito + AssertJ.
- **KHÔNG** thêm dependency. **KHÔNG** thêm bảng/cột DB.
- Biến môi trường là nguồn cấu hình: mặc định mới trong `application.properties` phải đúng
  mà **không cần** sửa biến môi trường trên Render.
- Tiếng Việt có dấu trong Javadoc/comment OK; file phải lưu UTF-8.
- Frontend (`lib/`) **không** commit, **không** push.
- Câu thông báo thất bại về điều kiện thắng giữ nguyên **byte-for-byte**:
  `Bạn đã chơi rất tốt — vẫn chưa đủ điều kiện chiến thắng. Cố gắng hơn ở những lượt sau nhé! 🌟`

## Task 1 — VNPay API 2.1.0

Sửa `VnpayConfig`, `VnpaySignatureService`, `PaymentService`, `application.properties` + test.

## Task 2 — Luật thắng: chạm START lần 2 + 15 câu hỏi

Sửa `TurnCoordinator`, `GameService`, `GameRulesConfig`, `application.properties` + test.

## Thứ tự

Task 1 xong (commit) → Task 2 xong (commit) → push backend → build+deploy frontend → verify
thanh toán thật trên sandbox.

## Ruling đã chốt (ghi lại để implementer không phải tự quyết)

- **Ruling A:** API cũ (`pay_web`) **không còn dùng** — endpoint đã 404. Mặc định chuyển hẳn sang
  2.1.0 (`paymentv2/vpcpay.html`, `vnp_Command=pay`, `vnp_Version=2.1.0`, `vnp_OrderType=other`).
  Vẫn giữ biến môi trường để quay lại được, nhưng mặc định là 2.1.0.
- **Ruling B:** Thuật toán hash **mặc định theo phiên bản API**: 2.1.0 → `HmacSHA512`,
  API cũ → `HmacSHA256`. Không hardcode SHA512 vì còn hỗ trợ API cũ.
- **Ruling C:** `vnp_SecureHashType` **loại khỏi chuỗi băm** (không chỉ loại `vnp_SecureHash`).
  Lý do: IPN của 2.1.0 có thể kèm `vnp_SecureHashType`, và mẫu chính thức của VNPay loại nó
  khỏi `fields` trước khi băm. Hệ quả: bộ tham số API cũ (vốn gửi kèm `vnp_SecureHashType`)
  không còn khớp — chấp nhận, vì API cũ đã chết.
- **Ruling D:** `vnp_IpAddr` **không bao giờ được bỏ**. Không đọc được IP client ⇒ dùng
  fallback `127.0.0.1` (giá trị mẫu chính thức của VNPay) + log cảnh báo.
- **Ruling E:** `vnp_OrderType` = `other` (giá trị mặc định trong mẫu chính thức).
- **Ruling F:** Điều kiện "chạm START lần 2" = bước đi **hoặc đáp đúng ô START, hoặc đi qua ô
  START**, và quân **không được đứng ở ô START trước bước đi** (để lần rời START đầu tiên
  không tính). Xem Task 2 để có công thức chính xác.

## Verify

- `cd server && .\mvnw.cmd clean test` → 0 fail.
- Deploy backend (Render auto-deploy qua push) rồi `GET /api/game/config` phải trả
  `winMinAnswers: 15`.
- Tạo 1 đơn thật qua `POST /api/payments` và mở `payUrl` trong trình duyệt ⇒ phải ra **trang
  thanh toán VNPay**, không phải trang "Thông báo: Sai chữ ký".
