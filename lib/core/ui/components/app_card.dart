import 'package:flutter/material.dart';

import '../../theme/tokens.dart';

/// Card chính của app — 3 variants, border radius và shadow thống nhất.
///
/// Thay thế `Card` / `Material` + `BoxDecoration` rải rác khắp code
/// để đảm bảo tính nhất quán về góc bo, đổ bóng, padding.
enum AppCardVariant {
  /// Nền trắng, viền mờ, shadow nhẹ — dùng phổ biến nhất.
  elevated,

  /// Nền trắng, viền rõ, không shadow — dùng trong danh sách.
  outlined,

  /// Nền xám nhạt, không viền, không shadow — dùng làm section container.
  filled,
}

class AppCard extends StatelessWidget {
  const AppCard({
    super.key,
    required this.child,
    this.variant = AppCardVariant.elevated,
    this.padding = const EdgeInsets.all(Gap.xl),
    this.onTap,
    this.borderRadius,
    this.color,
  });

  final Widget child;
  final AppCardVariant variant;
  final EdgeInsets padding;
  final VoidCallback? onTap;
  final double? borderRadius;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final radius = borderRadius ?? Rad.lg;

    final (bg, border, shadow) = _resolveStyle(isDark);

    Widget card = AnimatedContainer(
      duration: AppMotion.fast,
      decoration: BoxDecoration(
        color: color ?? bg,
        borderRadius: BorderRadius.circular(radius),
        border: border,
        boxShadow: shadow,
      ),
      child: Padding(padding: padding, child: child),
    );

    if (onTap == null) return card;

    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(radius),
      child: card,
    );
  }

  (Color, Border?, List<BoxShadow>?) _resolveStyle(bool isDark) {
    final surface = isDark ? AppColors.darkSurface : AppColors.surface;
    final border = isDark ? AppColors.darkBorder : AppColors.border;

    return switch (variant) {
      AppCardVariant.elevated => (
          surface,
          Border.all(color: border),
          Elev.md,
        ),
      AppCardVariant.outlined => (
          surface,
          Border.all(color: border, width: 1.5),
          null,
        ),
      AppCardVariant.filled => (
          isDark ? AppColors.darkSurfaceVariant : AppColors.surfaceVariant,
          null,
          null,
        ),
    };
  }
}
