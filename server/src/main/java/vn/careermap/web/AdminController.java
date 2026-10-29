package vn.careermap.web;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.careermap.service.UserService;
import vn.careermap.web.dto.ChangeRoleRequest;
import vn.careermap.web.dto.PaymentSummary;
import vn.careermap.web.dto.RoomSummary;
import vn.careermap.web.dto.UserSummary;

/**
 * API quản trị hệ thống.
 *
 * <p>Tất cả endpoint đều yêu cầu header {@code X-Admin-Secret} khớp với biến
 * môi trường {@code ADMIN_SECRET_KEY}. Nếu biến này chưa được set, mọi request
 * admin đều bị chặn (tránh lộ dữ liệu khi chưa cấu hình).
 *
 * <p>Luồng triển khai:
 * <ol>
 *   <li>Người dùng đăng ký/đăng nhập qua Firebase như bình thường.</li>
 *   <li>Quản trị viên set {@code ADMIN_SECRET_KEY} trên Render.</li>
 *   <li>Trang admin Flutter nhập {@code X-Admin-Secret} để mở khóa các tab quản trị.</li>
 *   <li>Sau đó dùng endpoint {@code PUT /api/admin/users/{firebaseUid}/role}
 *       để gán vai trò ADMIN cho tài khoản người dùng mong muốn.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

  private final UserService userService;

  /** Chuỗi bí mật bảo vệ API quản trị (đọc từ env). Có thể null nếu chưa cấu hình. */
  @Value("${app.admin-secret-key:}")
  private String adminSecret;

  /** Constructor production: đọc secret từ env qua @Value. */
  public AdminController(UserService userService) {
    this.userService = userService;
  }

  /** Constructor test: gán secret thủ công (không qua Spring env). */
  AdminController(UserService userService, String adminSecret) {
    this.userService = userService;
    this.adminSecret = adminSecret;
  }

  /** Kiểm tra caller có quyền quản trị hay không. */
  private ResponseEntity<Void> requireAdmin(String secret) {
    if (adminSecret == null || adminSecret.isBlank()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build(); // chưa cấu hình
    }
    if (secret == null || !secret.equals(adminSecret)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    return null; // OK
  }

  /** Danh sách tất cả người dùng (dùng cho trang quản trị). */
  @GetMapping("/users")
  public ResponseEntity<List<UserSummary>> listUsers(
      @RequestHeader(name = "X-Admin-Secret", required = false) String secret) {
    ResponseEntity<Void> check = requireAdmin(secret);
    if (check != null) return ResponseEntity.status(check.getStatusCode()).build();
    return ResponseEntity.ok(userService.listUsers());
  }

  /** Thay đổi vai trò của một người dùng (USER ↔ ADMIN). */
  @PutMapping("/users/{firebaseUid}/role")
  public ResponseEntity<UserSummary> changeRole(
      @PathVariable String firebaseUid,
      @RequestBody ChangeRoleRequest request,
      @RequestHeader(name = "X-Admin-Secret", required = false) String secret) {
    ResponseEntity<Void> check = requireAdmin(secret);
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

  /** Danh sách phòng ĐANG CHƠI (PLAYING) để quản trị theo dõi. */
  @GetMapping("/rooms")
  public ResponseEntity<List<RoomSummary>> listActiveRooms(
      @RequestHeader(name = "X-Admin-Secret", required = false) String secret) {
    ResponseEntity<Void> check = requireAdmin(secret);
    if (check != null) return ResponseEntity.status(check.getStatusCode()).build();
    return ResponseEntity.ok(userService.listActiveRooms());
  }

  /** Danh sách tất cả giao dịch thanh toán (mới nhất trước). KHÔNG chứa riasecJson. */
  @GetMapping("/payments")
  public ResponseEntity<List<PaymentSummary>> listPayments(
      @RequestHeader(name = "X-Admin-Secret", required = false) String secret) {
    ResponseEntity<Void> check = requireAdmin(secret);
    if (check != null) return ResponseEntity.status(check.getStatusCode()).build();
    return ResponseEntity.ok(userService.listPayments());
  }

  private UserSummary toSummary(vn.careermap.domain.User u) {
    return new UserSummary(
        u.getFirebaseUid(),
        u.getEmail(),
        u.getDisplayName(),
        u.getRole() == null ? "USER" : u.getRole().name(),
        u.getCreatedAt().toString());
  }
}
