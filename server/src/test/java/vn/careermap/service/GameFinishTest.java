package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.domain.RoomStatus;
import vn.careermap.domain.TurnPhase;
import vn.careermap.repo.PlayerRepository;
import vn.careermap.repo.RoomRepository;
import vn.careermap.web.dto.FinishRequest;

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
}
