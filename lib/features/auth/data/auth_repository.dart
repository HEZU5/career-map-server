import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';

import '../domain/app_user.dart';

/// Thao tác xác thực người dùng.
abstract class AuthRepository {
  /// Người dùng hiện tại (null nếu chưa đăng nhập).
  AppUser? get currentUser;

  /// Stream lắng nghe thay đổi trạng thái đăng nhập.
  Stream<AppUser?> get authStateChanges;

  /// Đăng ký tài khoản mới.
  Future<AppUser> register({
    required String email,
    required String password,
    required String displayName,
  });

  /// Đăng nhập.
  Future<AppUser> login({required String email, required String password});

  /// Đăng xuất.
  Future<void> logout();

  /// Cập nhật tên hiển thị.
  Future<void> updateDisplayName(String name);

  /// Gửi email đặt lại mật khẩu.
  Future<void> sendPasswordReset(String email);
}

/// Triển khai bằng Firebase Auth + Firestore (lưu profile displayName/photoURL).
class FirebaseAuthRepository implements AuthRepository {
  FirebaseAuthRepository({
    FirebaseAuth? auth,
    FirebaseFirestore? firestore,
  })  : _auth = auth ?? FirebaseAuth.instance,
        _firestore = firestore ?? FirebaseFirestore.instance;

  final FirebaseAuth _auth;
  final FirebaseFirestore _firestore;

  static const _usersCollection = 'users';

  @override
  AppUser? get currentUser {
    final user = _auth.currentUser;
    if (user == null) return null;
    return _toAppUser(user);
  }

  @override
  Stream<AppUser?> get authStateChanges =>
      _auth.authStateChanges().map((user) {
        if (user == null) return null;
        return _toAppUser(user);
      });

  AppUser _toAppUser(User user) => AppUser(
        uid: user.uid,
        email: user.email,
        displayName: user.displayName,
        photoURL: user.photoURL,
      );

  @override
  Future<AppUser> register({
    required String email,
    required String password,
    required String displayName,
  }) async {
    final credential = await _auth.createUserWithEmailAndPassword(
      email: email,
      password: password,
    );
    final user = credential.user!;
    await user.updateDisplayName(displayName);
    await _firestore.collection(_usersCollection).doc(user.uid).set({
      'email': email,
      'displayName': displayName,
      'createdAt': FieldValue.serverTimestamp(),
    }, SetOptions(merge: true));
    return _toAppUser(user);
  }

  @override
  Future<AppUser> login({
    required String email,
    required String password,
  }) async {
    final credential = await _auth.signInWithEmailAndPassword(
      email: email,
      password: password,
    );
    final user = credential.user!;
    return _toAppUser(user);
  }

  @override
  Future<void> logout() async {
    await _auth.signOut();
  }

  @override
  Future<void> updateDisplayName(String name) async {
    final user = _auth.currentUser;
    if (user == null) throw StateError('Chưa đăng nhập');
    await user.updateDisplayName(name);
    await _firestore.collection(_usersCollection).doc(user.uid).set({
      'displayName': name,
    }, SetOptions(merge: true));
  }

  @override
  Future<void> sendPasswordReset(String email) async {
    await _auth.sendPasswordResetEmail(email: email);
  }
}
