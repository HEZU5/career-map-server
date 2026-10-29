package vn.careermap.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import vn.careermap.web.dto.ChangeRoleRequest;
import vn.careermap.web.dto.PaymentSummary;
import vn.careermap.web.dto.RoomSummary;
import vn.careermap.web.dto.UserSummary;

class AdminControllerTest {

  private final UserServiceStub service = new UserServiceStub();
  private final AdminController controllerNoSecret = new AdminController(service);

  @Test
  void chuaCauHinhSecretTraVe503() {
    // AdminController đọc admin-secret-key từ env; trong test env không set => null => 503.
    ResponseEntity<List<UserSummary>> res = controllerNoSecret.listUsers(null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

    ResponseEntity<UserSummary> roleRes =
        controllerNoSecret.changeRole("abc", new ChangeRoleRequest("ADMIN"), null);
    assertThat(roleRes.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

    assertThat(controllerNoSecret.listActiveRooms(null).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(controllerNoSecret.listPayments(null).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void saiSecretTraVe403() {
    AdminController c = new AdminController(service, "mat-khau-dung");
    ResponseEntity<List<UserSummary>> res = c.listUsers("mat-khau-sai");
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void dungSecretLayDanhSachUsers() {
    AdminController c = new AdminController(service, "mat-khau-dung");
    ResponseEntity<List<UserSummary>> res = c.listUsers("mat-khau-dung");
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody()).hasSize(2);
  }

  @Test
  void dungSecretDoiRoleThanhCong() {
    AdminController c = new AdminController(service, "mat-khau-dung");
    ResponseEntity<UserSummary> res =
        c.changeRole("uid-1", new ChangeRoleRequest("ADMIN"), "mat-khau-dung");
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody().role()).isEqualTo("ADMIN");
  }

  @Test
  void roleSaiTraVe400() {
    AdminController c = new AdminController(service, "mat-khau-dung");
    ResponseEntity<UserSummary> res =
        c.changeRole("uid-1", new ChangeRoleRequest("SUPER_ADMIN"), "mat-khau-dung");
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void danhSachPhongVaGiaoDich() {
    AdminController c = new AdminController(service, "mat-khau-dung");
    assertThat(c.listActiveRooms("mat-khau-dung").getBody()).hasSize(1);
    assertThat(c.listPayments("mat-khau-dung").getBody()).hasSize(1);
    assertThat(c.listPayments("mat-khau-dung").getBody().get(0).status()).isEqualTo("PAID");
  }

  // ── Stub để không cần Spring context/DB ──────────────────────────────────

  static class UserServiceStub extends vn.careermap.service.UserService {
    UserServiceStub() {
      super(null, null, null, null);
    }

    @Override
    public List<UserSummary> listUsers() {
      return List.of(
          new UserSummary("uid-1", "a@x.com", "Nguyen Van A", "USER", "2026-10-01"),
          new UserSummary("uid-2", "b@x.com", "Tran Thi B", "ADMIN", "2026-10-02"));
    }

    @Override
    public vn.careermap.domain.User changeRole(String firebaseUid, String roleName) {
      vn.careermap.domain.User u =
          new vn.careermap.domain.User(firebaseUid, "x@y.com", "X", null);
      u.setRole(vn.careermap.domain.UserRole.valueOf(roleName));
      return u;
    }

    @Override
    public List<RoomSummary> listActiveRooms() {
      return List.of(new RoomSummary("A1B2", "PLAYING", "Nguyen Van A", 2, null, "2026-10-01"));
    }

    @Override
    public List<PaymentSummary> listPayments() {
      return List.of(
          new PaymentSummary(
              "order-1", "uid-1", "A1B2", 50000, "PAID", "2026-10-01", "2026-10-01T10:00:00Z"));
    }
  }
}
