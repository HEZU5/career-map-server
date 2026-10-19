package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.domain.Player;
import vn.careermap.domain.RiasecCategory;
import vn.careermap.domain.Room;
import vn.careermap.domain.RoomStatus;
import vn.careermap.domain.TurnPhase;
import vn.careermap.repo.PlayerRepository;
import vn.careermap.repo.RoomRepository;
import vn.careermap.web.dto.FinishRequest;
import vn.careermap.web.dto.WinnerRequest;

/**
 * Kết thúc ván: SERVER tự chấm người thắng và chỉ chủ phòng mới kết thúc được.
 *
 * <p>Trước đây {@code winnerKey} do client gửi lên và bất kỳ ai trong phòng cũng
 * gọi được endpoint này — tức client tự phong mình là người thắng, thậm chí kết
 * thúc ván của người khác. Ở đây server là bên quyết định: ngưỡng thắng đọc từ
 * {@link GameRulesConfig}, người thắng là người ĐẦU TIÊN trong phòng đủ ngưỡng,
 * và quyền kết thúc thuộc về {@code effectiveHostKey()}.
 *
 * <p>Thuần JUnit + Mockito, không Spring context, không database.
 */
class GameFinishTest {
  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 12);

  /** Ngưỡng SIẾT hơn mặc định — dùng để chứng minh server đọc ngưỡng từ cấu hình. */
  private static final GameRulesConfig STRICT_RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 3, 30);

  /** Ngưỡng NỚI hơn mặc định — biến môi trường có thể hạ ngưỡng lúc deploy. */
  private static final GameRulesConfig LOOSE_RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip", 50000, 15, 1, 4);

  /** key -> player, đóng vai "database" cho {@code resolvePlayer}. */
  private final Map<String, Player> byKey = new HashMap<>();

  private GameService gameService(GameRulesConfig rules) {
    PlayerRepository players = mock(PlayerRepository.class);
    when(players.findByPlayerKey(anyString()))
        .thenAnswer(call -> Optional.ofNullable(byKey.get(call.<String>getArgument(0))));
    when(players.save(any(Player.class))).thenAnswer(call -> call.getArgument(0));
    return new GameService(
        players,
        mock(GameBroadcaster.class),
        new GameSnapshotBuilder(rules),
        mock(TurnCoordinator.class),
        rules);
  }

  private Room room(String ownerKey, Player... members) {
    Room room = Room.create("H12345", "Chủ phòng", false, ownerKey);
    for (Player p : members) {
      room.addPlayer(p);
      byKey.put(p.getPlayerKey(), p);
    }
    return room;
  }

  private Player player(String key, int laps, int answers) {
    Player p = Player.create(key, key);
    p.addCompletedLaps(laps);
    for (int i = 0; i < answers; i++) {
      p.incrementTotalAnswered();
    }
    return p;
  }

  /** Người chơi đã trả lời: điểm RIASEC nằm ở bảng {@code scores} của riêng họ. */
  private Player scoredPlayer(String key, Map<RiasecCategory, Integer> points) {
    Player p = player(key, 1, 12);
    points.forEach((category, value) -> p.scoreOf(category).addPoints(value));
    return p;
  }

  private static void assertForbidden(Throwable thrown) {
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  // ── Quyền: chỉ chủ phòng mới kết thúc được ──────────────────────────────

  @Test
  void khachKhongKetThucDuoc() {
    Room room = room("owner", player("owner", 3, 30), player("guest-1", 0, 0));
    GameService service = gameService(RULES);

    assertThatThrownBy(() -> service.finish(new FinishRequest("guest-1")))
        .satisfies(GameFinishTest::assertForbidden);

    assertThat(room.getStatus()).isEqualTo(RoomStatus.WAITING);
    assertThat(room.getWinnerKey()).isNull();
  }

  @Test
  void chuPhongKetThucDuoc() {
    Room room = room("owner", player("owner", 0, 0), player("guest-1", 0, 0));
    GameService service = gameService(RULES);

    service.finish(new FinishRequest("owner"));

    assertThat(room.getStatus()).isEqualTo(RoomStatus.FINISHED);
    assertThat(room.getFinishedAt()).isNotNull();
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.NONE);
    assertThat(room.isPendingAnswer()).isFalse();
  }

  @Test
  void nguoiGiuQuyenChuPhongTamCungKetThucDuoc() {
    Room room = room("owner", player("owner", 1, 12), player("guest-1", 0, 0));
    room.setActingHostKey("guest-1");
    GameService service = gameService(RULES);

    service.finish(new FinishRequest("guest-1"));

    assertThat(room.getStatus()).isEqualTo(RoomStatus.FINISHED);
  }

  @Test
  void khachGoiFinishSauKhiVanKetThucVanBiChan() {
    Room room = room("owner", player("owner", 1, 12), player("guest-1", 0, 0));
    GameService service = gameService(RULES);
    service.finish(new FinishRequest("owner"));

    // Ván đã xong nhưng người còn lại trong phòng vẫn KHÔNG được gọi finish:
    // kiểm tra host phải đứng TRƯỚC nhánh "đã kết thúc, trả snapshot".
    assertThatThrownBy(() -> service.finish(new FinishRequest("guest-1")))
        .satisfies(GameFinishTest::assertForbidden);
    assertThat(room.getWinnerKey()).isEqualTo("owner");
  }

  @Test
  void ketThucRoiVanChoPhepChuPhongGoiLai() {
    Room room = room("owner", player("owner", 1, 12), player("guest-1", 0, 0));
    GameService service = gameService(RULES);

    service.finish(new FinishRequest("owner"));
    Instant finishedAt = room.getFinishedAt();
    // Gọi lần hai (client bấm 2 nút / retry mạng): không nổi, không đổi kết quả.
    service.finish(new FinishRequest("owner"));

    assertThat(room.getStatus()).isEqualTo(RoomStatus.FINISHED);
    assertThat(room.getWinnerKey()).isEqualTo("owner");
    assertThat(room.getFinishedAt()).isEqualTo(finishedAt);
  }

  // ── Server tự tính người thắng ───────────────────────────────────────────

  @Test
  void nguoiDauTienDuNguongLaNguoiThang() {
    // Cả hai cùng đủ ngưỡng (1 vòng + 12 câu) → người vào phòng trước thắng.
    Room room = room("owner", player("owner", 1, 12), player("guest-1", 1, 12));
    GameService service = gameService(RULES);

    service.finish(new FinishRequest("owner"));

    assertThat(room.getWinnerKey()).isEqualTo("owner");
  }

  @Test
  void nguoiGioiLuotDauKhongDuNguongThiKhongPhaiNguoiThang() {
    // owner đi trước nhưng thiếu 1 câu; guest-1 mới đủ ngưỡng.
    Room room = room("owner", player("owner", 1, 11), player("guest-1", 1, 12));
    GameService service = gameService(RULES);

    service.finish(new FinishRequest("owner"));

    assertThat(room.getWinnerKey()).isEqualTo("guest-1");
  }

  @Test
  void khongAiDuNguongThiKhongCoNguoiThang() {
    Room room = room("owner", player("owner", 0, 11), player("guest-1", 0, 11));
    GameService service = gameService(RULES);

    service.finish(new FinishRequest("owner"));

    assertThat(room.getStatus()).isEqualTo(RoomStatus.FINISHED);
    assertThat(room.getWinnerKey()).isNull();
  }

  @Test
  void nguongThangDocTuCauHinhKhongHardcode() {
    // Ngưỡng siết: 3 vòng + 30 câu. Ai chỉ 1 vòng/12 câu thì KHÔNG thắng.
    Room room = room("owner", player("owner", 1, 12), player("guest-1", 3, 30));
    GameService service = gameService(STRICT_RULES);

    service.finish(new FinishRequest("owner"));

    assertThat(room.getWinnerKey()).isEqualTo("guest-1");
  }

  // ── Ngưỡng thắng: checkWinner và finish phải dùng CHUNG một nguồn ─────────

  @Test
  void nguongThangCuaCheckWinnerDocTuCauHinh() {
    // 1 vòng / 12 câu: đủ theo RULES nhưng KHÔNG đủ theo STRICT_RULES (3/30).
    byKey.put("owner", player("owner", 1, 12));
    assertThat(gameService(RULES).checkWinner(new WinnerRequest("owner")).eligibleToFinish())
        .isTrue();
    assertThat(gameService(STRICT_RULES).checkWinner(new WinnerRequest("owner")).eligibleToFinish())
        .isFalse();

    // Đổi ngưỡng theo hướng ngược lại (nới xuống 1 vòng / 4 câu).
    byKey.clear();
    byKey.put("guest-1", player("guest-1", 1, 4));
    assertThat(gameService(LOOSE_RULES).checkWinner(new WinnerRequest("guest-1")).eligibleToFinish())
        .isTrue();
    assertThat(gameService(RULES).checkWinner(new WinnerRequest("guest-1")).eligibleToFinish())
        .isFalse();
  }

  @Test
  void checkWinnerVaFinishChoCungKetQua() {
    // `game.win-min-*` là biến môi trường override được lúc deploy. Dù người
    // chơi gọi endpoint nào, server phải trả lời giống nhau về câu "đủ điều
    // kiện chưa" — nếu lệch thì client hiện nút kết thúc rồi server lại từ chối.
    int[][] stats = {{0, 0}, {1, 11}, {1, 12}, {2, 29}, {3, 30}};
    for (GameRulesConfig rules : List.of(RULES, STRICT_RULES, LOOSE_RULES)) {
      for (int[] s : stats) {
        byKey.clear();
        Room room = room("owner", player("owner", s[0], s[1]));
        GameService service = gameService(rules);

        boolean eligible = service.checkWinner(new WinnerRequest("owner")).eligibleToFinish();
        service.finish(new FinishRequest("owner"));

        assertThat(eligible)
            .as(
                String.format(
                    "nguong %d vong / %d cau, nguoi choi %d vong / %d cau",
                    rules.getWinMinLaps(), rules.getWinMinAnswers(), s[0], s[1]))
            .isEqualTo("owner".equals(room.getWinnerKey()));
      }
    }
  }

  // ── Người đã rời phòng: đủ ngưỡng cũng không phải người thắng ───────────────

  @Test
  void nguoiDaRoiPhongDuNguongVanKhongPhaiNguoiThang() {
    // Đúng đường đi thật: RoomService chỉ setLeft(true), KHÔNG gỡ khỏi
    // room.getPlayers(), nên người đã rời vẫn đứng đầu danh sách và vẫn giữ
    // nguyên completedLaps/totalAnswered.
    Player daRoi = player("guest-1", 1, 12);
    daRoi.setLeft(true);
    Room room = room("owner", daRoi, player("owner", 1, 12));
    GameService service = gameService(RULES);

    service.finish(new FinishRequest("owner"));

    // Người còn lại đủ ngưỡng thì được phong thắng.
    assertThat(room.getWinnerKey()).isEqualTo("owner");
  }

  @Test
  void aiCungDaRoiPhongThiVanKetThucDuocNhungKhongCoNguoiThang() {
    Player ownerDaRoi = player("owner", 1, 12);
    ownerDaRoi.setLeft(true);
    Player guestDaRoi = player("guest-1", 2, 30);
    guestDaRoi.setLeft(true);
    Room room = room("owner", ownerDaRoi, guestDaRoi);
    GameService service = gameService(RULES);

    service.finish(new FinishRequest("owner"));

    assertThat(room.getStatus()).isEqualTo(RoomStatus.FINISHED);
    assertThat(room.getWinnerKey()).isNull();
  }

  @Test
  void nguoiDaRoiKhongDuocBaoDuDieuKien() {
    Player daRoi = player("guest-1", 1, 12);
    daRoi.setLeft(true);
    byKey.put("guest-1", daRoi);

    assertThat(gameService(RULES).checkWinner(new WinnerRequest("guest-1")).eligibleToFinish())
        .isFalse();
  }

  // ── Vòng đời phòng: bắt đầu ván thì phòng chuyển sang PLAYING ────────────

  @Test
  void phongBatDauVanThiChuyenSangPlaying() {
    Room room = room("owner", player("owner", 0, 0), player("guest-1", 0, 0));
    RoomRepository rooms = mock(RoomRepository.class);
    when(rooms.findByCode("H12345")).thenReturn(Optional.of(room));
    RoomService service =
        new RoomService(
            rooms,
            mock(PlayerRepository.class),
            mock(GameBroadcaster.class),
            new GameSnapshotBuilder(RULES),
            mock(GameService.class),
            mock(TurnCoordinator.class),
            mock(PresenceService.class),
            RULES);

    service.start("H12345");

    assertThat(room.getStatus()).isEqualTo(RoomStatus.PLAYING);
  }

  // ── Chụp điểm RIASEC lúc kết thúc ván ───────────────────────────────────

  /**
   * Điểm RIASEC chỉ sống trong bảng {@code scores}, mà bảng đó bị {@code
   * RoomJanitor} xoá theo phòng sau {@code finishedRoomTtlMin} (xoá Room →
   * cascade Player → cascade Score). Báo cáo Holland bán 50.000đ đọc điểm ở
   * thời điểm tạo đơn, nên phải chụp vào chính phòng NGAY lúc kết thúc ván.
   */
  @Test
  void ketThucVanLuuDiemRiasecCuaMoiNguoi() {
    Room room =
        room(
            "owner",
            scoredPlayer("owner", Map.of(RiasecCategory.R, 3, RiasecCategory.I, 5)),
            scoredPlayer("guest-1", Map.of(RiasecCategory.I, 2, RiasecCategory.C, 7)));
    GameService service = gameService(RULES);

    service.finish(new FinishRequest("owner"));

    // Cộng dồn của MỌI người chơi: I = 5 + 2 = 7, không phải của riêng owner.
    assertThat(room.getRiasecSnapshot())
        .containsEntry("R", 3)
        .containsEntry("I", 7)
        .containsEntry("C", 7)
        .containsEntry("A", 0);
    assertThat(room.getRiasecSnapshot()).hasSize(RiasecCategory.values().length);
  }

  @Test
  void phongBiXoaThiDiemVanCon() {
    Room room =
        room(
            "owner",
            scoredPlayer("owner", Map.of(RiasecCategory.R, 4, RiasecCategory.S, 6)),
            player("guest-1", 0, 0));
    GameService service = gameService(RULES);
    service.finish(new FinishRequest("owner"));

    // Mô phỏng RoomJanitor dọn phòng hết hạn: xoá Room → cascade Player →
    // cascade Score. Ảnh chụp nằm ở bảng riêng nên không bị mất theo.
    room.getPlayers().clear();

    assertThat(room.isFinished()).isTrue();
    assertThat(room.getRiasecSnapshot()).containsEntry("R", 4).containsEntry("S", 6);
  }

  @Test
  void chetDiemLaiKhongCongDiton() {
    Room room = room("owner", scoredPlayer("owner", Map.of(RiasecCategory.R, 5)));
    GameService service = gameService(RULES);

    service.finish(new FinishRequest("owner"));
    service.finish(new FinishRequest("owner")); // retry mạng / bấm 2 nút

    assertThat(room.getRiasecSnapshot()).containsEntry("R", 5);
  }

  @Test
  void phongChuaKetThucVanThiChuaChupDiem() {
    Room room = room("owner", scoredPlayer("owner", Map.of(RiasecCategory.R, 5)));

    assertThat(room.getRiasecSnapshot()).isEmpty();
  }

  @Test
  void goiKhongDuocQuyenThiKhongChupDiem() {
    Room room =
        room(
            "owner",
            scoredPlayer("owner", Map.of(RiasecCategory.R, 5)),
            scoredPlayer("guest-1", Map.of(RiasecCategory.R, 9)));
    GameService service = gameService(RULES);

    assertThatThrownBy(() -> service.finish(new FinishRequest("guest-1")))
        .satisfies(GameFinishTest::assertForbidden);

    // Chụp điểm phải nằm SAU kiểm tra quyền chủ phòng: lời gọi bị chặn thì
    // phòng chưa kết thúc và không được chụp hộ.
    assertThat(room.getRiasecSnapshot()).isEmpty();
  }
}
