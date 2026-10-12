import 'dart:math' as math;

import 'package:flutter/material.dart';

/// Hệ toạ độ bàn cờ (tỉ lệ % so với kích thước bàn).
///
/// `assets/Board game_new.jpg` là ẢNH GỐC để nguyên (4022x2115 px) — mọi ô,
/// badge, chữ đều đã in sẵn trên ảnh. Vì bàn cờ luôn nằm trong khung tỉ lệ cố
/// định (FittedBox co giãn như nhau theo cả chiều ngang + dọc) nên toạ độ %
/// luôn khớp ảnh ở MỌI kích thước.
abstract final class BoardGeometry {
  /// Kích thước ảo của "sân" bàn cờ (cùng tỉ lệ 4022:2115 của ảnh gốc
  /// `Board game_new.jpg`).
  static const double virtualWidth = 1620;
  static const double virtualHeight = 1620 * 2115 / 4022;

  /// Tâm 53 ô đã đo trực tiếp trên ảnh gốc (4022x2115), đơn vị %.
  /// Thứ tự khớp hẳn thứ tự đi của [BoardDefinition.tiles].
  static const List<Offset> cellCenters = [
    Offset(26.42, 91.42), // 0 START
    Offset(18.98, 91.47), // 1 Cơ hội
    Offset(11.85, 91.47), // 2 R
    Offset(5.05, 89.74), // 3 góc dưới-trái
    Offset(4.28, 77.99), // 4 I
    Offset(4.28, 69.29), // 5 Cơ hội
    Offset(4.28, 61.56), // 6 A
    Offset(4.28, 53.81), // 7 ⭐
    Offset(4.28, 46.10), // 8 S
    Offset(4.28, 38.37), // 9 Cơ hội
    Offset(4.28, 30.61), // 10 E
    Offset(4.28, 19.62), // 11 ⭐
    Offset(5.33, 9.69), // 12 góc trên-trái
    Offset(11.46, 8.23), // 13 C
    Offset(16.15, 8.23), // 14 Cơ hội
    Offset(20.38, 8.23), // 15 R
    Offset(24.44, 8.23), // 16 ⭐
    Offset(28.52, 8.23), // 17 I
    Offset(32.58, 8.23), // 18 Thử thách
    Offset(36.65, 8.23), // 19 A
    Offset(40.71, 8.23), // 20 ⭐
    Offset(44.78, 8.23), // 21 S
    Offset(48.84, 8.23), // 22 Cơ hội
    Offset(52.91, 8.23), // 23 E
    Offset(56.99, 8.23), // 24 ⭐
    Offset(61.05, 8.23), // 25 C
    Offset(65.12, 8.23), // 26 Thử thách
    Offset(69.18, 8.23), // 27 R
    Offset(73.25, 8.23), // 28 ⭐
    Offset(77.31, 8.23), // 29 I
    Offset(81.38, 8.23), // 30 Cơ hội
    Offset(85.45, 8.23), // 31 A
    Offset(90.00, 8.23), // 32 ⭐
    Offset(94.23, 10.74), // 33 góc trên-phải
    Offset(95.72, 22.10), // 34 S
    Offset(95.72, 31.37), // 35 Cơ hội
    Offset(95.72, 39.79), // 36 E
    Offset(95.72, 47.52), // 37 ⭐
    Offset(95.72, 55.25), // 38 C
    Offset(95.72, 62.98), // 39 Cơ hội
    Offset(95.72, 70.71), // 40 R
    Offset(95.72, 78.04), // 41 ⭐
    Offset(94.25, 89.89), // 42 góc dưới-phải
    Offset(87.80, 91.47), // 43 I
    Offset(82.78, 91.47), // 44 Cơ hội
    Offset(78.37, 91.47), // 45 A
    Offset(74.14, 91.47), // 46 ⭐
    Offset(70.03, 91.47), // 47 S
    Offset(65.66, 91.47), // 48 Thử thách
    Offset(61.05, 91.47), // 49 E
    Offset(56.60, 91.47), // 50 ⭐
    Offset(52.32, 91.47), // 51 C
    Offset(48.04, 91.47), // 52 Cơ hội
  ];

  /// Bật chế độ debug HITBOX: vẽ viền/indextừng ô + bật nút "xuất ảnh PNG".
  /// Build bằng: `flutter build web --dart-define=DEBUG_HITBOX=true`.
  static const bool debugHitbox = bool.fromEnvironment('DEBUG_HITBOX');

  /// Ô ĐIỀU KHIỂN XÚC XẮC — dải trang trí hồng nhạt có mũi tên `<<<<` nằm
  /// giữa START (tâm 26.42%, 91.42%) và ô 52 (tâm 48.04%, 91.47%) ở cạnh
  /// dưới bàn. Đây là khoảng trống DUY NHẤT không có ô nào, nên mọi nút
  /// điều khiển đặt ở đây đều KHÔNG che ô/quân.
  ///
  /// Đo trực tiếp trên ảnh gốc 4022x2115: dải hồng chạy x 28.0% → 49.98%,
  /// y 83.4% → 99.9%. Thu hẹp lại để lề cách ô 52 (x ≥ 45.95% hitbox) và
  /// START (x ≤ 31.19% hitbox) — vùng an toàn [31.6, 45.4] x [84.0, 99.2].
  static const Rect controlZone = Rect.fromLTRB(31.6, 84.0, 45.4, 99.2);

  /// Tâm vùng điều khiển (%). Dùng để đặt pill "Gia Huy tung được 4"…
  /// không, dùng cho các phần tử con canh giữa theo cả 2 trục.
  static Offset get controlZoneCenter => controlZone.center;

  /// Hitbox RIÊNG TỪNG Ô — (chiều rộng %, chiều cao % của bàn), 53 phần tử
  /// khớp thứ tự [cellCenters]. Mỗi ô đo TRỰC TIẾP từ ranh giới màu trên ảnh
  /// 4022x2115 (cạnh trên/dưới = cả băng, cạnh trái/phải = cả băng, START và
  /// 4 góc = box riêng), nên w/h khác nhau theo vị trí thật của từng ô.
  /// Quân cờ (`pointFor`) và highlight/quân đều đọc CÙNG nguồn [cellCenters]
  /// nên không thể lệch nhau. Sửa số ở đây CHỈ ảnh hưởng hitbox — không đổi
  /// luật chơi. Số liệu cần tinh chỉnh theo ảnh debug.
  static const List<CellDims> cellDims = [
    CellDims(7.88, 15.84), // 0 START
    CellDims(6.99, 15.37), // 1 Cơ hội
    CellDims(7.24, 15.37), // 2 R
    CellDims(8.70, 16.78), // 3 góc dưới-trái THỬ THÁCH
    CellDims(8.11, 10.45), // 4 I (cạnh trái, chữ dọc)
    CellDims(8.11, 6.86), // 5 Cơ hội
    CellDims(8.11, 8.51), // 6 A
    CellDims(8.11, 6.90), // 7 ⭐
    CellDims(8.11, 8.51), // 8 S
    CellDims(8.11, 6.86), // 9 Cơ hội
    CellDims(8.11, 8.46), // 10 E
    CellDims(8.11, 13.43), // 11 ⭐
    CellDims(6.71, 13.71), // 12 góc trên-trái THỬ THÁCH
    CellDims(5.37, 15.89), // 13 C
    CellDims(3.95, 15.89), // 14 Cơ hội
    CellDims(4.45, 15.89), // 15 R
    CellDims(3.63, 15.89), // 16 ⭐
    CellDims(4.48, 15.89), // 17 I
    CellDims(3.61, 15.89), // 18 Thử thách
    CellDims(4.48, 15.89), // 19 A
    CellDims(3.61, 15.89), // 20 ⭐
    CellDims(4.48, 15.89), // 21 S
    CellDims(3.61, 15.89), // 22 Cơ hội
    CellDims(4.48, 15.89), // 23 E
    CellDims(3.63, 15.89), // 24 ⭐
    CellDims(4.45, 15.89), // 25 C
    CellDims(3.63, 15.89), // 26 Thử thách
    CellDims(4.45, 15.89), // 27 R
    CellDims(3.63, 15.89), // 28 ⭐
    CellDims(4.45, 15.89), // 29 I
    CellDims(3.63, 15.89), // 30 Cơ hội
    CellDims(4.48, 15.89), // 31 A
    CellDims(4.57, 15.89), // 32 ⭐
    CellDims(6.71, 13.71), // 33 góc trên-phải THỬ THÁCH
    CellDims(8.11, 10.17), // 34 S (cạnh phải, chữ dọc)
    CellDims(8.11, 8.27), // 35 Cơ hội
    CellDims(8.11, 8.46), // 36 E
    CellDims(8.11, 6.90), // 37 ⭐
    CellDims(8.11, 8.46), // 38 C
    CellDims(8.11, 6.90), // 39 Cơ hội
    CellDims(8.11, 8.46), // 40 R
    CellDims(8.11, 6.29), // 41 ⭐
    CellDims(6.96, 17.26), // 42 góc dưới-phải THỬ THÁCH
    CellDims(5.74, 15.37), // 43 I
    CellDims(4.25, 15.37), // 44 Cơ hội
    CellDims(4.53, 15.37), // 45 A
    CellDims(3.88, 15.37), // 46 ⭐
    CellDims(4.30, 15.37), // 47 S
    CellDims(4.38, 15.37), // 48 Thử thách
    CellDims(4.80, 15.37), // 49 E
    CellDims(4.05, 15.37), // 50 ⭐
    CellDims(4.45, 15.37), // 51 C
    CellDims(4.08, 15.37), // 52 Cơ hội
  ];

  /// Hitbox thực tế của ô [index] trên bàn kích thước [size].
  /// Dùng CHUNG cho highlight vàng, debug grid và nguồn toạ độ của quân cờ
  /// (cùng [cellCenters]) — đảm bảo khớp nhau ở mọi kích thước màn hình.
  /// Với ô nằm trong [polygonIndices], hitbox là đa giác [cellPath] (không phải
  /// rect) vì cạnh bị góc tím cắt chéo; ngược lại là hình chữ nhật tròn góc.
  static Rect cellRect(int index, Size size) {
    final center = centerFor(index);
    final dims = cellDims[index % cellDims.length];
    return Rect.fromCenter(
      center: Offset(size.width * center.dx / 100, size.height * center.dy / 100),
      width: size.width * dims.width / 100,
      height: size.height * dims.height / 100,
    );
  }

  /// 12 ô phải dùng hitbox ĐA GIÁC (đo trực tiếp từ ranh giới màu trên ảnh
  /// 4022x2115): 4 góc THỬ THÁCH (3, 12, 33, 42) + 8 ô sát góc bị đường chéo
  /// tím cắt thành hình thang (2, 4, 11, 13, 32, 34, 41, 43).
  static const List<int> polygonIndices = [2, 3, 4, 11, 12, 13, 32, 33, 34, 41, 42, 43];

  /// Trọng tâm (centroid) của đa giác [polygonPcts] — dùng cho quân cờ trên
  /// các ô đa giác (phần NHÌN THẤY của ô, không phải tâm hình chữ nhật cũ).
  static const List<Offset> polygonCenters = [
    Offset(11.7824, 91.9126), // 2 R
    Offset(5.0467, 89.8815), // 3 góc dưới-trái
    Offset(3.731, 78.4709), // 4 I
    Offset(3.9676, 20.4779), // 11 ⭐
    Offset(5.3665, 9.7384), // 12 góc trên-trái
    Offset(11.2249, 6.9859), // 13 C
    Offset(90.0114, 7.0361), // 32 ⭐
    Offset(95.0337, 10.277), // 33 góc trên-phải
    Offset(96.3231, 21.6258), // 34 S
    Offset(96.0669, 79.9932), // 41 ⭐
    Offset(94.2729, 89.945), // 42 góc dưới-phải
    Offset(88.0602, 92.5912), // 43 I
  ];

  /// Toạ độ (%) đa giác của 12 ô trong [polygonIndices] (đúng thứ tự danh sách
  /// trên), mỗi phần tử là list x0,y0,x1,y1,... Đo từ ranh giới màu thật của
  /// từng ô (mask màu liên thông → convex hull → epil-decimate 4-6 đỉnh);
  /// đa giác liền kề không chồng lấn quá ~1px.
  static const List<List<double>> polygonPcts = [
    // 2 R (dưới, trái góc 3)
    [10.0155, 83.0258, 15.4658, 83.786, 15.4658, 99.1513, 6.5994, 99.262],
    // 3 góc dưới-trái THỬ THÁCH
    [
      8.3463,
      78.5978,
      10.0155,
      83.0258,
      6.5994,
      99.262,
      3.4317,
      99.1513,
      1.9876,
      98.2509,
      1.0947,
      96.8339,
      0.396,
      94.5166,
      0.2717,
      88.2731,
      0.1941,
      88.1919,
    ],
    // 4 I (trái, trên góc 3)
    [0.2484, 73.3358, 8.3036, 72.2952, 8.3463, 78.5978, 0.1941, 88.1919],
    // 11 ⭐ (trái, dưới góc 12)
    [0.2329, 12.3247, 9.045, 19.5572, 8.3307, 25.6236, 0.2329, 25.6236],
    // 12 góc trên-trái THỬ THÁCH
    [
      6.0171,
      0.369,
      11.646,
      16.0886,
      9.045,
      19.5572,
      0.2329,
      12.3247,
      0.2329,
      6.2435,
      0.722,
      3.6384,
      1.9876,
      1.2325,
      3.7034,
      0.3321,
    ],
    // 13 C (trên, phải góc 12)
    [6.0171, 0.369, 13.8005, 0.428, 14.4449, 15.8893, 11.646, 16.0886],
    // 32 ⭐ (trên, trái góc 33)
    [87.7174, 0.428, 93.9441, 0.369, 90.0621, 16.0886, 87.7174, 15.9336],
    // 33 góc trên-phải THỬ THÁCH
    [
      93.9441,
      0.369,
      97.0497,
      0.6642,
      98.7811,
      2.6937,
      99.6506,
      5.9557,
      99.7671,
      11.8081,
      91.6925,
      22.1402,
      91.2733,
      19.6679,
      90.0621,
      16.0886,
    ],
    // 34 S (phải, trên góc 33)
    [91.6925, 22.1402, 99.7671, 11.8081, 99.7748, 26.428, 91.6964, 27.845],
    // 41 ⭐ (phải, trên góc 42)
    [
      91.6925,
      75.1292,
      99.7671,
      75.1292,
      99.7671,
      87.8229,
      90.6832,
      80.369,
      90.955,
      79.7048,
      91.3432,
      78.2288,
      91.5761,
      76.7528,
    ],
    // 42 góc dưới-phải THỬ THÁCH
    [
      90.6832,
      80.369,
      99.7671,
      87.8229,
      99.5031,
      93.2841,
      98.9053,
      95.4613,
      98.1366,
      97.0701,
      96.2461,
      98.9151,
      93.5559,
      99.1882,
      87.8882,
      83.7638,
      89.4837,
      82.4576,
    ],
    // 43 I (dưới, trái góc 42)
    [84.5885, 83.8376, 87.8882, 83.7638, 93.5559, 99.1882, 85.2873, 99.1882],
  ];

  /// Ô [index] có hitbox đa giác không?
  static bool isPolygonCell(int index) => _polygonSlot(index) >= 0;

  /// Tâm dùng cho quân cờ / hiệu ứng: với ô đa giác là trọng tâm phần nhìn
  /// thấy ([polygonCenters]), ô thường vẫn là [cellCenters].
  static Offset centerFor(int index) {
    final i = index % cellCenters.length;
    final slot = _polygonSlot(i);
    return slot >= 0 ? polygonCenters[slot] : cellCenters[i];
  }

  static int _polygonSlot(int index) {
    final i = index % cellCenters.length;
    for (var s = 0; s < polygonIndices.length; s++) {
      if (polygonIndices[s] == i) return s;
    }
    return -1;
  }

  /// Hitbox thực tế của ô [index] trên bàn [size] dưới dạng [Path]:
  /// đa giác nếu thuộc [polygonIndices], ngược lại RRect tròn góc (giữ hiệu
  /// ứng bo góc như cũ). Dùng cho highlight vàng, debug grid và hit-test.
  static Path cellPath(int index, Size size) {
    final slot = _polygonSlot(index);
    if (slot >= 0) {
      return polygonPath(index, size);
    }
    final rect = cellRect(index, size);
    return Path()..addRRect(
      RRect.fromRectAndRadius(rect, Radius.circular(math.min(rect.width, rect.height) * 0.3)),
    );
  }

  /// Đa giác của ô [index] (chỉ gọi khi [isPolygonCell]), từ [polygonPcts].
  static Path polygonPath(int index, Size size) {
    final pcts = polygonPcts[_polygonSlot(index)];
    final path = Path();
    for (var i = 0; i < pcts.length; i += 2) {
      final p = Offset(size.width * pcts[i] / 100, size.height * pcts[i + 1] / 100);
      if (i == 0) {
        path.moveTo(p.dx, p.dy);
      } else {
        path.lineTo(p.dx, p.dy);
      }
    }
    return path..close();
  }

  /// Hit-test: điểm [local] (toạ độ tại gốc bàn) có nằm trong hitbox của ô
  /// [index] không? Dùng [cellPath] (Path.contains) nên tôn trọng đa giác.
  static bool cellContains(Offset local, int index, Size size) =>
      cellPath(index, size).contains(local);
}

/// Kích thước hitbox 1 ô (% so với bàn).
@immutable
class CellDims {
  const CellDims(this.width, this.height);

  /// Chiều rộng ô tính bằng % chiều rộng bàn (đảo nghĩa với ô chữ dọc).
  final double width;

  /// Chiều cao ô tính bằng % chiều cao bàn.
  final double height;
}
