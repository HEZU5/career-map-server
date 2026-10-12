import 'package:flutter/material.dart';

import '../../theme/tokens.dart';

/// Avatar chính của app — size variants, online indicator, character image.
///
/// Thay thế `CircleAvatar` trực tiếp để đảm bảo:
/// - Kích thước nhất quán (sm/md/lg/xl)
/// - Viền trạng thái online/offline
/// - Ảnh nhân vật hoặc chữ cái fallback
enum AppAvatarSize {
  /// 24px — badge nhỏ, danh sách dày đặc.
  sm,

  /// 32px — danh sách, thẻ người chơi compact.
  md,

  /// 44px — thẻ người chơi đầy đủ, phòng chờ.
  lg,

  /// 64px — avatar lớn, header.
  xl,
}

class AppAvatar extends StatelessWidget {
  const AppAvatar({
    super.key,
    required this.name,
    this.asset,
    this.color,
    this.size = AppAvatarSize.md,
    this.online,
    this.borderWidth,
  });

  final String name;
  final String? asset;
  final Color? color;
  final AppAvatarSize size;
  final bool? online;
  final double? borderWidth;

  @override
  Widget build(BuildContext context) {
    final (diameter, fontSize, borderW) = _resolveSize();
    final bg = color ?? _fallbackColor(name);

    Widget avatar = Container(
      width: diameter,
      height: diameter,
      decoration: BoxDecoration(
        color: bg,
        shape: BoxShape.circle,
        border: Border.all(
          color: Colors.white,
          width: borderW,
        ),
        boxShadow: Elev.sm,
      ),
      child: ClipOval(
        child: asset != null
            ? Image.asset(
                asset!,
                fit: BoxFit.cover,
                width: diameter,
                height: diameter,
                errorBuilder: (_, _, _) => _fallbackText(fontSize),
              )
            : _fallbackText(fontSize),
      ),
    );

    if (online == null) return avatar;

    return Stack(
      clipBehavior: Clip.none,
      children: [
        avatar,
        Positioned(
          right: 0,
          bottom: 0,
          child: Container(
            width: diameter * 0.32,
            height: diameter * 0.32,
            decoration: BoxDecoration(
              color: online! ? AppColors.success : AppColors.textSecondary,
              shape: BoxShape.circle,
              border: Border.all(color: Colors.white, width: 2),
            ),
          ),
        ),
      ],
    );
  }

  Widget _fallbackText(double fontSize) {
    return Center(
      child: Text(
        name.isNotEmpty ? name[0].toUpperCase() : '?',
        style: TextStyle(
          color: Colors.white,
          fontSize: fontSize,
          fontWeight: FontWeight.w800,
        ),
      ),
    );
  }

  (double, double, double) _resolveSize() {
    return switch (size) {
      AppAvatarSize.sm => (24.0, 10.0, 1.5),
      AppAvatarSize.md => (32.0, 13.0, 2.0),
      AppAvatarSize.lg => (44.0, 17.0, 2.5),
      AppAvatarSize.xl => (64.0, 24.0, 3.0),
    };
  }

  Color _fallbackColor(String name) {
    const palette = [
      Color(0xFF6C4AB6),
      Color(0xFF5DADE2),
      Color(0xFF58D68D),
      Color(0xFFF5A623),
      Color(0xFFE74C3C),
      Color(0xFF9B59B6),
    ];
    return palette[name.hashCode.abs() % palette.length];
  }
}
