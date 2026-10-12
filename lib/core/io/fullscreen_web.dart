import 'dart:async';
import 'dart:developer' as developer;
import 'dart:js_interop';

/// `globalThis.document` dạng typed interop cho Fullscreen API.
@JS('globalThis.document')
external JSObject get _jsDocument;

extension type _Document(JSObject _) implements JSObject {
  external _Element get documentElement;
  external JSAny? get fullscreenElement;
  external JSAny? get webkitFullscreenElement;
  external JSAny? get fullscreenEnabled;
  external JSAny? get webkitFullscreenEnabled;
  external JSPromise<JSAny?> exitFullscreen();
  external void addEventListener(JSString type, JSFunction listener);
  external void removeEventListener(JSString type, JSFunction listener);
}

extension type _Element(JSObject _) implements JSObject {
  external JSPromise<JSAny?> requestFullscreen([JSAny? options]);
  external JSAny? webkitRequestFullscreen();
}

/// Đọc các member fetch (dùng để phát hiện hỗ trợ không cần gọi).
extension type _ElementCapabilities(JSObject _) implements JSObject {
  external JSAny? get requestFullscreen;
  external JSAny? get webkitRequestFullscreen;
}

bool _boolOf(JSAny? v) => v != null && v.isA<JSBoolean>() ? (v as JSBoolean).toDart : false;

bool _isActive(_Document doc) =>
    doc.fullscreenElement != null || doc.webkitFullscreenElement != null;

/// Trình duyệt có hỗ trợ fullscreen API (iPhone Safari/bản stub trả false).
bool isFullscreenSupported() {
  try {
    final doc = _Document(_jsDocument);
    if (_boolOf(doc.fullscreenEnabled) || _boolOf(doc.webkitFullscreenEnabled)) {
      return true;
    }
    final caps = _ElementCapabilities(doc.documentElement);
    return caps.requestFullscreen != null || caps.webkitRequestFullscreen != null;
  } catch (_) {
    return false;
  }
}

/// Đang ở chế độ toàn màn hình (đọc đồng bộ, không cần await).
bool isFullscreenActive() {
  try {
    return _isActive(_Document(_jsDocument));
  } catch (_) {
    return false;
  }
}

/// Vào toàn màn hình. PHẢI gọi trong handler bấm nút: requestFullscreen được
/// thực thi trước bất kỳ `await` nào, nên vẫn nằm trong user gesture — không
/// bị trình duyệt từ chối. Target là `document.documentElement` (Element mới
/// có requestFullscreen; gọi trên Document sẽ TypeError [nguyên nhân lỗi gốc]).
Future<bool> enterFullscreen() async {
  final doc = _Document(_jsDocument);
  if (_isActive(doc)) return true;
  try {
    final element = doc.documentElement;
    try {
      await element.requestFullscreen().toDart;
    } catch (e) {
      // Safari cũ / member thiếu: fallback prefix webkit (đồng bộ).
      developer.log('[fullscreen] webkitRequestFullscreen fallback: $e');
      element.webkitRequestFullscreen();
    }
    return _isActive(doc);
  } catch (e) {
    developer.log('[fullscreen] không vào được (trình duyệt từ chối?): $e');
    return false;
  }
}

/// Thoát toàn màn hình.
Future<bool> exitFullscreen() async {
  final doc = _Document(_jsDocument);
  if (!_isActive(doc)) return true;
  try {
    await doc.exitFullscreen().toDart;
    return true;
  } catch (e) {
    developer.log('[fullscreen] không thoát được: $e');
    return false;
  }
}

/// Stream trả [true] mỗi khi trang vào fullscreen, [false] khi thoát — kể cả
/// khi người dùng nhấn ESC. Dùng để đồng bộ icon nút.
Stream<bool> fullscreenChanges() {
  late StreamController<bool> controller;
  JSFunction? handler;
  controller = StreamController<bool>.broadcast(
    onListen: () {
      handler = ((JSFunction _) {
        controller.add(_isActive(_Document(_jsDocument)));
      }).toJS;
      final doc = _Document(_jsDocument);
      doc.addEventListener('fullscreenchange'.toJS, handler!);
      doc.addEventListener('webkitfullscreenchange'.toJS, handler!);
    },
    onCancel: () {
      final h = handler;
      if (h == null) return;
      final doc = _Document(_jsDocument);
      doc.removeEventListener('fullscreenchange'.toJS, h);
      doc.removeEventListener('webkitfullscreenchange'.toJS, h);
      handler = null;
    },
  );
  return controller.stream;
}
