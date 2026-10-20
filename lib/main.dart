import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/material.dart';
import 'package:flutter_web_plugins/url_strategy.dart';

import 'app.dart';
import 'core/storage/session_store.dart';

export 'app.dart';

/// Entry-point siêu mỏng theo chuẩn 2024-2026:
/// không chứa UI, không chứa màu global, không chứa page.
/// UI nằm ở `app.dart` + `features/`.
///
/// Nạp phiên đã lưu (tên + phòng đang chơi) TRƯỚC khi dựng app để bấm F5 không
/// mất ván; từ đây mọi thao tác đọc/ghi phiên đều đồng bộ.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  usePathUrlStrategy();

  // Khởi tạo Firebase (dùng cho Firebase Auth). Các giá trị config cần được
  // cung cấp từ Firebase Console → Project settings → General → Your apps →
  // Config snippet (web). Nếu chạy web thiếu config, initializeApp sẽ ném lỗi
  // và app sẽ hiển thị trang thông báo thay vì crash trắng.
  try {
    await Firebase.initializeApp();
  } catch (e) {
    // Bỏ qua lỗi init nếu chưa có config — UI sẽ xử lý trạng thái "chưa kết nối Firebase".
    debugPrint('Firebase init warning: $e');
  }

  final session = await SessionStore.load();
  runApp(CareerMapApp(session: session));
}
