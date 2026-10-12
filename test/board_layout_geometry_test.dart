import 'package:career_map/features/game/presentation/widgets/board_geometry.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// Vùng điều khiển xúc xắc nằm trên bàn cờ — bắt buộc KHÔNG được chạm bất kỳ
/// ô nào (đó là điều kiện "không widget nào nằm đè lên ô cờ").
void main() {
  const size = Size(BoardGeometry.virtualWidth, BoardGeometry.virtualHeight);
  final zone = BoardGeometry.controlZone;

  Rect zoneInPixels() => Rect.fromLTRB(
    size.width * zone.left / 100,
    size.height * zone.top / 100,
    size.width * zone.right / 100,
    size.height * zone.bottom / 100,
  );

  test('ô điều khiển không giao bất kỳ hitbox ô nào (quét mẫu 0.4%)', () {
    final rect = zoneInPixels();
    final stepX = size.width * 0.004;
    final stepY = size.height * 0.004;

    final offenders = <String>[];
    for (var x = rect.left; x <= rect.right; x += stepX) {
      for (var y = rect.top; y <= rect.bottom; y += stepY) {
        final p = Offset(x, y);
        for (var i = 0; i < BoardGeometry.cellDims.length; i++) {
          if (BoardGeometry.cellPath(i, size).contains(p)) {
            offenders.add(
              'ô $i tại (${(x / size.width * 100).toStringAsFixed(1)}%, '
              '${(y / size.height * 100).toStringAsFixed(1)}%)',
            );
          }
        }
      }
    }
    expect(offenders, isEmpty);
  });

  test('ô điều khiển nằm giữa START và ô 52 trên cạnh dưới', () {
    expect(zone.left, greaterThan(BoardGeometry.cellCenters[0].dx));
    expect(zone.right, lessThan(BoardGeometry.cellCenters[52].dx));
    expect(zone.center.dy, greaterThan(88));
    expect(zone.top, greaterThan(80));
    expect(zone.bottom, lessThanOrEqualTo(100));
  });

  test('mọi tâm quân cờ nằm NGOÀI vùng điều khiển', () {
    final rect = zoneInPixels();
    for (var i = 0; i < BoardGeometry.cellCenters.length; i++) {
      final c = BoardGeometry.centerFor(i);
      final p = Offset(size.width * c.dx / 100, size.height * c.dy / 100);
      expect(
        rect.contains(p),
        isFalse,
        reason: 'quân ô $i nằm trong ô điều khiển',
      );
    }
  });

  test('mọi ô đều nằm ngoài khung giữa 8–92% x 17–83% (popup không che ô)', () {
    // Khung popup chỉ là vùng trống artwork ở giữa bàn.
    final frameLeft = size.width * 0.08;
    final frameRight = size.width * 0.92;
    final frameTop = size.height * 0.17;
    final frameBottom = size.height * 0.83;
    for (var i = 0; i < BoardGeometry.cellCenters.length; i++) {
      final c = BoardGeometry.centerFor(i);
      final p = Offset(size.width * c.dx / 100, size.height * c.dy / 100);
      final inside =
          p.dx > frameLeft &&
          p.dx < frameRight &&
          p.dy > frameTop &&
          p.dy < frameBottom;
      expect(inside, isFalse, reason: 'tâm ô $i lọt vào khung giữa');
    }
  });
}
