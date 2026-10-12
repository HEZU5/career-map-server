/// Motion system — duration và curve chuẩn cho toàn app.
///
/// Quy tắc:
/// - Duration: micro 120ms, standard 250ms, emphasized 400ms, page 350ms
/// - Curve: easeOutCubic (vào), easeInCubic (ra), easeOutBack (điểm nhấn vui)
/// - Animation phục vụ phản hồi hoặc định hướng, không trang trí thừa
/// - Mỗi màn tối đa 1 hiệu ứng nổi bật
/// - Tôn trọng giảm chuyển động (disableAnimations)
library;

import 'package:flutter/material.dart';

abstract final class AppMotion {
  // ── Durations ─────────────────────────────────────────────────────────
  static const Duration micro = Duration(milliseconds: 120);
  static const Duration fast = Duration(milliseconds: 150);
  static const Duration standard = Duration(milliseconds: 250);
  static const Duration emphasized = Duration(milliseconds: 400);
  static const Duration page = Duration(milliseconds: 350);
  static const Duration slow = Duration(milliseconds: 600);

  /// @Deprecated: use [standard] instead.
  static const Duration normal = standard;

  // ── Curves ───────────────────────────────────────────────────────────
  /// Vào — chậm dần khi kết thúc (mặc định cho hầu hết animation)
  static const Curve enter = Curves.easeOutCubic;

  /// Ra — nhanh dần khi bắt đầu (thoát màn, đóng dialog)
  static const Curve exit = Curves.easeInCubic;

  /// Điểm nhấn vui — bounce nhẹ cho CTA, thành công
  static const Curve bounce = Curves.easeOutBack;

  /// Mượt — cho slide, fade thông thường
  static const Curve smooth = Curves.easeInOutCubic;

  /// Linear — cho progress bar, shimmer
  static const Curve linear = Curves.linear;

  // ── Reduced motion ────────────────────────────────────────────────────
  /// Kiểm tra người dùng có bật giảm chuyển động không
  static bool isReducedMotion(BuildContext context) =>
      MediaQuery.of(context).disableAnimations;

  /// Trả về duration nếu bình thường, zero nếu giảm chuyển động
  static Duration maybe(BuildContext context, Duration duration) =>
      isReducedMotion(context) ? Duration.zero : duration;
}
