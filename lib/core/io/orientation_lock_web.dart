import 'dart:js_interop';

/// `globalThis.screen.orientation.lock('landscape')` dạng typed interop.
@JS('globalThis.screen')
external JSObject? get _jsScreen;

extension type _Screen(JSObject _) implements JSObject {
  external _Orientation? get orientation;
}

extension type _Orientation(JSObject _) implements JSObject {
  external JSPromise<JSAny?> lock(String value);
}

/// Thử khóa hướng ngang trên trình duyệt di động (PWA/installed).
///  - Trình duyệt không hỗ trợ `screen.orientation.lock` → trả [false] mà
///    không crash; app tự dùng chế độ xoay nội dung 90° trong GamePage.
Future<bool> lockLandscape() async {
  try {
    final screen = _jsScreen;
    if (screen == null) return false;
    final orientation = (_Screen(screen)).orientation;
    if (orientation == null) return false;
    await orientation.lock('landscape').toDart;
    return true;
  } catch (_) {
    return false;
  }
}
