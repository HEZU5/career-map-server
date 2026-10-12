import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';
import '../../domain/board.dart';
import 'board_geometry.dart';

/// Vẽ lớp chồng lên ảnh nền gốc (`Board game_new.jpg`).
///
/// Ảnh đã in sẵn toàn bộ ô, màu, badge (START / ⭐ / THỬ THÁCH ...) và chữ —
/// nên class này KHÔNG đổ màu / không vẽ badge lên ảnh. Chỉ vẽ:
///   - viền vàng quanh ô đang có quân cờ (hitbox RIÊNG từng ô, không tràn);
///   - (chế độ debug) viền + số thứ tự từng ô để đối chiếu hitbox với ảnh.
class BoardPainter extends CustomPainter {
  BoardPainter(this.tiles, {this.highlightTiles = const {}, this.showIndexes = true});

  final List<BoardTile> tiles;

  /// Các ô hiện đang có quân cờ (dùng để vẽ viền vàng).
  final Set<int> highlightTiles;

  /// Bật/tắt số thứ tự trong chế độ debug (cờ ẩn/ hiện số).
  final bool showIndexes;

  /// Toạ độ tâm ô thứ [index] trên bàn [size] — đọc từ bảng đo trên ảnh
  /// (chu vi 53 ô). [index] được wrap về trong vòng cờ để tái sử dụng cho
  /// các vòng sau. CÙNG NGUỒN với hitbox/vị trí quân nên luôn khớp.
  /// Với ô đa giác ([BoardGeometry.polygonIndices]), trả về trọng tâm phần
  /// nhìn thấy của ô ([BoardGeometry.polygonCenters]).
  static Offset pointFor(int index, Size size) {
    final center = BoardGeometry.centerFor(index);
    return Offset(size.width * center.dx / 100, size.height * center.dy / 100);
  }

  /// Vị trí TRÊN ĐƯỜNG ĐI khi quân đã bước được [stepIndex] ô (số thập phân,
  /// ví dụ 2.4 = giữa ô từ `from` đến ô sau). Ô tiếp theo được tính theo
  /// [BoardDefinition.direction]: đi ngược qua START vẫn wrap đúng 1 vòng.
  /// Hiệu ứng chạy từng ô: [stepIndex] chạy 0 → [steps] (số bước xúc xắc).
  static Offset pathPoint(double stepIndex, Size size, {required int from}) {
    final len = BoardGeometry.cellCenters.length;
    final k = stepIndex.floor();
    final t = stepIndex - stepIndex.floorToDouble();
    final a = BoardGeometry.centerFor(BoardDefinition.moveIndex(from, k) % len);
    final b = BoardGeometry.centerFor(BoardDefinition.moveIndex(from, k + 1) % len);
    return Offset.lerp(
      Offset(a.dx * size.width / 100, a.dy * size.height / 100),
      Offset(b.dx * size.width / 100, b.dy * size.height / 100),
      t,
    )!;
  }

  @override
  void paint(Canvas canvas, Size size) {
    for (final i in highlightTiles) {
      if (i < 0 || i >= tiles.length) continue;
      final fill = Paint()
        ..style = PaintingStyle.fill
        ..color = AppColors.gold.withValues(alpha: 0.10);
      final strokeThin = Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = size.width * 0.0032
        ..color = AppColors.gold;
      final strokeThick = Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = size.width * 0.008
        ..color = AppColors.gold.withValues(alpha: 0.25);
      if (BoardGeometry.isPolygonCell(i % 53)) {
        // Ô góc / ô sát góc: hitbox là đa giác (hình thang tím cắt chéo) — vẽ
        // theo đúng đa giác đã đo, không dùng rect.
        final path = BoardGeometry.cellPath(i, size);
        canvas.drawPath(path, fill);
        canvas.drawPath(path, strokeThin);
        canvas.drawPath(path, strokeThick);
      } else {
        final rect = BoardGeometry.cellRect(i, size);
        final rrect = RRect.fromRectAndRadius(
          rect,
          Radius.circular(math.min(rect.width, rect.height) * 0.3),
        );
        canvas.drawRRect(rrect, fill);
        canvas.drawRRect(rrect, strokeThin);
        canvas.drawRRect(rrect, strokeThick);
      }
    }

    if (BoardGeometry.debugHitbox) _paintDebugGrid(canvas, size);
  }

  @override
  bool shouldRepaint(covariant BoardPainter oldDelegate) =>
      oldDelegate.tiles != tiles ||
      oldDelegate.highlightTiles != highlightTiles ||
      oldDelegate.showIndexes != showIndexes;

  /// Chế độ debug: viền hitbox của CẢ 53 ô + số thứ tự nhỏ (không đè chữ/★)
  /// + chấm tâm — để người kiểm tra khớp từng ô với ảnh trước khi tinh chỉnh
  /// [BoardGeometry.cellDims]/[BoardGeometry.polygonPcts]. 12 ô góc/sát góc
  /// được vẽ theo đa giác, tô nền bán trong suốt xen kẽ màu.
  void _paintDebugGrid(Canvas canvas, Size size) {
    final label = _DebugText();
    for (var i = 0; i < BoardGeometry.cellCenters.length; i++) {
      final color = switch (BoardDefinition.tiles[i]) {
        StartTile() => const Color(0xFFF9A825),
        StarTile() => const Color(0xFF8E24AA),
        OpportunityTile() => const Color(0xFF1E88E5),
        ChallengeTile() => const Color(0xFFE53935),
        RiasecTile() => const Color(0xFF00897B),
      };
      final drawFill = Paint()
        ..style = PaintingStyle.fill
        ..color = color.withValues(alpha: i.isEven ? 0.08 : 0.04);
      final drawStroke = Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5
        ..color = color;
      if (BoardGeometry.isPolygonCell(i)) {
        final path = BoardGeometry.cellPath(i, size);
        canvas.drawPath(path, drawFill);
        canvas.drawPath(path, drawStroke);
        _paintCellLabel(canvas, label, i, path, size);
      } else {
        final rect = BoardGeometry.cellRect(i, size);
        final rrect = RRect.fromRectAndRadius(
          rect,
          Radius.circular(math.min(rect.width, rect.height) * 0.3),
        );
        canvas.drawRRect(rrect, drawFill);
        canvas.drawPath(Path()..addRRect(rrect), drawStroke..strokeWidth = 1);
        final center = pointFor(i, size);
        canvas.drawCircle(center, 2.5, Paint()..color = const Color(0xFFFF00FF));
        if (showIndexes) {
          label.paint(
            canvas,
            '$i',
            Offset(rect.left + 4, rect.top + 4),
            align: Alignment.centerRight,
          );
        }
      }
    }
  }

  /// Chấm tâm + số thứ tự ô đa giác; chấm tâm nằm ở trọng tâm, số đặt ở
  /// góc trên-trái của hitbox (đã cắt theo đa giác, không đè chữ/★).
  void _paintCellLabel(Canvas canvas, _DebugText label, int index, Path path, Size size) {
    final center = pointFor(index, size);
    canvas.drawCircle(center, 2.5, Paint()..color = const Color(0xFFFF00FF));
    if (!showIndexes) return;
    final bounds = path.getBounds();
    final corner = Offset(bounds.left + 4, bounds.top + 4);
    label.paint(canvas, '$index', corner, align: Alignment.centerLeft);
  }
}

/// Vẽ số thứ tự ô nhỏ (reuse TextPainter giữa các lần vẽ trong 1 paint).
class _DebugText {
  final TextPainter _painter = TextPainter(textDirection: TextDirection.ltr);

  void paint(
    Canvas canvas,
    String text,
    Offset offset, {
    AlignmentGeometry align = Alignment.topLeft,
  }) {
    _painter.text = TextSpan(
      text: text,
      style: const TextStyle(
        color: Color(0xFFFFFF00),
        fontSize: 11,
        fontWeight: FontWeight.w800,
        backgroundColor: Color(0xAA000000),
      ),
    );
    _painter.layout();
    final box = _painter.size;
    final dx = switch (align) {
      Alignment.centerRight => -box.width,
      Alignment.centerLeft => 0.0,
      _ => 0.0,
    };
    _painter.paint(canvas, offset + Offset(dx, 0));
  }
}
