package vn.careermap.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.careermap.service.UserService;
import vn.careermap.web.dto.SyncUserRequest;

/**
 * Endpoint đồng bộ hồ sơ người dùng (Firebase Auth → DB game).
 *
 * <p>Best-effort: client gọi sau khi đăng nhập; lỗi DB không chặn đăng nhập.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

  private final UserService userService;

  public UserController(UserService userService) {
    this.userService = userService;
  }

  @PostMapping("/sync")
  public ResponseEntity<Void> sync(@RequestBody SyncUserRequest request) {
    if (request == null || request.firebaseUid() == null || request.firebaseUid().isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    userService.syncProfile(
        request.firebaseUid(),
        request.email(),
        request.displayName(),
        request.photoURL());
    return ResponseEntity.ok().build();
  }

  @GetMapping("/{firebaseUid}")
  public ResponseEntity<SyncUserRequest> get(@PathVariable String firebaseUid) {
    var user = userService.findByFirebaseUid(firebaseUid);
    if (user == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(new SyncUserRequest(
        user.getFirebaseUid(),
        user.getEmail(),
        user.getDisplayName(),
        user.getPhotoURL(),
        user.getRole() == null ? "USER" : user.getRole().name()));
  }
}
