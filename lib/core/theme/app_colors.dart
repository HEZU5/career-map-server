/// Màu sắc dùng chung toàn app.
///
/// File này re-export từ [tokens.dart] để tránh ambiguous import.
/// Code cũ import 'app_colors.dart' vẫn hoạt động bình thường.
/// Code mới nên import trực tiếp 'tokens.dart'.
library;

export 'tokens.dart' show AppColors;
