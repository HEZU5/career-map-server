package vn.careermap.service;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.careermap.domain.PaymentOrder;
import vn.careermap.domain.PaymentStatus;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.domain.RoomStatus;
import vn.careermap.domain.User;
import vn.careermap.domain.UserRole;
import vn.careermap.repo.PaymentOrderRepository;
import vn.careermap.repo.PlayerRepository;
import vn.careermap.repo.RoomRepository;
import vn.careermap.repo.UserRepository;
import vn.careermap.web.dto.PaymentSummary;
import vn.careermap.web.dto.RoomSummary;
import vn.careermap.web.dto.UserSummary;

/**
 * Đồng bộ hồ sơ người dùng (Firebase Auth → DB game) và các thao tác quản trị.
 *
 * <p>Best-effort: lỗi DB không được chặn đăng nhập (giống Firestore profile).
 *
 * <p>Các method quản trị yêu cầu caller đã xác thực là ADMIN (xem
 * {@link vn.careermap.web.AdminController}).
 */
@Service
public class UserService {

  private final UserRepository userRepository;
  private final RoomRepository roomRepository;
  private final PlayerRepository playerRepository;
  private final PaymentOrderRepository paymentOrderRepository;

  public UserService(
      UserRepository userRepository,
      RoomRepository roomRepository,
      PlayerRepository playerRepository,
      PaymentOrderRepository paymentOrderRepository) {
    this.userRepository = userRepository;
    this.roomRepository = roomRepository;
    this.playerRepository = playerRepository;
    this.paymentOrderRepository = paymentOrderRepository;
  }

  /**
   * Tạo hoặc cập nhật hồ sơ người dùng theo Firebase UID.
   *
   * <p>Nếu đã tồn tại thì cập nhật email/displayName/photoURL + updatedAt;
   * nếu chưa thì tạo mới với role {@link UserRole#USER}.
   */
  @Transactional
  public User syncProfile(String firebaseUid, String email, String displayName, String photoURL) {
    if (firebaseUid == null || firebaseUid.isBlank()) {
      throw new IllegalArgumentException("firebaseUid không được rỗng");
    }
    User existing = userRepository.findById(firebaseUid).orElse(null);
    if (existing == null) {
      User created = new User(firebaseUid, email, displayName, photoURL);
      return userRepository.save(created);
    }
    existing.setEmail(email);
    existing.setDisplayName(displayName);
    existing.setPhotoURL(photoURL);
    existing.touch();
    return userRepository.save(existing);
  }

  public User findByFirebaseUid(String firebaseUid) {
    return userRepository.findById(firebaseUid).orElse(null);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // QUẢN TRỊ
  // ─────────────────────────────────────────────────────────────────────────────

  /** Lấy danh sách tất cả người dùng (dùng cho trang quản trị). */
  @Transactional(readOnly = true)
  public List<UserSummary> listUsers() {
    return userRepository.findAll().stream()
        .map(u -> new UserSummary(
            u.getFirebaseUid(),
            u.getEmail(),
            u.getDisplayName(),
            u.getRole() == null ? "USER" : u.getRole().name(),
            u.getCreatedAt().toString()))
        .collect(Collectors.toList());
  }

  /** Thay đổi vai trò của một người dùng (USER ↔ ADMIN). */
  @Transactional
  public User changeRole(String firebaseUid, String roleName) {
    if (firebaseUid == null || firebaseUid.isBlank()) {
      throw new IllegalArgumentException("firebaseUid không được rỗng");
    }
    UserRole role;
    try {
      role = UserRole.valueOf(roleName);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Vai trò không hợp lệ: " + roleName);
    }
    User existing = userRepository.findById(firebaseUid).orElse(null);
    if (existing == null) {
      throw new IllegalArgumentException("Không tìm thấy người dùng: " + firebaseUid);
    }
    existing.setRole(role);
    existing.touch();
    return userRepository.save(existing);
  }

  /** Lấy danh sách phòng ĐANG CHƠI (PLAYING) để quản trị theo dõi. */
  @Transactional(readOnly = true)
  public List<RoomSummary> listActiveRooms() {
    return roomRepository.findByActiveTrue().stream()
        .map(r -> new RoomSummary(
            r.getCode(),
            r.getStatus().name(),
            r.getHostName(),
            r.getPlayers() == null ? 0 : r.getPlayers().size(),
            r.getWinnerKey(),
            r.getCreatedAt().toString()))
        .collect(Collectors.toList());
  }

  /** Lấy danh sách tất cả giao dịch thanh toán (mới nhất trước).
   *
   * <p>KHÔNG chứa riasecJson để đảm bảo bảo mật thông tin khách hàng. */
  @Transactional(readOnly = true)
  public List<PaymentSummary> listPayments() {
    return paymentOrderRepository.findAll().stream()
        .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
        .map(p -> new PaymentSummary(
            p.getId(),
            p.getPlayerKey(),
            p.getRoomCode(),
            p.getAmount(),
            p.getStatus().name(),
            p.getCreatedAt().toString(),
            p.getPaidAt() == null ? null : p.getPaidAt().toString()))
        .collect(Collectors.toList());
  }
}
