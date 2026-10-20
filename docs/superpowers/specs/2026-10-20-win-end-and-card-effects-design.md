# Thiết kế: Kết thúc ván theo điều kiện + Hiệu ứng lá Cơ hợi thật

Ngày: 2026-10-20 · Trạng thái: chờ duyệt
Phạm vi: backend `server/` (Spring Boot) + frontend `lib/` (Flutter web)

---

## 1. Vì sao

Hai yêu cầu của người dùng:

1. Khi người chơi **đã đủ điều kiện** mà **đi qua ô START** (hoàn thành 1 vòng) →
   ván **kết thúc ngay**, người đó thắng, được **+5 điểm chiến thắng**, và màn
   kết quả có **bảng xếp hạng theo điểm chiến thắng**.
2. Các lá Cơ hội/Thử thách có **câu lựa chọn trong nội dung** hiện chỉ được viết
   ra rồi bỏ qua — phải làm thành **lựa chọn thật**.

## 2. Hiện trạng (đã kiểm chứng trong code)

- Điều kiện đủ điều kiện: `GameService.isEligibleToWin()` =
  `!isLeft && completedLaps >= winMinLaps && totalAnswered >= winMinAnswers`
  (ngưỡng `WIN_MIN_LAPS=1`, `WIN_MIN_ANSWERS=12`).
- `completedLaps` tăng ở `TurnCoordinator.rollAndMove():112-114` khi vị trí mới < vị trí cũ.
- Ván **chỉ** kết thúc qua `POST /api/game/finish` (chủ phòng bấm).
- `winPoints` do **client** gửi lên (`GameService.answer():149`) — server tin luôn.
- Danh mục lá bài **chỉ nằm ở Flutter** (`lib/features/game/data/career_cards.dart`),
  server không biết lá nào có hiệu ứng gì.

## 3. Phần 1 — Kết thúc ván khi đủ điều kiện

### 3.1 Cấu hình

`GameRulesConfig` thêm `winBonusPoints` ← `${game.win-bonus-points:5}`
(biến môi trường `WIN_BONUS_POINTS`, đổi được lúc deploy không cần build lại).

### 3.2 Server

**Tách `finish()`** thành 2 phần:

- `finish(FinishRequest)` — giữ nguyên kiểm tra quyền chủ phòng, rồi gọi
  `finishWithWinner(room, pickWinner(room))`.
- `private GameSnapshot finishWithWinner(Room room, String winnerKey)` — gộp phần
  còn lại: `setStatus(FINISHED)`, `setFinishedAt`, `setWinnerKey(winnerKey)`,
  `room.captureRiasecSnapshot()`, `clearTurn()`, `setPendingAnswer(false)`,
  `broadcaster.gameFinished(...)`.

**Điểm kết thúc mới** — trong `GameService.rollDice()`, ngay sau khi
`turns.rollForTurn(...)` trả về:

```java
if (isEligibleToWin(player)) {
  player.addWinPoints(rules.getWinBonusPoints());
  finishWithWinner(room, player.getPlayerKey());
}
```

Vì `completedLaps` vừa được cộng ở `rollAndMove()` **trước** khi về đây, nên điều
kiện thực tế là: **đã trả lời ≥ 12 câu VÀ vừa đi qua ô START**.

`rollSolo()` (chơi offline, không có phòng): cùng kiểm tra; vì không có phòng để
broadcast, trạng thái kết thúc phải truyền về client qua response.

**`DiceResponse`** thêm 2 field có default: `boolean gameFinished`, `String winnerKey`.

**Thứ tự quan trọng:** kiểm tra đủ điều kiện phải **sau** khi `rollAndMove` đã
cộng `completedLaps`; nếu kiểm tra trước thì điều kiện `completedLaps >= 1` chưa
bao giờ đạt đúng lúc qua START.

**Ai thắng nếu hai người cùng đạt?** Người **đi qua START trước** thắng, vì ván
kết thúc ngay tại thời điểm đó. Không cần `pickWinner()` cho trường hợp này —
`pickWinner()` vẫn giữ cho đường kết thúc thủ công của chủ phòng.

### 3.3 Client

- `GameApi`/`GameController.rollDice()` đọc `gameFinished` + `winnerKey`; khi
  `gameFinished` → `_game.onFinished(winnerKey)`.
- `game_page.dart`: sau khi roll mà `gameFinished` → `_showResultScreen()`.
  **Chống hiện 2 lần** khi online (WS event `finished` + response cùng tới):
  dùng một cờ `_resultShown` trong state, đặt trước khi mở hộp thoại, reset khi
  vào ván mới.
- **Bảng xếp hạng** trong `GameResultContent`: sắp `game.players` theo
  `winPoints` giảm dần (hoà → theo thứ tự vào phòng), hiện hạng, tên, điểm thắng;
  highlight người thắng; người đã rời phòng nằm cuối bảng (ghi rõ "đã rời").
  Dữ liệu `winPoints` **đã có sẵn** trong snapshot (`GamePlayerInfo.winPoints`).

## 4. Phần 2 — Hiệu ứng lá Cơ hội thật

### 4.1 Nguyên tắc: server là nguồn sự thật

Hiện client tự khai `winPoints` và server tin (`GameService.answer():149`). Khi làm
hiệu ứng, **server phải tự tra bảng hiệu ứng và tự tính điểm**, không nhận điểm từ
client. Việc này sửa luôn lỗ hổng cũ.

### 4.2 Bảng hiệu ứng ở server

`server/.../domain/BonusEffect.java` (enum) + `server/.../service/CardEffectRegistry.java`:

| Lá | Hiệu ứng | winPoints | Cần người chơi chọn? |
|---|---|---|---|
| CƠ HỢI 01 | `NONE` | 2 | không |
| CƠ HỢI 02 | `EXTRA_ROLL` | 2 | không |
| CƠ HỢI 03 | `MOVE_2` | 2 | không |
| CƠ HỢI 04 | `PICK_PLAYER_PLUS1` | 1 | **có** — chọn 1 người chơi |
| CƠ HỢI 05 | `NONE` | 3 | không |
| CƠ HỢI 06 | `EXTRA_ROLL` | 2 | không |
| CƠ HỢI 07 | `NONE` | 2 | không |
| CƠ HỢI 08 | `ALL_PLAYERS_PLUS1` | 1 | không (cộng cho tất cả) |
| CƠ HỢI 09 | `MOVE_3` | 2 | không |
| CƠ HỢI 10 | `PLUS3_OR_MOVE_2` | 3 | **có** — chọn 1 trong 2 |
| THỬ THÁCH 01–10 | `NONE` | 2 hoặc 3 (theo lá) | không |

Ghi chú: "chơi thêm 1 lượt" (CƠ HỢI 02) và "tung xúc xắc thêm 1 lần" (CƠ HỢI 06) về
cơ chế là **giống nhau** → dùng chung một effect `EXTRA_ROLL`.

### 4.3 Endpoint mới

`POST /api/game/bonus-effect`

Vào: `{ "playerId": "...", "cardId": "CƠ HỢI 04", "choice": "MOVE_2" | "PLUS_3" | null, "targetPlayerKey": "guest-H123-1" | null }`

Ra: `BonusEffectResponse { effect, winPoints, fromPosition, toPosition, awardedPlayerKeys, message }`

Server tra bảng → kiểm tra `choice`/`targetPlayerKey` có hợp lệ với lá đó không →
áp hiệu ứng → trả kết quả.

### 4.4 Cách áp từng hiệu ứng

- **`NONE`** — cộng `winPoints` cho người chơi, đóng lượt, chuyển lượt.
- **`EXTRA_ROLL`** — cộng `winPoints`, đóng lượt hiện tại rồi bắt đầu lượt
  `WAITING_ROLL` cho **chính người đó** (được tung tiếp ngay).
- **`MOVE_2` / `MOVE_3`** — **không** cộng `winPoints` (lá này thay bằng việc đi).
  Dời vị trí người chơi tiếp N ô theo chiều đi (`position - N`, `Math.floorMod`).
  Nếu đích là ô thẻ → `setPendingAnswer(true)` + `beginTurn(..., WAITING_ANSWER, ...)`
  để mở thẻ đó. Nếu không phải ô thẻ → đóng lượt, chuyển lượt.
  **Cần trả `fromPosition`/`toPosition`** để client vẽ chuyển động.
- **`PICK_PLAYER_PLUS1`** — người chơi chọn 1 người trong phòng (có thể là chính
  họ; không chọn người đã rời phòng). Cả người chọn và người được chọn cùng
  `+1 winPoints`.
- **`ALL_PLAYERS_PLUS1`** — mọi người trong phòng (kể cả người vừa đến) `+1`.
- **`PLUS3_OR_MOVE_2`** — người chơi chọn: `PLUS_3` → `+3 winPoints`; `MOVE_2` →
  đi thêm 2 ô (không cộng điểm).

Mọi hiệu ứng đều **đóng lượt hiện tại** rồi mới áp (trừ `EXTRA_ROLL` vốn bắt
đầu lượt mới cho chính người đó). Kết thúc → `broadcaster.gameUpdated(...)` để
mọi client thấy vị trí mới.

### 4.5 Client

- `OpportunityCard`/`ChallengeCard` thêm `effect` + `choices` để UI biết lá nào
  cần gì. **Nguồn hiệu ứng phải khớp với bảng server** — thêm test ở client khẳng
  định danh sách `cardId` mà client biết khác với bảng server là lỗi.
- `_openOpportunity`:
  - `PICK_PLAYER_PLUS1` → hiện danh sách người chơi để bấm (không cần bấm nút
    "Nhận thưởng" nữa; bấm vào người là xong).
  - `PLUS3_OR_MOVE_2` → 2 nút lựa chọn.
  - còn lại → nút "Nhận thưởng" như hiện tại, nhưng nội dung hiển thị đúng hiệu
    ứng thật (vd "Bạn được đi thêm 2 ô").
- Gọi `POST /api/game/bonus-effect` cho **mọi** lá Cơ hội và Thử thách (thay cho
  `answerBonusCard`), để server tự tính điểm.
- Vẽ chuyển động khi server di chuyển: dùng `fromPosition`/`toPosition` trả về,
  chạy animation di chuyển giống lúc tung xúc xắc.

### 4.6 Ngoài phạm vi

`THỬ THÁCH 02` ("các người chơi còn lại có 30 giây để đoán") và `THỬ THÁCH 09`
("người chơi bên trái chọn 1 nghề") cần **người khác trong phòng hành động** — khác
hẳn hạng mục "chọn trong thẻ". Tách riêng, ghi nhận để làm sau.

## 5. Thứ tự làm

1. Server: cấu hình `winBonusPoints` + `finishWithWinner` + tự kết thúc khi qua START
2. Server: `DiceResponse` thêm `gameFinished`/`winnerKey`
3. Client: nhận `gameFinished` → hiện màn kết quả (có chống hiện 2 lần)
4. Client: bảng xếp hạng theo điểm chiến thắng trong hộp thoại kết quả
5. Server: enum hiệu ứng + bảng tra hiệu ứng cho 20 lá
6. Server: endpoint `/api/game/bonus-effect` + áp từng hiệu ứng
7. Client: UI lựa chọn theo hiệu ứng + gọi endpoint mới
8. Client: vẽ chuyển động khi server di chuyển người chơi

Mỗi bước là một task có test riêng, review riêng.

## 6. Ràng buộc kỹ thuật

- Backend test: JUnit 5 + AssertJ thuần, không Spring context, không DB.
- Frontend test: `flutter test`, widget test.
- Không thêm thư viện mới.
- Frontend **không** commit vào git (`lib/` bị ignore).
- Encoding UTF-8 — dùng `ReadAllLines`/`WriteAllLines` với `UTF8Encoding::new($false)`,
  **không** dùng `Set-Content`/`Get-Content` không chỉ định encoding.
- Câu thông báo cho người chưa đủ điều kiện giữ **nguyên byte-for-byte**.
