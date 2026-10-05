package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;

/**
 * Luật dọn phòng bỏ hoang — không có thì lobby tích đầy phòng chết và danh
 * sách loạn.
 *
 * <p>Quan trọng nhất: KHÔNG xoá phòng đang có người thật, kể cả người đang tạm
 * thoát (F5, đóng tab). Chỉ xoá phòng chắc chắn bỏ hoang.
 *
 * <p>Gọi thẳng {@link RoomCleanupService#isStale} chứ không chép lại logic —
 * test phải kiểm chứng đúng code đang chạy.
 */
class RoomCleanupServiceTest {

  private final RoomCleanupService service = new RoomCleanupService(null);
  private final Instant now = Instant.parse("2026-10-05T12:00:00Z");

  private Room room(boolean active) {
    Room room = Room.create("H12345", "An", false);
    room.setActive(active);
    return room;
  }

  private void setCreated(Room room, Instant when) throws Exception {
    Field f = Room.class.getDeclaredField("createdAt");
    f.setAccessible(true);
    f.set(room, when);
  }

  private void setUpdated(Room room, Instant when) throws Exception {
    Field f = Room.class.getDeclaredField("updatedAt");
    f.setAccessible(true);
    f.set(room, when);
  }

  private void addPlayer(Room room, String key, boolean left) {
    Player p = Player.create(key, key);
    p.setLeft(left);
    room.addPlayer(p);
  }

  @Test
  @DisplayName("phòng không còn ai thì xoá")
  void emptyRoomIsStale() throws Exception {
    Room r = room(false);
    setCreated(r, now);
    assertThat(service.isStale(r, now)).isTrue();
  }

  @Test
  @DisplayName("phòng chờ có người vừa hoạt động thì giữ")
  void roomWithPeopleIsKept() throws Exception {
    Room r = room(false);
    setCreated(r, now.minusSeconds(1800));
    addPlayer(r, "guest-H1-1", false);
    assertThat(service.isStale(r, now)).isFalse();
  }

  @Test
  @DisplayName("mọi người đã bấm rời thì xoá")
  void allPlayersLeftIsStale() throws Exception {
    Room r = room(false);
    setCreated(r, now.minusSeconds(300));
    addPlayer(r, "guest-H1-1", true);
    addPlayer(r, "guest-H1-2", true);
    assertThat(service.isStale(r, now)).isTrue();
  }

  @Test
  @DisplayName("chỉ một người rời thì vẫn giữ phòng cho người còn lại")
  void onePlayerLeftStillKeepsRoom() throws Exception {
    Room r = room(false);
    setCreated(r, now.minusSeconds(300));
    addPlayer(r, "guest-H1-1", true);
    addPlayer(r, "guest-H1-2", false);
    assertThat(service.isStale(r, now)).isFalse();
  }

  @Test
  @DisplayName("phòng chờ bỏ trống quá 2 giờ thì xoá")
  void waitingRoomTooOldIsStale() throws Exception {
    Room r = room(false);
    setCreated(r, now.minusSeconds(3 * 3600));
    addPlayer(r, "guest-H1-1", false);
    assertThat(service.isStale(r, now)).isTrue();
  }

  @Test
  @DisplayName("phòng chờ tạo lâu rồi nhưng vừa có người vào thì giữ")
  void waitingRoomRecentlyActiveIsKept() throws Exception {
    Room r = room(false);
    setCreated(r, now.minusSeconds(5 * 3600));
    addPlayer(r, "guest-H1-1", false);
    setUpdated(r, now.minusSeconds(60));
    assertThat(service.isStale(r, now)).isFalse();
  }

  @Test
  @DisplayName("phòng đã bắt đầu, có người, im lặng quá 3 giờ thì xoá")
  void startedIdleRoomIsStale() throws Exception {
    Room r = room(true);
    setCreated(r, now.minusSeconds(4 * 3600));
    addPlayer(r, "guest-H1-1", false);
    assertThat(service.isStale(r, now)).isTrue();
  }

  @Test
  @DisplayName("phòng đang chơi, vừa có hoạt động thì giữ dù đã tạo lâu")
  void startedButActiveIsKept() throws Exception {
    Room r = room(true);
    setCreated(r, now.minusSeconds(4 * 3600));
    addPlayer(r, "guest-H1-1", false);
    setUpdated(r, now.minusSeconds(300));
    assertThat(service.isStale(r, now)).isFalse();
  }

  @Test
  @DisplayName("phòng cũ chưa có updated_at thì lùi về created_at")
  void oldRoomWithoutUpdatedAtFallsBackToCreatedAt() throws Exception {
    Room r = room(false);
    setCreated(r, now.minusSeconds(10 * 3600));
    assertThat(r.lastActivityAt()).isEqualTo(now.minusSeconds(10 * 3600));
    addPlayer(r, "guest-H1-1", false);
    assertThat(service.isStale(r, now)).isTrue();
  }

  @Test
  @DisplayName("phòng riêng tư bỏ hoang cũng phải bị dọn, không để lại rác")
  void privateStaleRoomIsAlsoRemoved() throws Exception {
    Room r = Room.create("H99999", "An", true);
    setCreated(r, now.minusSeconds(5 * 3600));
    addPlayer(r, "guest-H9-1", false);
    assertThat(service.isStale(r, now)).isTrue();
  }
}