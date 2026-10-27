package vn.careermap.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Hồ sơ người dùng — lưu trong DB game để liên kết tài khoản Firebase với
 * dữ liệu game (phòng, người chơi, thanh toán).
 *
 * <p>Tài khoản xác thực (email/mật khẩu, Google) do Firebase Auth quản lý;
 * bản ghi này là bản sao hồ sơ (best-effort) để game có thể tra cứu theo
 * {@code firebaseUid}. Không phải nguồn sự thật cho mật khẩu.
 */
@Entity
@Table(name = "users")
public class User {

  /** Khóa chính = Firebase UID (duy nhất, không đổi). */
  @Id
  @Column(nullable = false, unique = true, length = 128)
  private String firebaseUid;

  @Column(length = 255)
  private String email;

  @Column(length = 255)
  private String displayName;

  @Column(length = 1024)
  private String photoURL;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected User() {
    // JPA
  }

  public User(String firebaseUid, String email, String displayName, String photoURL) {
    this.firebaseUid = firebaseUid;
    this.email = email;
    this.displayName = displayName;
    this.photoURL = photoURL;
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  public String getFirebaseUid() {
    return firebaseUid;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getPhotoURL() {
    return photoURL;
  }

  public void setPhotoURL(String photoURL) {
    this.photoURL = photoURL;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void touch() {
    this.updatedAt = Instant.now();
  }
}
