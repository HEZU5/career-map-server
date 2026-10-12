import 'package:flutter/material.dart';

import '../../theme/tokens.dart';

/// Button chính của app — 5 variants, 3 sizes, loading state.
enum AppButtonVariant { primary, secondary, outline, ghost, danger }
enum AppButtonSize { sm, md, lg }

class AppButton extends StatefulWidget {
  const AppButton({
    super.key,
    required this.label,
    this.onPressed,
    this.variant = AppButtonVariant.primary,
    this.size = AppButtonSize.md,
    this.icon,
    this.isLoading = false,
    this.fullWidth = false,
  });

  final String label;
  final VoidCallback? onPressed;
  final AppButtonVariant variant;
  final AppButtonSize size;
  final IconData? icon;
  final bool isLoading;
  final bool fullWidth;

  @override
  State<AppButton> createState() => _AppButtonState();
}

class _AppButtonState extends State<AppButton> {
  bool _isPressed = false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isDark = theme.brightness == Brightness.dark;

    final colors = _resolveColors(isDark);
    final bg = colors.$1;
    final fg = colors.$2;
    final border = colors.$3;
    final sizes = _resolveSize();
    final height = sizes.$1;
    final fontSize = sizes.$2;
    final padding = sizes.$3;
    final radius = sizes.$4;

    final isDisabled = widget.onPressed == null || widget.isLoading;

    Widget content;
    if (widget.isLoading) {
      content = SizedBox(
        width: height * 0.5,
        height: height * 0.5,
        child: CircularProgressIndicator(strokeWidth: 2.5, color: fg),
      );
    } else if (widget.icon != null) {
      content = Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(widget.icon, size: fontSize * 1.3),
          const SizedBox(width: Gap.sm),
          Text(widget.label),
        ],
      );
    } else {
      content = Text(widget.label);
    }

    return GestureDetector(
      onTapDown: isDisabled ? null : (_) => setState(() => _isPressed = true),
      onTapUp: isDisabled ? null : (_) => setState(() => _isPressed = false),
      onTapCancel: isDisabled ? null : () => setState(() => _isPressed = false),
      child: AnimatedScale(
        scale: _isPressed ? 0.97 : 1.0,
        duration: Motion.fast,
        child: AnimatedContainer(
          duration: Motion.fast,
          height: height,
          width: widget.fullWidth ? double.infinity : null,
          padding: padding,
          decoration: BoxDecoration(
            color: bg,
            borderRadius: BorderRadius.circular(radius),
            border: border != null ? Border.all(color: border, width: 1.5) : null,
            boxShadow: widget.variant == AppButtonVariant.primary && !isDisabled
                ? [
                    BoxShadow(
                      color: (isDark ? AppColors.darkPrimary : AppColors.primary)
                          .withValues(alpha: 0.3),
                      blurRadius: 8,
                      offset: const Offset(0, 4),
                    ),
                  ]
                : null,
          ),
          child: MergeSemantics(
            child: Center(
              child: DefaultTextStyle(
                style: theme.textTheme.labelLarge!.copyWith(
                  color: fg,
                  fontSize: fontSize,
                  fontWeight: FontWeight.w700,
                ),
                child: content,
              ),
            ),
          ),
        ),
      ),
    );
  }

  (Color, Color, Color?) _resolveColors(bool isDark) {
    final primary = isDark ? AppColors.darkPrimary : AppColors.primary;
    return switch (widget.variant) {
      AppButtonVariant.primary => (primary, isDark ? AppColors.darkOnPrimary : Colors.white, null),
      AppButtonVariant.secondary => (primary.withValues(alpha: isDark ? 0.2 : 0.12), primary, null),
      AppButtonVariant.outline => (Colors.transparent, primary, primary),
      AppButtonVariant.ghost => (Colors.transparent, primary, null),
      AppButtonVariant.danger => (AppColors.error, Colors.white, null),
    };
  }

  (double, double, EdgeInsets, double) _resolveSize() {
    return switch (widget.size) {
      AppButtonSize.sm => (36.0, 13.0, const EdgeInsets.symmetric(horizontal: Gap.md), Rad.md),
      AppButtonSize.md => (44.0, 15.0, const EdgeInsets.symmetric(horizontal: Gap.xl), Rad.lg),
      AppButtonSize.lg => (52.0, 16.0, const EdgeInsets.symmetric(horizontal: Gap.xxl), Rad.lg),
    };
  }
}
