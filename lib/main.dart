import 'package:flutter/material.dart';

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
  final session = await SessionStore.load();
  runApp(CareerMapApp(session: session));
}
