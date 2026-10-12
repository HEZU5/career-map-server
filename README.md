# Holland Career Explorer

Ứng dụng Flutter cho board game định hướng nghề nghiệp theo Holland RIASEC.

## Chạy Flutter

```powershell
flutter pub get
flutter run
```

Ứng dụng có sảnh tạo/vào phòng, bạn bè mẫu, bốn người chơi cố định ở bốn góc màn hình, bàn cờ RIASEC chữ nhật, xúc xắc quay, popup kết quả, quân cờ chuyển từng ô, popup điểm và bảng điểm theo nhóm. Màn chơi tự khóa landscape/full screen để phù hợp chơi chung trên một điện thoại.

## Chạy API Java

Yêu cầu Java 21 và Maven.

```powershell
cd server
mvn spring-boot:run
```

API MVP: `POST /api/rooms`, `GET /api/rooms/{code}`, `POST /api/rooms/{code}/players`, `POST /api/game/roll-dice`, `GET /api/game/tile/{position}`, `POST /api/game/answer`, `GET /api/game/score/{playerId}`, `POST /api/game/check-winner`, `GET /api/riasec/{category}/description`.

Để đưa lên môi trường thật, bổ sung PostgreSQL, JWT và WebSocket để đồng bộ lượt chơi giữa tối đa bốn thiết bị.
