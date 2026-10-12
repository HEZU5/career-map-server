import 'package:flutter/foundation.dart';

/// MỘT nguồn chân lý cho địa chỉ backend, dùng chung cho cả REST
/// (`HttpGameApi`) lẫn WebSocket (`GameSocket`) — trước đây hai nơi tự đọc
/// `API_BASE_URL` riêng nên dễ lệch nhau.
///
/// Nguồn cấu hình: `--dart-define=API_BASE_URL=...`
/// - Dev (`flutter run`): bỏ trống → `http://localhost:8080`.
/// - Web release (Firebase Hosting): nếu build THIẾU dart-define thì trả về
///   [productionBaseUrl] thay vì localhost — tránh việc bản deploy gọi nhầm
///   vào `http://localhost:8080` của máy người dùng (nguyên nhân lỗi đã gặp).
class ApiConfig {
  const ApiConfig._();

  /// ⚠ ĐIỀN URL BACKEND PRODUCTION TẠI ĐÚNG MỘT CHỖ NÀY (hoặc truyền lúc
  /// build bằng `--dart-define=API_BASE_URL=...`, giá trị truyền sẽ được ưu
  /// tiên). Cả REST và WebSocket đều suy ra từ đây.
  static const String productionBaseUrl = 'https://career-map-server.onrender.com';

  static const String _define = String.fromEnvironment('API_BASE_URL');

  /// `true` khi URL đến từ `--dart-define` (không phải giá trị mặc định).
  static bool get isConfigured => _define.isNotEmpty;

  /// Base URL REST tuyệt đối, đã bỏ dấu `/` cuối.
  static String get baseUrl {
    if (_define.isNotEmpty) return _stripTrailingSlash(_define);
    // Release web thiếu dart-define: dùng production, KHÔNG rơi về localhost.
    if (kReleaseMode && kIsWeb) return productionBaseUrl;
    return 'http://localhost:8080';
  }

  /// URL WebSocket tương ứng `/ws`. Đổi scheme `http→ws`, `https→wss` để
  /// không bị chặn mixed-content khi trang chạy qua HTTPS (Firebase Hosting).
  static String get socketUrl {
    final uri = Uri.parse(baseUrl);
    final isTls = uri.scheme == 'https';
    final defaultPort = isTls ? 443 : 80;
    final port = uri.port == defaultPort ? '' : ':${uri.port}';
    return '${isTls ? 'wss' : 'ws'}://${uri.host}$port/ws';
  }

  /// `true` khi client đang trỏ vào máy local — dùng để hiện cảnh báo ở lobby.
  static bool get isLocalhost {
    final host = Uri.parse(baseUrl).host;
    return host == 'localhost' || host == '127.0.0.1' || host == '0.0.0.0';
  }

  static String _stripTrailingSlash(String url) =>
      url.endsWith('/') ? url.substring(0, url.length - 1) : url;
}
