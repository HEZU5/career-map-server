package vn.careermap.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Luật suy ra hiện diện từ mốc {@code lastSeen}.
 *
 * <p>Đây là trái tim của phần "mất kết nối vs chủ động rời": mọi ngưỡng đều suy ra
 * từ heartbeat, không có tín hiệu nào từ client nói "tôi đi rồi".
 */
class PlayerPresenceTest {
  private static final long OFFLINE_AFTER = 15;
  private static final long AWAY_AFTER = 120;

  private static Player playerSeenAgo(long seconds) {
    Player player = Player.create("p1", "An");
    player.setLastSeen(Instant.now().minusSeconds(seconds));
    return player;
  }

  @Test
  void vuaMoiVaoPhongChuaCoHeartbeatVanLaOnline() {
    Player player = Player.create("p1", "An");
    assertThat(player.presenceAt(Instant.now(), OFFLINE_AFTER, AWAY_AFTER))
        .isEqualTo(PresenceStatus.ONLINE);
  }

  @Test
  void vuaGuiHeartbeatLaOnline() {
    Player player = playerSeenAgo(3);
    assertThat(player.presenceAt(Instant.now(), OFFLINE_AFTER, AWAY_AFTER))
        .isEqualTo(PresenceStatus.ONLINE);
  }

  @Test
  void quaNguongOfflineThanhOffline() {
    assertThat(playerSeenAgo(20).presenceAt(Instant.now(), OFFLINE_AFTER, AWAY_AFTER))
        .isEqualTo(PresenceStatus.OFFLINE);
  }

  @Test
  void quaNguongAwayThanhAway() {
    assertThat(playerSeenAgo(150).presenceAt(Instant.now(), OFFLINE_AFTER, AWAY_AFTER))
        .isEqualTo(PresenceStatus.AWAY);
  }

  @Test
  void nguoiDaChuDongRoiLuonLaLeftKhongPhaiOffline() {
    Player player = playerSeenAgo(1);
    player.setLeft(true);
    assertThat(player.presenceAt(Instant.now(), OFFLINE_AFTER, AWAY_AFTER))
        .isEqualTo(PresenceStatus.LEFT);
  }

  @Test
  void vangMatDauTinhTuLucMatKetNoiChuaPhaiVangMat() {
    Player player = playerSeenAgo(30);
    assertThat(player.isAway(null, Instant.now(), AWAY_AFTER)).isFalse();
    assertThat(player.isDisconnected(null, Instant.now(), OFFLINE_AFTER)).isTrue();
  }

  @Test
  void vangMatDaVuotNguongThiTinhSangVangMat() {
    Player player = playerSeenAgo(200);
    assertThat(player.isAway(null, Instant.now(), AWAY_AFTER)).isTrue();
  }

  @Test
  void phongCuKhongCoHeartbeatDungLastActivityLamMocThayThe() {
    // Phòng tạo trước khi có cột last_seen: nếu không có mốc dự phòng thì
    // chúng sẽ "online" vĩnh viễn và không bao giờ được dọn.
    Room room = Room.create("H12345", "An", false, "p1");
    room.addPlayer(Player.create("p1", "An"));
    Player legacy = room.findPlayer("p1");

    Instant stale = Instant.now().minusSeconds(500);
    assertThat(legacy.presenceAt(Instant.now(), OFFLINE_AFTER, AWAY_AFTER, stale))
        .isEqualTo(PresenceStatus.AWAY);
  }
}