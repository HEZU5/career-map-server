package vn.careermap.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.careermap.domain.User;
import vn.careermap.repo.UserRepository;

/**
 * Đồng bộ hồ sơ người dùng (Firebase Auth → DB game).
 *
 * <p>Best-effort: lỗi DB không được chặn đăng nhập (giống Firestore profile).
 */
@Service
public class UserService {

  private final UserRepository userRepository;

  public UserService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * Tạo hoặc cập nhật hồ sơ người dùng theo Firebase UID.
   *
   * <p>Nếu đã tồn tại thì cập nhật email/displayName/photoURL + updatedAt;
   * nếu chưa thì tạo mới.
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
}
