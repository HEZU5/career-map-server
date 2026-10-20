# Kết thúc ván theo điều kiện + Hiệu ứng lá Cơ hợi — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ván tự kết thúc khi người chơi đủ điều kiện đi qua ô START (người đó thắng, +5 điểm thắng, có bảng xếp hạng); các lá Cơ hội có lựa chọn trong nội dung trở thành lựa chọn thật do server quyết.

**Architecture:** Server thành nguồn sự thật cho cả luật thắng lẫn hiệu ứng lá bài. `GameService.finish()` tách thành `finishWithWinner(room, winnerKey)` dùng chung cho kết thúc thủ công và kết thúc tự động. Bảng hiệu ứng 20 lá đặt ở server, client chỉ gửi `cardId` + `choice` + `targetPlayerKey`.

**Tech Stack:** Spring Boot 3.4 / Java 21 / JPA · Flutter web / provider

**Spec:** `docs/superpowers/specs/2026-10-20-win-end-and-card-effects-design.md`

## Global Constraints

- `winBonusPoints` đọc từ `game.win-bonus-points` = `${WIN_BONUS_POINTS:5}`.
- Điều kiện đủ điều kiện KHÔNG đổi: `!isLeft && completedLaps >= winMinLaps && totalAnswered >= winMinAnswers` (`WIN_MIN_LAPS=1`, `WIN_MIN_ANSWERS=12`).
- Server tự tính `winPoints` từ bảng hiệu ứng — **không** nhận điểm từ client.
- Backend test: JUnit 5 + AssertJ thuần, không Spring context, không DB. MockMvc được phép.
- Frontend test: `flutter test`. Frontend **không** commit vào git (`lib/` bị ignore).
- Không thêm thư viện mới.
- Encoding UTF-8: dùng `[System.IO.File]::ReadAllLines($p,[System.Text.Encoding]::UTF8)` + `WriteAllLines($p,$lines,[System.Text.UTF8Encoding]::new($false))`. **KHÔNG** dùng `Set-Content`/`Get-Content` không chỉ định encoding.
- Câu thông báo người chưa đủ điều kiện giữ **nguyên byte-for-byte**.

---

### Task 1: Server — cấu hình + `finishWithWinner` + tự kết thúc khi qua START

**Files:**
- Modify: `server/src/main/java/vn/careermap/config/GameRulesConfig.java`
- Modify: `server/src/main/resources/application.properties`
- Modify: `server/src/main/java/vn/careermap/service/GameService.java`
- Modify: `server/src/main/java/vn/careermap/web/dto/DiceResponse.java`
- Test: `server/src/test/java/vn/careermap/service/GameAutoFinishTest.java` (mới)

**Interfaces:**
- Produces: `GameRulesConfig.getWinBonusPoints()` → `long` (mặc định 5).
- Produces: `GameService.finishWithWinner(Room, String winnerKey)` (private).
- Produces: `DiceResponse` có thêm `boolean gameFinished` + `String winnerKey` (default `false` / `null`).

- [ ] **Step 1: Viết test fail**

`GameAutoFinishTest.java`:
```java
@Test
void nguoiDuDieuKienQuaStartThiVanKetThuc()      // roll qua START khi đủ 12 câu -> room FINISHED, winnerKey = player đó
@Test
void nguoiChuaDuDieuKienQuaStartThiVanChuaKetThuc()
@Test
void nguoiThangNhanThêmDiemChienThang()          // winPoints tăng đúng winBonusPoints
@Test
void vanSoloCungKetThucKhiDuDieuKien()            // rollSolo trả DiceResponse.gameFinished = true
```

- [ ] **Step 2: Chạy để thấy fail** — `cd server && .\mvnw.cmd test -Dtest=GameAutoFinishTest`

- [ ] **Step 3: Thêm cấu hình**

`GameRulesConfig`: thêm field `winBonusPoints` + `@Value("${game.win-bonus-points:5}")` + getter.
`application.properties`: `game.win-bonus-points=${WIN_BONUS_POINTS:5}`

- [ ] **Step 4: Tách `finish()`**

`GameService.finish()` giữ nguyên phần kiểm tra quyền host (dòng ~192) rồi gọi
`finishWithWinner(room, pickWinner(room))`.
`private GameSnapshot finishWithWinner(Room room, String winnerKey)` chứa phần còn
lại (setStatus FINISHED, setFinishedAt, setWinnerKey, captureRiasecSnapshot,
clearTurn, setPendingAnswer(false), log, build snapshot, broadcast gameFinished).

- [ ] **Step 5: Thêm điểm kết thúc tự động**

Trong `rollDice()`, ngay sau `turns.rollForTurn(...)` trả về không null:
```java
if (isEligibleToWin(player)) {
  player.addWinPoints(rules.getWinBonusPoints());
  finishWithWinner(room, player.getPlayerKey());
}
```
Trong `rollSolo()`: cùng kiểm tra; không có phòng nên chỉ cộng điểm và **không**
gọi `finishWithWinner` — trạng thái kết thúc truyền qua `DiceResponse`.

**BẮT BUỘC:** kiểm tra đủ điều kiện phải **sau** khi `rollAndMove` đã cộng
`completedLaps` (đó là lý do `completedLaps >= 1` đạt đúng lúc qua START).

- [ ] **Step 6: `DiceResponse`**

Thêm 2 field có default để không phá call site:
`boolean gameFinished` (default false), `String winnerKey` (default null).
`rollDice` trả kèm `gameFinished=true, winnerKey=...` khi tự kết thúc.

- [ ] **Step 7: Chạy test** — `cd server && .\mvnw.cmd clean test` → ≥ baseline, 0 fail.

- [ ] **Step 8: Commit** — `git add server/ && git commit -m "feat(game): tu ket thuc van khi nguoi du dieu kien qua START"`

---

### Task 2: Client — nhận `gameFinished` + bảng xếp hạng

**Files:**
- Modify: `lib/features/game/data/game_api.dart` (parse `DiceResponse`)
- Modify: `lib/features/game/presentation/game_controller.dart`
- Modify: `lib/features/game/presentation/game_page.dart`
- Test: `test/auto_finish_and_ranking_test.dart` (mới)

**Interfaces:**
- Consumes: `DiceResponse.gameFinished`, `DiceResponse.winnerKey` (Task 1).
- Produces: `GameResultContent` nhận thêm `List<Player> ranking` (đã sort theo winPoints giảm dần).

- [ ] **Step 1: Viết test fail**

```dart
testWidgets('server bao gameFinished thi ra man hinh ket qua', ...)
testWidgets('bang xep hang sap theo diem chien thang giam dan', ...)
testWidgets('chi hien man hinh ket qua mot lan', ...)  // WS finished + response cùng tới -> 1 lần
```

- [ ] **Step 2: Chạy để thấy fail** — `flutter test test/auto_finish_and_ranking_test.dart`

- [ ] **Step 3: Parse `gameFinished` trong `GameApi`/`GameController`**

`GameController.rollDice()`: nếu response `gameFinished` → `_game.onFinished(winnerKey)`.

- [ ] **Step 4: Chống hiện 2 lần trong `game_page`**

Thêm `bool _resultShown = false;` trong state. `_showResultScreen()` set `true` trước khi
mở hộp thoại và return sớm nếu đã true. Reset khi vào ván mới.

- [ ] **Step 5: Bảng xếp hạng**

Trong `_showResultScreen()`: sort `game.players` theo `winPoints` giảm dần (hoà → theo
thứ tự vào phòng), truyền vào `GameResultContent`.
Trong `GameResultContent`: vẽ bảng hạng / tên / điểm thắng; highlight người thắng;
người đã rời phòng nằm cuối, ghi rõ "đã rời".

- [ ] **Step 6: Chạy test** → PASS.

- [ ] **Step 7: Commit** — frontend untracked, KHÔNG `git add lib/`.

---

### Task 3: Server — bảng hiệu ứng 20 lá + endpoint `/api/game/bonus-effect`

**Files:**
- Create: `server/src/main/java/vn/careermap/domain/BonusEffect.java`
- Create: `server/src/main/java/vn/careermap/service/CardEffectRegistry.java`
- Create: `server/src/main/java/vn/careermap/web/dto/BonusEffectRequest.java`
- Create: `server/src/main/java/vn/careermap/web/dto/BonusEffectResponse.java`
- Modify: `server/src/main/java/vn/careermap/service/GameService.java`
- Modify: `server/src/main/java/vn/careermap/web/GameController.java`
- Test: `server/src/test/java/vn/careermap/service/BonusEffectTest.java` (mới)

**Interfaces:**
- Produces: `enum BonusEffect { NONE, EXTRA_ROLL, MOVE_2, MOVE_3, PICK_PLAYER_PLUS1, ALL_PLAYERS_PLUS1, PLUS3_OR_MOVE_2 }`
- Produces: `CardEffectRegistry.effectOf(String cardId)` → `CardEffectSpec { BonusEffect effect, long winPoints, Set<String> allowedChoices }` — trả `null` nếu cardId không biết.
- Produces: `POST /api/game/bonus-effect` body `{playerId, cardId, choice, targetPlayerKey}` → `BonusEffectResponse { effect, winPoints, fromPosition, toPosition, awardedPlayerKeys, message }`.

- [ ] **Step 1: Viết test fail** — `BonusEffectTest.java`

```java
@Test void coHoi01ChiCongDiem()          // NONE -> +2, đóng lượt
@Test void coHoi03DiThem2O()             // MOVE_2 -> position +2, winPoints = 0
@Test void coHoi04ChonMotNguoi()         // PICK_PLAYER_PLUS1 -> cả 2 người +1
@Test void coHoi04KhongChonNguoiLaRaPhong()  // 403
@Test void coHoi08TatCaNguoiNhanPlus1()  // ALL_PLAYERS_PLUS1 -> mọi người +1
@Test void coHoi10ChonMotTrongHai()      // PLUS3_OR_MOVE_2 -> choice hợp lệ mới được
@Test void cardIdKhongBietThiBaoLoi()     // 400
```

- [ ] **Step 2: Chạy để thấy fail**

- [ ] **Step 3: Enum + Registry**

`BonusEffect` enum 7 giá trị. `CardEffectRegistry` là `@Component` với map
`cardId → CardEffectSpec`, đủ 20 lá (10 CƠ HỢI + 10 THỬ THÁCH) theo bảng ở spec §4.2.
Lá THỬ THÁCH: `NONE`, winPoints 2 (trừ 04 và 07 là 3 — kiểm lại đúng số trong
`career_cards.dart`).

- [ ] **Step 4: Áp hiệu ứng trong `GameService`**

`applyBonusEffect(Player, Room, CardEffectSpec, String choice, String targetPlayerKey)`:
- `NONE`: cộng `winPoints` cho người chơi.
- `EXTRA_ROLL`: cộng `winPoints`, đóng lượt hiện tại, `beginTurn(player, WAITING_ROLL, deadline, false)`.
- `MOVE_2`/`MOVE_3`: **không** cộng điểm; dời vị trí tiếp N ô theo chiều đi
  (`Math.floorMod(pos - N, pathSize)`); nếu đích là ô thẻ → `setPendingAnswer(true)` +
  `beginTurn(..., WAITING_ANSWER, ...)`; nếu không → đóng lượt + chuyển lượt.
  Trả `fromPosition`/`toPosition`.
- `PICK_PLAYER_PLUS1`: `targetPlayerKey` phải là người trong phòng, chưa rời; cả hai +1.
- `ALL_PLAYERS_PLUS1`: mọi người trong phòng (kể cả người vừa đến) +1.
- `PLUS3_OR_MOVE_2`: `choice` phải là `PLUS_3` (→ +3 điểm) hoặc `MOVE_2` (→ đi 2 ô).

Kết thúc mọi nhánh (trừ `EXTRA_ROLL` vốn mở lượt mới cho chính người đó) → đóng lượt
hiện tại rồi `broadcaster.gameUpdated(...)`.

- [ ] **Step 5: Controller + DTO**

`@PostMapping("/game/bonus-effect")` trong `GameController`.

- [ ] **Step 6: Chạy test** → PASS.

- [ ] **Step 7: Commit** — `git add server/ && git commit -m "feat(cards): bang hieu ung 20 la + endpoint bonus-effect"`

---

### Task 4: Client — UI lựa chọn theo hiệu ứng + vẽ chuyển động

**Files:**
- Modify: `lib/features/game/data/career_cards.dart` (thêm `effect` + `choices` cho lá)
- Modify: `lib/features/game/presentation/game_page.dart`
- Modify: `lib/features/game/data/game_api.dart` (gọi endpoint mới)
- Test: `test/card_effect_choice_test.dart` (mới)

**Interfaces:**
- Consumes: `BonusEffectResponse` (Task 3).
- Produces: `CardEffect` enum client **phải khớp** enum server.

- [ ] **Step 1: Viết test fail**

```dart
testWidgets('la co hoi 04 hien danh sach nguoi choi de chon', ...)
testWidgets('la co hoi 10 hien hai nut chon', ...)
testWidgets('goi endpoint bonus-effect thay vi answerBonusCard', ...)
testWidgets('danh sach cardId client khop bang server', ...)  // bảng id + effect phải khớp
```

- [ ] **Step 2: Chạy để thấy fail**

- [ ] **Step 3: Thêm `effect`/`choices` vào model lá**

`OpportunityCard` và `ChallengeCard` thêm field `effect` (String/enum) + `choices`.
Gán đúng theo bảng spec §4.2 cho 20 lá.

- [ ] **Step 4: UI chọn**

Trong `_openOpportunity`:
- `PICK_PLAYER_PLUS1` → hiện danh sách người chơi (tên + avatar) để bấm; bấm xong gọi API.
- `PLUS3_OR_MOVE_2` → 2 nút ("+3 Điểm Chiến thắng" / "Đi thêm 2 ô").
- còn lại → nút "Nhận thưởng" như hiện tại, nội dung hiện đúng hiệu ứng thật.
Mọi lá Cơ hội/Thử thách đều gọi `POST /api/game/bonus-effect`.

- [ ] **Step 5: Vẽ chuyển động khi server di chuyển**

Khi `BonusEffectResponse.fromPosition != toPosition`, chạy animation di chuyển người
chơi từ `fromPosition` → `toPosition` (dùng lại cơ chế `_moveController` sẵn có).

- [ ] **Step 6: Chạy test** → PASS.

- [ ] **Step 7: Commit** — frontend untracked, KHÔNG `git add lib/`.
