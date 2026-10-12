import 'package:flutter/material.dart';

import '../../theme/tokens.dart';

/// Loading state chính của app — skeleton shimmer + spinner.
///
/// Thay thế `CircularProgressIndicator` trơn để tăng trải nghiệm:
/// skeleton shimmer cho danh sách, spinner cho hành động.
class AppLoading extends StatelessWidget {
  const AppLoading({super.key, this.size = 24.0, this.strokeWidth = 2.5});

  final double size;
  final double strokeWidth;

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final color = isDark ? AppColors.darkPrimary : AppColors.primary;

    return Center(
      child: SizedBox(
        width: size,
        height: size,
        child: CircularProgressIndicator(
          strokeWidth: strokeWidth,
          color: color,
        ),
      ),
    );
  }
}

/// Skeleton shimmer — dùng khi đang tải danh sách.
class AppSkeleton extends StatelessWidget {
  const AppSkeleton({
    super.key,
    this.width,
    this.height = 16,
    this.borderRadius,
  });

  final double? width;
  final double height;
  final double? borderRadius;

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final base = isDark ? AppColors.darkSurfaceVariant : AppColors.surfaceVariant;

    return Container(
      width: width,
      height: height,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(borderRadius ?? Rad.sm),
        color: base,
      ),
    );
  }
}

/// Skeleton list — dùng khi đang tải danh sách phòng/người chơi.
class AppSkeletonList extends StatelessWidget {
  const AppSkeletonList({super.key, this.itemCount = 3, this.itemHeight = 64});

  final int itemCount;
  final double itemHeight;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        for (var i = 0; i < itemCount; i++)
          Padding(
            padding: const EdgeInsets.only(bottom: Gap.md),
            child: AppSkeleton(height: itemHeight, width: double.infinity),
          ),
      ],
    );
  }
}
