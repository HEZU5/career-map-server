/// Xuất file nhị phân phía CLIENT:
///  - Web: tải qua trình duyệt (`<a download>`) như chức năng "Save as".
///  - Nền khác: stub trả [false] (không mở file manager vì khác nền tảng).
library;

export 'save_bytes_stub.dart' if (dart.library.html) 'save_bytes_web.dart';
