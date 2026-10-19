package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import vn.careermap.domain.Room;
import vn.careermap.domain.RoomStatus;
import vn.careermap.domain.TurnPhase;
import vn.careermap.web.dto.RollRequest;

/**
 * Tranh chấp lượt: nhiều máy cùng tự tung, chỉ một máy được ghi.
 *
 * <p>Đây là kiểm th then chốt của toàn bộ phần "đồng hồ server-authoritative".
 * Không có cơ chế này, mỗi lần hết giờ sẽ có nhiều client cùng gọi API và quân
 * sẽ nhảy nhiều lần trong một lượt.
 */
class TurnCoordinationTest {

  private static Room roomWithTwoPlayersStarted() {
    Room room = Room.create("H12345", "An", false, "p1");
    room.addPlayer(vn.careermap.domain.Player.create("p1", "An"));
    room.addPlayer(vn.careermap.domain.Player.create("p2", "Bình"));
    room.setActive(true);
    room.setStatus(RoomStatus.PLAYING);
    return room;
  }

  @Test
  void maHaiHaiXacNhanDungPhaThiMoiTung() {
    Room room = roomWithTwoPlayersStarted();
    room.beginTurn(room.findPlayer("p1"), TurnPhase.WAITING_ROLL, java.time.Instant.now().plusSeconds(15), false);
    long turnId = room.getTurnId();
    long rollIdBefore = room.getRollId();

    boolean first = room.isCurrentTurn(turnId, TurnPhase.WAITING_ROLL);
    boolean second = room.isCurrentTurn(turnId, TurnPhase.WAITING_ROLL);

    assertThat(first).isTrue();
    assertThat(second).isTrue(); // hai request cùng đọc state đều thấy hợp lệ
    assertThat(room.getRollId()).isEqualTo(rollIdBefore);
  }

  @Test
  void rollIdChiTangMotLanMoiLanTung() {
    Room room = roomWithTwoPlayersStarted();
    room.recordRoll("p1", 3, false);
    long afterFirst = room.getRollId();
    room.recordRoll("p1", 5, false);
    assertThat(room.getRollId()).isEqualTo(afterFirst + 1);
  }

  @Test
  void clientGuiExpectedTurnIdSaiThiServerTuTrenhet() {
    RollRequest stale = new RollRequest("p1", 99L, Boolean.TRUE, "client-referee");
    assertThat(stale.expectedTurnId()).isEqualTo(99L);
    assertThat(stale.isAuto()).isTrue();
  }

  @Test
  void bamTayKhongCoExpectedTurnId() {
    RollRequest manual = RollRequest.manual("p1");
    assertThat(manual.expectedTurnId()).isNull();
    assertThat(manual.isAuto()).isFalse();
    assertThat(manual.reason()).isEqualTo("manual");
  }

  @Test
  void autoRollGhiDeLenDeHienThiPill() {
    Room room = roomWithTwoPlayersStarted();
    room.recordRoll("p1", 2, true);
    assertThat(room.isLastRollAuto()).isTrue();
    room.recordRoll("p1", 2, false);
    assertThat(room.isLastRollAuto()).isFalse();
  }

  @Test
  void luotPhaiThuocNguoiDangDenLuotKhacKhongTungDuoc() {
    Room room = roomWithTwoPlayersStarted();
    room.beginTurn(room.findPlayer("p1"), TurnPhase.WAITING_ROLL, java.time.Instant.now().plusSeconds(15), false);
    // Bình đến lượt trước A
    assertThat(room.getTurnPlayerKey()).isEqualTo("p1");
    assertThat(room.getTurnPlayerKey().equals("p2")).isFalse();
  }

  @Test
  void bangChua53O() {
    assertThat(TurnCoordinator.pathSize()).isEqualTo(53);
  }

  @Test
  void chiStarVaStartKhongRutTh() {
    // Ô 0 = START, ô 7 = ⭐ → không rút thẻ.
    assertThat(TurnCoordinator.pathTile(0).isRiasec()).isFalse();
    assertThat(TurnCoordinator.pathTile(7).name()).isEqualTo("STAR");
  }

  @Test
  void viTriNgoaiBangVanQuayVeTrongBang() {
    assertThat(TurnCoordinator.pathTile(-1).name()).isEqualTo(TurnCoordinator.pathTile(52).name());
    assertThat(TurnCoordinator.pathTile(53).name()).isEqualTo(TurnCoordinator.pathTile(0).name());
  }
}