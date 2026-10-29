package vn.careermap.web;

import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.careermap.domain.User;
import vn.careermap.domain.UserRole;
import vn.careermap.service.UserService;
import vn.careermap.web.dto.ChangeRoleRequest;
import vn.careermap.web.dto.PaymentSummary;
import vn.careermap.web.dto.RoomSummary;
import vn.careermap.web.dto.UserSummary;

/**
 * API quản trị hệ thống.
 *
 * <p>Phân quyền theo nguyên tắc kinh doanh:
 * <ul>
 *   <li>{@link UserRole#ADMIN}: quản lý người dùng và phòng đang chơi,
 *       <b>KHÔNG</b> xem doanh thu (giao dịch thanh toán).
 *   <li>{@link UserRole#ACCOUNTANT}: xem giao dịch thanh toán (doanh thu),
 *       <b>KHÔNG</b> quản lý người dùng/phòng.
 * </ul>
 *
 * <p>Tất cả endpoint yêu cầu header {@code X-Admin-Secret} khớp với biến
 * môi trường {@code ADMIN_SECRET_KEY} và header {@code X-Admin-Firebase-Uid}
 * để xác định vai trò người gọi. Nếu biến secret chưa được set, mọi request
 * admin đều bị chặn.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

  private final UserService userService;

  @Value("${app.admin-secret-key:}")
  private String adminSecret;

  public AdminController(UserService userService) {
    this.userService = userService;
  }

  AdminController(UserService userService, String adminSecret) {
    this.userService = userService;
    this.adminSecret = adminSecret;
  }

  /** Kiểm tra secret + vai trò người gọi. Trả null nếu OK, ResponseEntity lỗi nếu không. */
  private ResponseEntity<Void> checkAccess(
      String secret, String adminUid, Set<UserRole> allowedRoles) {
    if (adminSecret == null || adminSecret.isBlank()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    if (secret == null || !secret.equals(adminSecret)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    if (adminUid == null || adminUid.isBlank()) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    User caller = userService.findByFirebaseUid(adminUid);
    if (caller == null || caller.getRole() == null) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    if (!allowedRoles.contains(caller.getRole())) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    return null;
  }

  private UserSummary toSummary(User u) {
    return new UserSummary(
        u.getFirebaseUid(),
        u.getEmail(),
        u.getDisplayName(),
        u.getRole() == null ? "USER" : u.getRole().name(),
        u.getCreatedAt().toString());
  }

  /** Danh sách tất cả người dùng — chỉ ADMIN. */
  @GetMapping("/users")
  public ResponseEntity<List<UserSummary>> listUsers(
      @RequestHeader(name = "X-Admin-Secret", required = false) String secret,
      @RequestHeader(name = "X-Admin-Firebase-Uid", required = false) String adminUid) {
    ResponseEntity<Void> check = checkAccess(secret, adminUid, Set.of(UserRole.ADMIN));
    if (check != null) return ResponseEntity.status(check.getStatusCode()).build();
    return ResponseEntity.ok(userService.listUsers());
  }

  /** Thay đổi vai trò (USER <-> ADMIN <-> ACCOUNTANT) — chỉ ADMIN. */
  @PutMapping("/users/{firebaseUid}/role")
  public ResponseEntity<UserSummary> changeRole(
      @PathVariable String firebaseUid,
      @RequestBody ChangeRoleRequest request,
      @RequestHeader(name = "X-Admin-Secret", required = false) String secret,
      @RequestHeader(name = "X-Admin-Firebase-Uid", required = false) String adminUid) {
    ResponseEntity<Void> check = checkAccess(secret, adminUid, Set.of(UserRole.ADMIN));
    if (check != null) return ResponseEntity.status(check.getStatusCode()).build();
    if (request == null || request.role() == null || request.role().isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    try {
      UserSummary updated = toSummary(userService.changeRole(firebaseUid, request.role()));
      return ResponseEntity.ok(updated);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().build();
    }
  }

  /** Danh sách phòng ĐANG CHƠI — chỉ ADMIN. */
  @GetMapping("/rooms")
  public ResponseEntity<List<RoomSummary>> listActiveRooms(
      @RequestHeader(name = "X-Admin-Secret", required = false) String secret,
      @RequestHeader(name = "X-Admin-Firebase-Uid", required = false) String adminUid) {
    ResponseEntity<Void> check = checkAccess(secret, adminUid, Set.of(UserRole.ADMIN));
    if (check != null) return ResponseEntity.status(check.getStatusCode()).build();
    return ResponseEntity.ok(userService.listActiveRooms());
  }

  /** Danh sách tất cả giao dịch thanh toán (mới nhất trước). KHÔNG chứa riasecJson. Chỉ ACCOUNTANT. */
  @GetMapping("/payments")
  public ResponseEntity<List<PaymentSummary>> listPayments(
      @RequestHeader(name = "X-Admin-Secret", required = false) String secret,
      @RequestHeader(name = "X-Admin-Firebase-Uid", required = false) String adminUid) {
    ResponseEntity<Void> check = checkAccess(secret, adminUid, Set.of(UserRole.ACCOUNTANT));
    if (check != null) return ResponseEntity.status(check.getStatusCode()).build();
    return ResponseEntity.ok(userService.listPayments());
  }
}
