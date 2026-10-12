/// Cố gắng khóa hướng ngang:
///  - Native app: `SystemChrome.setPreferredOrientations` đã làm việc này.
///  - Web: trình duyệt di động có thể hỗ trợ `screen.orientation.lock`,
///    phiên bản này thử gọi và trả về [true] nếu thành công, [false] khi
///    không hỗ trợ (không ném lỗi).
library;

export 'orientation_lock_stub.dart' if (dart.library.html) 'orientation_lock_web.dart';
