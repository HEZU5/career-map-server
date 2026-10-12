import 'package:flutter/material.dart';

import '../../theme/tokens.dart';
import 'app_button.dart';

/// Dialog chính của app — header illustration area, custom actions,
/// consistent styling.
///
/// Thay thế `AlertDialog` trực tiếp để dialog có bản sắc thống nhất:
/// header có icon/màu nền, content có spacing đều, actions căn phải.
class AppDialog extends StatelessWidget {
  const AppDialog({
    super.key,
    required this.title,
    required this.content,
    required this.actions,
    this.icon,
    this.iconColor,
    this.iconBackground,
    this.semanticLabel,
  });

  final String title;
  final Widget content;
  final List<Widget> actions;
  final IconData? icon;
  final Color? iconColor;
  final Color? iconBackground;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final primary = isDark ? AppColors.darkPrimary : AppColors.primary;
    final textPrimary = isDark ? AppColors.darkTextPrimary : AppColors.textPrimary;

    return Semantics(
      label: semanticLabel ?? title,
      child: Dialog(
        backgroundColor: isDark ? AppColors.darkSurface : AppColors.surface,
        insetPadding: const EdgeInsets.all(Gap.xxl),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(Rad.xl),
        ),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Padding(
            padding: const EdgeInsets.all(Gap.xxl),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (icon != null) ...[
                  Center(
                    child: Container(
                      width: 56,
                      height: 56,
                      decoration: BoxDecoration(
                        color: iconBackground ?? primary.withValues(alpha: 0.12),
                        shape: BoxShape.circle,
                      ),
                      child: Icon(icon, color: iconColor ?? primary, size: 28),
                    ),
                  ),
                  const SizedBox(height: Gap.lg),
                ],
                Text(
                  title,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 20,
                    fontWeight: FontWeight.w700,
                    color: textPrimary,
                  ),
                ),
                const SizedBox(height: Gap.md),
                DefaultTextStyle(
                  style: TextStyle(
                    fontSize: 14,
                    color: isDark ? AppColors.darkTextSecondary : AppColors.textSecondary,
                    height: 1.5,
                  ),
                  child: content,
                ),
                const SizedBox(height: Gap.xxl),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    for (var i = 0; i < actions.length; i++) ...[
                      if (i > 0) const SizedBox(width: Gap.md),
                      actions[i],
                    ],
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Dialog xác nhận — variant phổ biến nhất (title + message + 2 nút).
class AppConfirmDialog extends StatelessWidget {
  const AppConfirmDialog({
    super.key,
    required this.title,
    required this.message,
    required this.confirmLabel,
    this.cancelLabel = 'Huỷ',
    this.icon,
    this.isDanger = false,
    this.onConfirm,
    this.onCancel,
  });

  final String title;
  final String message;
  final String confirmLabel;
  final String cancelLabel;
  final IconData? icon;
  final bool isDanger;
  final VoidCallback? onConfirm;
  final VoidCallback? onCancel;

  @override
  Widget build(BuildContext context) {
    final dangerColor = AppColors.error;

    return AppDialog(
      title: title,
      icon: icon,
      iconColor: isDanger ? dangerColor : null,
      iconBackground: isDanger ? dangerColor.withValues(alpha: 0.12) : null,
      content: Text(message, textAlign: TextAlign.center),
      actions: [
        AppButton(
          label: cancelLabel,
          variant: AppButtonVariant.ghost,
          size: AppButtonSize.sm,
          onPressed: onCancel,
        ),
        AppButton(
          label: confirmLabel,
          variant: isDanger ? AppButtonVariant.danger : AppButtonVariant.primary,
          size: AppButtonSize.sm,
          onPressed: onConfirm,
        ),
      ],
    );
  }
}
