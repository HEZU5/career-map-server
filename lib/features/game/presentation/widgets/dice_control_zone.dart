import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../../core/theme/tokens.dart';
import 'board_geometry.dart';
import 'dice_face.dart';

/// Trạng thái của Ô ĐIỀU KHIỂN XÚC XẮC. Chỉ MỘT trạng thái có hiệu lực.
enum DiceControlState {
  /// 1. Chưa tới lượt / đang chờ người khác: KHÔNG nút, chỉ gợi ý trang trí.
  idle,

  /// 2. Tới lượt mình + sẵn sàng: nút "TUNG XÚC XẮC" nhấp nháy.
  readyToRoll,

  /// 3. Đang lắc / vừa trả kết quả: xúc xắc lắc rồi dừng ở mặt kết quả.
  rolling,

  /// 4. Quân đang đi hoặc thẻ/câu hỏi đang mở: chỉ mặt xúc xắc nhỏ / rỗng.
  busy,

  /// 5. Người chơi hiện tại đã xử lý xong ô: nút "LƯỢT TIẾP".
  resolved,

  /// 6. NGƯỜI KHÁC vừa tung: xem xúc xắc + mặt kết quả của họ. Ai chơi cũng
  ///    thấy (không chỉ người bấm nút), giữ mặt xúc xắc tới lượt kế tiếp.
  watching,
}

/// Ô điều khiển xúc xắc — CON của lớp bàn cờ, nằm đúng dải mũi tên `<<<<`
/// giữa START và ô 52 (vùng DUY NHẤT trên cạnh dưới không có ô nào), nên
/// đặt ở đây không che ô cờ/quân ở bất kỳ kích thước nào.
///
/// Vùng đặt cố định ([BoardGeometry.controlZone]) → bật/tắt nút KHÔNG làm
/// layout nhảy. Nút TUNG chỉ được dựng vào cây khi [showRollButton] đúng
/// điều kiện `isMyTurn && phase == waitingRoll && !moving && !popupOpen`
/// (không phải chỉ làm mờ/khóa).
///
/// Nằm TRONG `GameBoard` nhưng được vẽ TRƯỚC lớp quân cờ ([pawnsOnTop]) để
/// quân luôn nổi lên trên vùng điều khiển khi chạy qua.
class DiceControlZone extends StatelessWidget {
  const DiceControlZone({
    super.key,
    required this.boardSize,
    required this.state,
    required this.blink,
    required this.diceAnimation,
    this.diceFace = 0,
    this.showRollButton = false,
    this.showNextButton = false,
    this.waitingName = '',
    this.rollLabel = 'TUNG XÚC XẮC',
    this.nextLabel = 'LƯỢT TIẾP',
    this.onRoll,
    this.onNext,
  });

  /// Kích thước bàn cờ — để quy đổi vùng điều khiển (%) ra px như mọi lớp
  /// khác, nên nút luôn co giãn theo bàn.
  final Size boardSize;

  final DiceControlState state;

  /// Nhịp nhấp nháy của nút TUNG (0→1→0 lặp).
  final Animation<double> blink;

  /// Nhịp lắc của xúc xắc (0→1 trong lúc đang tung).
  final Animation<double> diceAnimation;

  /// Mặt xúc xắc hiện tại (0 = chưa có kết quả nào để hiện).
  final int diceFace;

  /// Điều kiện được phép hiện nút TUNG.
  final bool showRollButton;

  /// Người đang xử lý đã xong ô → hiện "LƯỢT TIẾP".
  final bool showNextButton;

  /// Tên người chơi đang chờ (trạng thái idle).
  final String waitingName;
  final String rollLabel;
  final String nextLabel;
final VoidCallback? onRoll;

  final VoidCallback? onNext;
  @override
  Widget build(BuildContext context) {
    final w = boardSize.width;
    final h = boardSize.height;
    final zone = BoardGeometry.controlZone;
    return Positioned(
      left: w * zone.left / 100,
      top: h * zone.top / 100,
      width: w * zone.width / 100,
      height: h * zone.height / 100,
      // Vùng cố định, chỉ nội dung bên trong thay đổi → không bao giờ nhảy.
      child: LayoutBuilder(
        builder: (context, box) {
          final zoneW = box.maxWidth;
          final zoneH = box.maxHeight;
          // Nút ≤ 90% vùng theo cả 2 chiều; luôn ≥ 44px để bấm được trên
          // điện thoại (vùng luôn rộng hơn 44px ở mọi kích thước hợp lệ).
          final button = math.min(zoneW, zoneH) * 0.9;
          final fitsText = button >= 52 && zoneW >= button * 2.4;
          return Center(
            child: SizedBox(
              width: fitsText ? math.min(zoneW * 0.9, button * 3.4) : button,
              height: button,
              child: _bodyForState(state, button, fitsText),
            ),
          );
        },
      ),
    );
  }

  /// Nội dung ô điều khiển theo 5 trạng thái.
  Widget _bodyForState(DiceControlState state, double button, bool fitsText) {
    return switch (state) {
      DiceControlState.readyToRoll =>
        showRollButton
            ? _BlinkRollButton(
                blink: blink,
                label: rollLabel,
                showText: fitsText,
                onTap: onRoll,
              )
            : const SizedBox.shrink(),
      DiceControlState.rolling => _ShakingDice(
        animation: diceAnimation,
        value: diceFace == 0 ? 1 : diceFace,
        size: button,
      ),
      DiceControlState.busy =>
        diceFace == 0
            ? const SizedBox.shrink()
            : Center(child: DiceFace(value: diceFace, size: button * 0.78)),
      DiceControlState.resolved =>
        showNextButton
            ? _NextTurnButton(label: nextLabel, showText: fitsText, onTap: onNext)
            : const SizedBox.shrink(),
      DiceControlState.idle => _WaitingHint(name: waitingName, showText: fitsText),
      // Người khác vừa tung: vẫn lắc nếu animation còn chạy, rồi dừng ở mặt
      // kết quả — ai cũng thấy, không chỉ người tung.
      DiceControlState.watching =>
        diceFace == 0
            ? _WaitingHint(name: waitingName, showText: fitsText)
            : _ShakingDice(animation: diceAnimation, value: diceFace, size: button),
    };
  }
}

/// Nút TUNG nhấp nháy (nhắc người chơi tới lượt). Chữ + icon xúc xắc; màn
/// nhỏ chỉ còn icon tròn.
class _BlinkRollButton extends StatelessWidget {
  const _BlinkRollButton({
    required this.blink,
    required this.label,
    required this.showText,
    this.onTap,
  });

  final Animation<double> blink;
  final String label;
  final bool showText;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: blink,
      builder: (context, child) {
        final t = blink.value;
        return Transform.scale(
          scale: 1 + 0.04 * t,
          child: Opacity(opacity: 0.82 + 0.18 * t, child: child),
        );
      },
      child: _PillButton(
        color: AppColors.primaryButton,
        onTap: onTap,
        showText: showText,
        icon: Icons.casino,
        label: label,
      ),
    );
  }
}

/// Nút "LƯỢT TIẾP" (chỉ người chơi hiện tại sau khi xử lý xong ô).
class _NextTurnButton extends StatelessWidget {
  const _NextTurnButton({required this.label, required this.showText, this.onTap});

  final String label;
  final bool showText;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return _PillButton(
      color: AppColors.violet,
      onTap: onTap,
      showText: showText,
      icon: Icons.skip_next,
      label: label,
    );
  }
}

/// Nút pill dùng chung (nền màu + viền trắng + chữ trắng, bo tròn).
class _PillButton extends StatelessWidget {
  const _PillButton({
    required this.color,
    required this.icon,
    required this.label,
    required this.showText,
    this.onTap,
  });

  final Color color;
  final IconData icon;
  final String label;
  final bool showText;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: color,
      borderRadius: BorderRadius.circular(Rad.full),
      elevation: 3,
      shadowColor: color.withValues(alpha: 0.5),
      child: InkWell(
        borderRadius: BorderRadius.circular(Rad.full),
        onTap: onTap,
        child: Container(
          constraints: const BoxConstraints(minWidth: 44, minHeight: 44),
          padding: EdgeInsets.symmetric(horizontal: showText ? Gap.lg : 0),
          alignment: Alignment.center,
          child: Row(
            mainAxisSize: MainAxisSize.min,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, color: Colors.white, size: 22),
              if (showText) ...[
                const SizedBox(width: Gap.sm),
                Flexible(
                  child: FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Text(
                      label,
                      maxLines: 1,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 15,
                        fontWeight: FontWeight.w900,
                        letterSpacing: 1,
                      ),
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// Trạng thái chờ: KHÔNG có nút — chỉ một dòng chữ nhỏ mờ để người chơi biết
/// đang chờ ai, và không chiếm chỗ bấm.
class _WaitingHint extends StatelessWidget {
  const _WaitingHint({required this.name, required this.showText});

  final String name;
  final bool showText;

  @override
  Widget build(BuildContext context) {
    if (!showText || name.isEmpty) return const SizedBox.shrink();
    return Center(
      child: Opacity(
        opacity: 0.55,
        child: Icon(
          Icons.hourglass_top,
          color: AppColors.primaryDark,
          size: 20,
          semanticLabel: 'Đang chờ $name',
        ),
      ),
    );
  }
}

/// Xúc xắc đang lắc rồi DỪNG Ở mặt kết quả: lắc nhanh nhiều vòng khi animation
/// tiến, khi animation về cuối (0→1) xoay dừng và hiện đúng mặt [value].
class _ShakingDice extends StatelessWidget {
  const _ShakingDice({required this.animation, required this.value, required this.size});

  final Animation<double> animation;
  final int value;
  final double size;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: animation,
      builder: (context, _) {
        final t = animation.value.clamp(0.0, 1.0);
        // Lắc 2.5 vòng, amplitude giảm dần về 0 khi tới kết quả.
        final turns = 2.5 * (1 - 0.15 * t);
        final shake = math.sin(t * math.pi * 2 * turns) * (1 - t) * size * 0.06;
        return Transform.translate(
          offset: Offset(shake, 0),
          child: Transform.rotate(
            angle: animation.isAnimating ? t * math.pi * 2 * turns : 0,
            child: DiceFace(value: value, size: size),
          ),
        );
      },
    );
  }
}
