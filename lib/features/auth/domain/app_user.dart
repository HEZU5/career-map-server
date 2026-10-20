/// Mô hình người dùng dùng trong toàn app sau khi đăng nhập.
class AppUser {
  const AppUser({
    required this.uid,
    required this.email,
    this.displayName,
    this.photoURL,
  });

  final String uid;
  final String? email;
  final String? displayName;
  final String? photoURL;

  /// Tên hiển thị cho UI: ưu tiên displayName, rồi email, rồi "Người dùng".
  String get displayNameOrEmail {
    if (displayName != null && displayName!.isNotEmpty) return displayName!;
    if (email != null && email!.isNotEmpty) return email!;
    return 'Người dùng';
  }

  /// Chìa khoá ngắn dùng để lưu vào SessionStore / SharedPreferences.
  String get playerKey => uid;

  AppUser copyWith({
    String? uid,
    String? email,
    String? displayName,
    String? photoURL,
  }) {
    return AppUser(
      uid: uid ?? this.uid,
      email: email ?? this.email,
      displayName: displayName ?? this.displayName,
      photoURL: photoURL ?? this.photoURL,
    );
  }

  @override
  String toString() => 'AppUser(uid: $uid, email: $email)';

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is AppUser && runtimeType == other.runtimeType && uid == other.uid;

  @override
  int get hashCode => uid.hashCode;
}
