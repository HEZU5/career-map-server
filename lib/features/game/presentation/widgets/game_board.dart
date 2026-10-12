import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../domain/board.dart';
import '../game_controller.dart';
import 'board_geometry.dart';
import 'board_painter.dart';
import 'player_corner.dart';

/// Ảnh bàn cờ GỐC (giữ nguyên 3862x2030): in sẵn toàn bộ 53 ô, badge và chữ
/// (xem [BoardGeometry]). Không vẽ gì đè lên ngoài highlight ô có quân.
const boardGameAsset = 'assets/Board game_new.jpg';

/// Miêu tả quân cờ đang chạy theo đường ô: từ ô [from] đi [steps] ô,
/// vị trí đọc từ [anim] (0 → 1). `GamePage` tạo ra khi bắt đầu animate
/// và xoá khi xong; những quân khác vẫn lướt bằng `AnimatedPositioned`.
class MovementAnim {
  const MovementAnim({
    required this.playerId,
    required this.from,
    required this.anim,
    required this.steps,
  });

  final String playerId;
  final int from;
  final Animation<double> anim;
  final int steps;
}

/// Bàn cờ trung tâm. Toàn bộ ô/quân/highlight nằm trong CÙNG một `Stack`
/// với ảnh nền, dùng toạ độ % so với bàn — nên luôn khớp ảnh ở mọi kích
/// thước màn hình (bàn được đặt trong FittedBox tỉ lệ cố định).
///
/// Thứ tự lớp (từ dưới lên):
///   1. ảnh nền
///   2. highlight ô có quân
///   3. [controlZone] — ô điều khiển xúc xắc (dải mũi tên, không ô nào)
///   4. quân cờ (LUÔN trên control zone)
class GameBoard extends StatelessWidget {
  const GameBoard({
    super.key,
    required this.controller,
    this.movement,
    this.boundaryKey,
    this.controlZone,
    this.topOverlay,
  });

  final GameController controller;

  /// Quân đang di chuyển theo đường ô (nếu có).
  final MovementAnim? movement;

  /// Khoá vào `RepaintBoundary` của bàn cờ — dùng cho chức năng debug
  /// "xuất ảnh PNG" (chụp hitbox để đối chiếu với ảnh gốc).
  final GlobalKey? boundaryKey;

  /// Ô điều khiển xúc xắc — vẽ TRÊN highlight, DƯỚI quân cờ (quân luôn nổi
  /// lên khi chạy qua dải này).
  final Widget? controlZone;

  /// Lớp phủ trên cùng của bàn (ví dụ pill kết quả tung) — nằm trên quân.
  final Widget? topOverlay;

  @override
  Widget build(BuildContext context) {
    return RepaintBoundary(
      key: boundaryKey,
      child: LayoutBuilder(
        builder: (context, box) {
          final size = Size(box.maxWidth, box.maxHeight);
          return Stack(
            clipBehavior: Clip.none,
            children: [
              // 1. Ảnh nền (ảnh gốc) — lớp dưới cùng.
              Positioned.fill(
                child: Image.asset(
                  boardGameAsset,
                  fit: BoxFit.fill,
                  filterQuality: FilterQuality.high,
                  gaplessPlayback: true,
                  alignment: Alignment.center,
                  errorBuilder: (_, _, _) => const SizedBox.shrink(),
                ),
              ),
              // 2. Các lớp highlight/quân vé trên cùng hệ toạ độ với ảnh
              //    (cùng % — không thể lệch ở mọi kích thước).
              Positioned.fill(
                child: LayoutBuilder(
                  builder: (context, inner) {
                    final occupied = {
                      for (final p in controller.players)
                        if (!p.left) p.position % BoardDefinition.length,
                    };
                    // Gom quân theo ô để xếp chồng cố định khi nhiều người
                    // cùng đứng 1 ô (hay gặp ở START).
                    final stackAt = <int, List<int>>{};
                    for (var i = 0; i < controller.players.length; i++) {
                      final key = controller.players[i].position % BoardDefinition.length;
                      (stackAt[key] ??= <int>[]).add(i);
                    }
                    return Stack(
                      clipBehavior: Clip.none,
                      children: [
                        CustomPaint(
                          painter: BoardPainter(BoardDefinition.tiles, highlightTiles: occupied),
                          child: const SizedBox.expand(),
                        ),
                        // 3. Ô điều khiển xúc xắc (nằm trên highlight).
                        ?controlZone,
                        // 4. Quân cờ — luôn trên ô điều khiển.
                        for (var i = 0; i < controller.players.length; i++)
                          _PawnSlot(
                            playerIndex: i,
                            controller: controller,
                            size: size,
                            movement: movement,
                            stackSlot:
                                (stackAt[controller.players[i].position % BoardDefinition.length] ??
                                        const <int>[])
                                    .indexOf(i),
                            stackSize:
                                (stackAt[controller.players[i].position % BoardDefinition.length] ??
                                        const <int>[])
                                    .length,
                          ),
                        // 5. Lớp phủ trên cùng (pill kết quả tung).
                        if (topOverlay != null) Positioned.fill(child: topOverlay!),
                      ],
                    );
                  },
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}

class _PawnSlot extends StatelessWidget {
  const _PawnSlot({
    required this.playerIndex,
    required this.controller,
    required this.size,
    required this.movement,
    this.stackSlot = 0,
    this.stackSize = 1,
  });

  final int playerIndex;
  final GameController controller;
  final Size size;
  final MovementAnim? movement;

  /// Vị trí trong nhóm quân đứng cùng ô (0 = quân đầu tiên).
  final int stackSlot;

  /// Tổng số quân đứng cùng ô.
  final int stackSize;

  /// Bán kính quân cờ — co giãn theo bàn, nhưng KHÔNG nhỏ hơn 12px bán kính
  /// (24px đường kính) để vẫn thấy rõ trên điện thoại ngang.
  double get _pawnRadius => math.max(12.0, size.width * 0.018);

  /// Quân của người đang tới lượt lớn hơn một chút cho nổi bật.
  double _radiusFor(bool active) =>
      active && !controller.players[playerIndex].left ? _pawnRadius * 1.18 : _pawnRadius;

  /// Lệch cố định khi nhiều quân đứng chung ô: xếp lưới 2 cột, mỗi quân lệch
  /// 0.55 bán kính theo cả 2 chiều (không phụ thuộc thứ tự index toàn cục).
  Offset _stackOffset(bool active) {
    if (stackSize <= 1) return Offset.zero;
    final r = _radiusFor(active);
    final col = stackSlot % 2;
    final row = stackSlot ~/ 2;
    final dx = (col - 0.5) * r * 0.62;
    final dy = (row - ((stackSize - 1) / 2).floor() / 2) * r * 0.62;
    return Offset(dx, dy);
  }

  @override
  Widget build(BuildContext context) {
    final player = controller.players[playerIndex];
    final active = playerIndex == controller.activeIndex;
    final radius = _radiusFor(active);
    final offset = _stackOffset(active);

    if (movement != null && player.id == movement!.playerId) {
      final m = movement!;
      // Đếm số ô đã bước (0 → steps) theo HƯỚNG ĐI CỐ ĐỊNH; ô thực sự = ô
      // xuất phát dịch chuyển bằng `moveIndex` (wrap qua START tự động).
      final stepIndex = m.anim.value * m.steps;
      final point = BoardPainter.pathPoint(stepIndex, size, from: m.from);
      // Nảy nhẹ giữa mỗi ô để dễ thấy quân đang bước.
      final seg = stepIndex - stepIndex.floorToDouble();
      final hop = -radius * 0.6 * math.sin(math.pi * seg).abs();
      return Positioned(
        left: point.dx - radius,
        top: point.dy - radius + hop,
        child: PlayerPawn(
          player: player,
          active: active,
          radius: radius,
          asset: characterAssetOf(player, fallbackIndex: playerIndex),
        ),
      );
    }

    final point = BoardPainter.pointFor(player.position, size);
    return AnimatedPositioned(
      duration: const Duration(milliseconds: 420),
      curve: Curves.easeOutCubic,
      left: point.dx - radius + offset.dx,
      top: point.dy - radius + offset.dy,
      child: PlayerPawn(
        player: player,
        active: active,
        radius: radius,
        asset: characterAssetOf(player, fallbackIndex: playerIndex),
      ),
    );
  }
}
