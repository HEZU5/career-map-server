// dart:html là thư viện web chuyên dụng được cấu trúc dùng riêng cho bản
// web (conditional import) — chấp nhận cảnh báo deprecation của analyzer.
// ignore_for_file: deprecated_member_use, avoid_web_libraries_in_flutter

import 'dart:html' as html;
import 'dart:typed_data';

/// Ghi [bytes] thành file tên [filename] qua cơ chế download của trình duyệt.
/// Trả [true] khi trình duyệt nhận lệnh (người dùng tự chọn chỗ lưu).
Future<bool> saveImageBytes(Uint8List bytes, String filename) async {
  try {
    final blob = html.Blob([bytes], 'image/png');
    final url = html.Url.createObjectUrlFromBlob(blob);
    final anchor = html.AnchorElement(href: url)
      ..download = filename
      ..style.display = 'none';
    html.document.body?.append(anchor);
    anchor.click();
    anchor.remove();
    html.Url.revokeObjectUrl(url);
    return true;
  } catch (_) {
    return false;
  }
}
