import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../../core/storage/session_store.dart';
import '../data/auth_repository.dart';
import '../domain/app_user.dart';

/// Quản lý trạng thái đăng nhập của toàn ứng dụng.
///
/// Nghe stream từ `AuthRepository` và duy trì `AppUser` hiện tại,
/// đồng bộ với `SessionStore` để survives F5 / đóng tab.
class AuthStateProvider extends ChangeNotifier {
  AuthStateProvider(this._repository, this._session);

  final AuthRepository _repository;
  final SessionStore _session;

  AppUser? _user;
  bool _isLoading = false;
  String? _error;

  /// Lấy instance từ context (dùng `AuthStateProvider.of(context)`).
  static AuthStateProvider of(BuildContext context, {bool listen = true}) =>
      Provider.of<AuthStateProvider>(context, listen: listen);

  /// Người dùng hiện tại (null nếu chưa đăng nhập).
  AppUser? get user => _user;

  /// Đang xử lý đăng ký / đăng nhập / đăng xuất.
  bool get isLoading => _isLoading;

  /// Thông báo lỗi gần nhất (null nếu không có lỗi).
  String? get error => _error;

  /// true nếu có người dùng đăng nhập.
  bool get isAuthenticated => _user != null;

  /// Lắng nghe thay đổi trạng thái auth từ Firebase.
  void init() {
    _repository.authStateChanges.listen((user) {
      _user = user;
      if (user == null) {
        _session.clearActiveRoom();
      } else {
        _session.savePlayerName(user.displayNameOrEmail);
      }
      _error = null;
      notifyListeners();
    });
  }

  /// Đăng ký tài khoản mới rồi tự động đăng nhập.
  Future<bool> register({
    required String email,
    required String password,
    required String displayName,
  }) async {
    return _run(() async {
      final user = await _repository.register(
        email: email,
        password: password,
        displayName: displayName,
      );
      _user = user;
      _session.savePlayerName(user.displayNameOrEmail);
      _error = null;
    });
  }

  /// Đăng nhập bằng email + mật khẩu.
  Future<bool> login({required String email, required String password}) async {
    return _run(() async {
      final user = await _repository.login(email: email, password: password);
      _user = user;
      _session.savePlayerName(user.displayNameOrEmail);
      _error = null;
    });
  }

  /// Đăng xuất.
  Future<void> logout() async {
    _isLoading = true;
    _error = null;
    notifyListeners();

    try {
      await _repository.logout();
      _user = null;
      _session.clearActiveRoom();
      _error = null;
    } catch (e) {
      _error = 'Không thể đăng xuất: $e';
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  /// Cập nhật tên hiển thị.
  Future<bool> updateProfile({required String displayName}) async {
    return _run(() async {
      await _repository.updateDisplayName(displayName);
      _user = _user?.copyWith(displayName: displayName);
      _session.savePlayerName(displayName);
      _error = null;
    });
  }

  /// Gửi email khôi phục mật khẩu.
  Future<bool> sendPasswordReset({required String email}) async {
    return _run(() async {
      await _repository.sendPasswordReset(email);
      _error = null;
    });
  }

  Future<bool> _run(Future<void> Function() action) async {
    _isLoading = true;
    _error = null;
    notifyListeners();

    try {
      await action();
      return true;
    } on FirebaseAuthException catch (e) {
      _error = _mapAuthError(e);
      return false;
    } catch (e) {
      _error = 'Lỗi: $e';
      return false;
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  String _mapAuthError(FirebaseAuthException e) {
    switch (e.code) {
      case 'email-already-in-use':
        return 'Email này đã được đăng ký';
      case 'invalid-email':
        return 'Email không hợp lệ';
      case 'weak-password':
        return 'Mật khẩu quá yếu (tối thiểu 6 ký tự)';
      case 'user-not-found':
      case 'wrong-password':
        return 'Email hoặc mật khẩu không đúng';
      case 'too-many-requests':
        return 'Quá nhiều lần thử, vui lòng thử lại sau';
      case 'operation-not-allowed':
        return 'Tính năng đăng ký email chưa được bật';
      case 'user-disabled':
        return 'Tài khoản này đã bị khóa';
      default:
        return e.message ?? 'Lỗi xác thực';
    }
  }
}
