import 'package:flutter/material.dart';

import '../../../core/theme/tokens.dart';
import '../../../core/ui/components/app_button.dart';

/// Trang cửa hàng — hiện đang "Coming Soon".
class ShopPage extends StatelessWidget {
  const ShopPage({super.key});

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(Gap.xxl),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: Breakpoints.contentMaxWidth),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const SizedBox(height: Gap.xxl),
                  Icon(Icons.store_outlined, size: 100, color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.5)),
                  const SizedBox(height: Gap.xxl),
                  Text(
                    'Cửa hàng sắp có',
                    style: Theme.of(context).textTheme.headlineLarge?.copyWith(
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: Gap.md),
                  Text(
                    'Chức năng mua báo cáo định hướng nghề nghiệp và tài nguyên'
                    ' sẽ được mở ra sớm. Quay lại sau nhé!',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: isDark ? AppColors.darkTextSecondary : AppColors.textSecondary,
                      height: 1.5,
                    ),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: Gap.xxxl),
                  AppButton(
                    label: 'Quay về trang chủ',
                    onPressed: () => Navigator.of(context).pushReplacementNamed('/'),
                    icon: Icons.home_rounded,
                    size: AppButtonSize.lg,
                    fullWidth: true,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
