import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';

/// Mặt xúc xắc vẽ chấm (pip) chuẩn 1–6 trên nền trắng viền bo.
/// Dùng chung cho: ô điều khiển xúc xắc trên bàn, pill kết quả, badge nhỏ
/// trên thẻ người chơi ở rail.
class DiceFace extends StatelessWidget {
  const DiceFace({super.key, required this.value, required this.size});

  /// Mặt xúc xắc (1–6). Giá trị ngoài khoảng sẽ hiện 1 chấm.
  final int value;

  /// Cạnh vuông của hình xúc xắc (px).
  final double size;

  /// Vị trí chấm trong lưới 3x3 (đánh số 1..9 từ trái sang, trên xuống).
  static const Map<int, Set<int>> _positions = {
    1: {5},
    2: {3, 7},
    3: {3, 5, 7},
    4: {1, 3, 7, 9},
    5: {1, 3, 5, 7, 9},
    6: {1, 3, 4, 6, 7, 9},
  };

  @override
  Widget build(BuildContext context) {
    final pips = _positions[value] ?? const {5};
    return SizedBox(
      width: size,
      height: size,
      child: DecoratedBox(
        decoration: BoxDecoration(
          gradient: const LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [Colors.white, Color(0xFFEDE6F5)],
          ),
          borderRadius: BorderRadius.circular(size * 0.26),
          border: Border.all(color: const Color(0xFF6A4C93), width: size * 0.05),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.28),
              blurRadius: size * 0.14,
              offset: Offset(0, size * 0.05),
            ),
          ],
        ),
        child: Padding(
          padding: EdgeInsets.all(size * 0.16),
          child: Column(
            children: [
              for (var row = 0; row < 3; row++)
                Expanded(
                  child: Row(
                    children: [
                      for (var col = 0; col < 3; col++)
                        Expanded(
                          child: Center(
                            child: pips.contains(row * 3 + col + 1)
                                ? Container(
                                    width: size * 0.17,
                                    height: size * 0.17,
                                    decoration: BoxDecoration(
                                      shape: BoxShape.circle,
                                      gradient: const LinearGradient(
                                        begin: Alignment.topLeft,
                                        end: Alignment.bottomRight,
                                        colors: [AppColors.violet, Color(0xFF4B2B87)],
                                      ),
                                    ),
                                  )
                                : const SizedBox.shrink(),
                          ),
                        ),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
