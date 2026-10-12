/// Toàn màn hình (fullscreen):
///  - Web: gọi `document.documentElement.requestFullscreen()` / `exitFullscreen()`.
///  - Nền khác (native/desktop): app chạy fullscreen theo màn hình OS, hàm
///    stub trả [false] — UI không hiển thị nút.
library;

export 'fullscreen_stub.dart' if (dart.library.html) 'fullscreen_web.dart';
