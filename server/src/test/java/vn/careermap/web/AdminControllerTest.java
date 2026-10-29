package vn.careermap.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import vn.careermap.domain.User;
import vn.careermap.domain.UserRole;
import vn.careermap.web.dto.ChangeRoleRequest;
import vn.careermap.web.dto.PaymentSummary;
import vn.careermap.web.dto.RoomSummary;
import vn.careermap.web.dto.UserSummary;

class AdminControllerTest {

  private final UserServiceStub service = new UserServiceStub();

  @Test
  void chuaCauHinhSecretTraVe503() {
    AdminController c = new AdminController(service);
    assertThat(c.listUsers(null, null).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(c.changeRole("abc", new ChangeRoleRequest("ADMIN"), null, null).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(c.listActiveRooms(null, null).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(c.listPayments(null, null).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void saiSecretTraVe403() {
    AdminController c = new AdminController(service, "mat-khau-dung");
    assertThat(c.listUsers("sai", "uid-admin").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void dungSecretNhungKhongCoUidTraVe403() {
    AdminController c = new AdminController(service, "mat-khau-dung");
    assertThat(c.listUsers("mat-khau-dung", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void uidLaUserGoiUsersTraVe403() {
    AdminController c = new AdminController(service, "mat-khau-dung");
    assertThat(c.listUsers("mat-khau-dung", "uid-user").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void uidLaAdminGoiUsersTraVe200() {
    AdminController c = new AdminController(service, "mat-khau-dung");
    ResponseEntity<List<UserSummary>> res = c.listUsers("mat-khau-dung", "uid-admin");
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody()).hasSize(2);
  }

  @Test
  void uidLaAdminDoiRoleThanhCong() {
    AdminController c = new AdminController(service, "mat-khau-dung");
    ResponseEntity<UserSummary> res =
        c.changeRole("uid-1", new ChangeRoleRequest("ACCOUNTANT"), "mat-khau-dung", "uid-admin");
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody().role()).isEqualTo("ACCOUNTANT");
  }

  @Test
  void uidLaUserDoiRoleTraVe403() {
    AdminController c = new AdminController(service, "mat-khau-dung");
    assertThat(c.changeRole("uid-1", new ChangeRoleRequest("ADMIN"), "mat-khau-dung", "uid-user")
            .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void uidLaAccountantGoiPaymentsTraVe200() {
    AdminController c = new AdminController(service, "mat-khau-dung");
    ResponseEntity<List<PaymentSummary>> res = c.listPayments("mat-khau-dung", "uid-accountant");
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody()).hasSize(1);
    assertThat(res.getBody().get(0).status()).isEqualTo("PAID");
  }

  @Test
  void uidLaAdminGoiPaymentsTraVe403() {
    AdminController c = new AdminController(service, "mat-khau-dung");
    assertThat(c.listPayments("mat-khau-dung", "uid-admin").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void uidLaAccountantGoiUsersTraVe403() {
    AdminController c = new AdminController(service, "mat-khau-dung");
    assertThat(c.listUsers("mat-khau-dung", "uid-accountant").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void danhSachPhongChiAdminXemDuoc() {
    AdminController c = new AdminController(service, "mat-khau-dung");
    assertThat(c.listActiveRooms("mat-khau-dung", "uid-admin").getBody()).hasSize(1);
    assertThat(c.listActiveRooms("mat-khau-dung", "uid-accountant").getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  // ── Stub ──────────────────────────────────────────────────────────────────

  static class UserServiceStub extends vn.careermap.service.UserService {
    private final Map<String, UserRole> roles = new HashMap<>();

    UserServiceStub() {
      super(null, null, null, null);
      roles.put("uid-admin", UserRole.ADMIN);
      roles.put("uid-accountant", UserRole.ACCOUNTANT);
      roles.put("uid-user", UserRole.USER);
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
          new PaymentSummary("order-1", "uid-1", "A1B2", 50000, "PAID", "2026-10-01", "2026-10-01T10:00:00Z"));
    }

    @Override
    public vn.careermap.domain.User findByFirebaseUid(String firebaseUid) {
      UserRole role = roles.get(firebaseUid);
      if (role == null) return null;
      vn.careermap.domain.User u =
          new vn.careermap.domain.User(firebaseUid, "x@y.com", "X", null);
      u.setRole(role);
      return u;
    }
  }
}
