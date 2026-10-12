package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.domain.PresenceStatus;
import vn.careermap.domain.PresenceServiceProbe;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.domain.RoomStatus;
import vn.careermap.domain.TurnPhase;

/**
 * Luật vòng đời phòng và quyền chủ phòng.
 *
 * <p>Phần lớn test ở đây không cần database: phòng không có ai thật nên mọi phòng
 * đều "không còn ai online", nên có thể kiểm tra điều kiện xóa thuần logic.
 */
class RoomLifecycleTest {
  private static final GameRulesConfig RULES =
      new GameRulesConfig(15, 60, 120, 5, 15, 2, 10, 30, "skip");

  private static Room waitingRoom(String ownerKey) {
    Room room = Room.create("H12345", "Chủ", false, ownerKey);
    room.addPlayer(Player.create("owner", "Chủ"));
    room.addPlayer(Player.create("guest-1", "Khách"));
    return room;
  }

  @Test
  void chuPhongLaNguoiTaoPhong() {
    Room room = waitingRoom("owner");
    assertThat(room.getOwnerKey()).isEqualTo("owner");
    assertThat(room.isOwner("owner")).isTrue();
    assertThat(room.isOwner("guest-1")).isFalse();
  }

  @Test
  void chuPhongChuaVangMatThiKhongAiGiuQuyenTam() {
    Room room = waitingRoom("owner");
    // Đã có owner còn trong phòng → effectiveHost là owner, không cần acting host.
    assertThat(room.effectiveHostKey()).isEqualTo("owner");
  }

  @Test
  void chuPhongVangMatVaConNguoiOnlineThiQuyenChuyenChoNguoiVaoSomNhat() {
    Room room = waitingRoom("owner");
    room.addPlayer(Player.create("late", "Z"));
    // Chủ phòng đang offline (chưa từng heartbeat), còn hai người kia online.
    PresenceServiceProbe probe =
        (r, p, now) -> !p.getPlayerKey().equals("owner");

    room.transferHostTo(probe, Instant.now());

    // Người vào phòng sớm nhất được giữ quyền tạm, không phải người vào sau.
    assertThat(room.getActingHostKey()).isEqualTo("guest-1");
    assertThat(room.effectiveHostKey()).isEqualTo("guest-1");
  }

  @Test
  void chuPhongVanOnlineThiGiuQuyenChoMinh() {
    Room room = waitingRoom("owner");
    PresenceServiceProbe probe = (r, p, now) -> true;

    room.transferHostTo(probe, Instant.now());

    assertThat(room.getActingHostKey()).isNull();
    assertThat(room.effectiveHostKey()).isEqualTo("owner");
  }

  @Test
  void khongConAiOnlineThiKhongChuyenQuyen() {
    Room room = waitingRoom("owner");
    PresenceServiceProbe nobody = (r, p, now) -> false;

    room.transferHostTo(nobody, Instant.now());

    assertThat(room.getActingHostKey()).isNull();
  }

  @Test
  void chuPhongQuayLaiThiLayLaiQuyen() {
    Room room = waitingRoom("owner");
    room.setActingHostKey("guest-1");
    room.reclaimHost();
    assertThat(room.getActingHostKey()).isNull();
    assertThat(room.effectiveHostKey()).isEqualTo("owner");
  }

  @Test
  void moiPhongBatDauLaWaitingChuaCoLuot() {
    Room room = waitingRoom("owner");
    assertThat(room.getStatus()).isEqualTo(RoomStatus.WAITING);
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.NONE);
    assertThat(room.getTurnDeadlineAt()).isNull();
  }

  @Test
  void batDauLuotTangTurnIdVaDatHan() {
    Room room = waitingRoom("owner");
    long before = room.getTurnId();
    Instant deadline = Instant.now().plusSeconds(RULES.turnRollS);

    room.beginTurn(room.findPlayer("owner"), TurnPhase.WAITING_ROLL, deadline, false);

    assertThat(room.getTurnId()).isEqualTo(before + 1);
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.WAITING_ROLL);
    assertThat(room.getTurnDeadlineAt()).isEqualTo(deadline);
    assertThat(room.getTurnPlayerKey()).isEqualTo("owner");
  }

  @Test
  void lanTungSauKhacLungLuotThiCompareAndSetThatBai() {
    Room room = waitingRoom("owner");
    room.beginTurn(room.findPlayer("owner"), TurnPhase.WAITING_ROLL, Instant.now().plusSeconds(15), false);
    long turnId = room.getTurnId();

    // Máy trọng tài giữ turnId cũ, bấm trễ 1 nhịp sau khi đã có người tung.
    room.clearTurn();
    room.beginTurn(room.findPlayer("guest-1"), TurnPhase.WAITING_ROLL, Instant.now().plusSeconds(15), false);

    assertThat(room.isCurrentTurn(turnId, TurnPhase.WAITING_ROLL)).isFalse();
    assertThat(room.isCurrentTurn(room.getTurnId(), TurnPhase.WAITING_ROLL)).isTrue();
  }

  @Test
  void doiPhaCungLamCompareAndSetThatBai() {
    Room room = waitingRoom("owner");
    room.beginTurn(room.findPlayer("owner"), TurnPhase.WAITING_ROLL, Instant.now().plusSeconds(15), false);
    long turnId = room.getTurnId();
    assertThat(room.isCurrentTurn(turnId, TurnPhase.WAITING_ANSWER)).isFalse();
  }

  @Test
  void deadlineChuaToChuaHetHan() {
    Room room = waitingRoom("owner");
    room.beginTurn(room.findPlayer("owner"), TurnPhase.WAITING_ROLL, Instant.now().plusSeconds(15), false);
    assertThat(room.isDeadlinePassed(Instant.now())).isFalse();
    assertThat(room.isDeadlinePassed(Instant.now().plusSeconds(16))).isTrue();
  }

  @Test
  void tatPhongChoPhepKhongConLuotTreo() {
    Room room = waitingRoom("owner");
    room.beginTurn(room.findPlayer("owner"), TurnPhase.WAITING_ROLL, Instant.now().plusSeconds(15), false);
    room.clearTurn();
    assertThat(room.getTurnPhase()).isEqualTo(TurnPhase.NONE);
    assertThat(room.getTurnDeadlineAt()).isNull();
    // Không có deadline thì không bao giờ "hết hạn".
    assertThat(room.isDeadlinePassed(Instant.now().plus(Duration.ofDays(1)))).isFalse();
  }
}