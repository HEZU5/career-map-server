import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../../core/theme/tokens.dart';
import '../../domain/player.dart';
import 'player_corner.dart';
import 'player_rail_card.dart';

/// Rail bên (trái/phải) của bố cục 3 vùng: railTrái | Bàn cờ | railPhải.
///
/// Chứa (theo thứ tự dọc):
///   1. [leading] — nút phụ đầu rail (thoát/camera bên trái, fullscreen/help
///      bên phải), mỗi nút ≥ 44x44.
///   2. Thẻ người chơi của phía này, CHIA ĐỀU hai bên (4 người → 2 trái,
///      2 phải). Desktop: thẻ dọc đầy đủ. Mobile ngang: avatar tròn ~44px.
///   3. [trailing] — mã phòng / dải nhóm RIASEC (chỉ rail trái).
///
/// Rail nằm NGOÀI bàn cờ nên không thể che ô nào.
class GameRail extends StatelessWidget {
  const GameRail({
    super.key,
    required this.allPlayers,
    required this.activeIndex,
    required this.myPlayerKey,
    required this.side,
    this.leading = const [],
    this.trailing = const [],
    this.compact = false,
    this.onTapPlayer,
    this.diceBadgeFor = _noBadge,
    this.railWidth = 150,
  });

  /// DANH SÁCH ĐẦY ĐỦ người chơi (đúng thứ tự server) — rail tự chia đều
  /// theo [activeIndex] để [activeIndex] luôn khớp.
  final List<Player> allPlayers;

  final int activeIndex;
  final String? myPlayerKey;

  /// Phía rail: trái nhận nửa đầu danh sách, phải nhận nửa sau.
  final RailSide side;

  final List<Widget> leading;
  final List<Widget> trailing;
  final bool compact;
  final ValueChanged<Player>? onTapPlayer;

  /// Mặt xúc xắc (>0) của người vừa tung → badge trên thẻ.
  final int Function(Player p) diceBadgeFor;

  final double railWidth;

  static int _noBadge(Player _) => 0;

  @override
  Widget build(BuildContext context) {
    final players = allPlayers;
    final half = (players.length / 2).ceil();
    final mine = side == RailSide.left ? players.take(half).toList() : players.skip(half).toList();
    final globalStart = side == RailSide.left ? 0 : half;

    return Container(
      color: Colors.white.withValues(alpha: 0.6),
      child: SafeArea(
        right: side == RailSide.left,
        child: Padding(
          padding: EdgeInsets.symmetric(horizontal: compact ? Gap.xs : Gap.sm, vertical: compact ? Gap.xs : Gap.md),
          child: Column(
            children: [
              ...leading,
              if (leading.isNotEmpty) SizedBox(height: compact ? Gap.sm : Gap.md),
              Expanded(
                child: SingleChildScrollView(
                  child: Column(
                    children: [
                      for (var i = 0; i < mine.length; i++)
                        Padding(
                          padding: EdgeInsets.only(bottom: compact ? Gap.md : Gap.lg),
                          child: PlayerRailCard(
                            player: mine[i],
                            active: globalStart + i == activeIndex,
                            isMe: mine[i].id == myPlayerKey,
                            onTap: onTapPlayer,
                            asset: characterAssetOf(mine[i], fallbackIndex: globalStart + i),
                            diceBadge: diceBadgeFor(mine[i]),
                            compact: compact,
                          ),
                        ),
                    ],
                  ),
                ),
              ),
              if (trailing.isNotEmpty) ...[SizedBox(height: compact ? Gap.sm : Gap.md), ...trailing],
            ],
          ),
        ),
      ),
    );
  }
}

/// Phía rail.
enum RailSide { left, right }

/// Nút icon tròn dùng chung trong rail — vùng bấm ≥ 44x44.
class RailIconButton extends StatelessWidget {
  const RailIconButton({
    super.key,
    required this.icon,
    required this.tooltip,
    required this.onPressed,
    this.color = AppColors.violet,
    this.size = 20,
  });

  final IconData icon;
  final String tooltip;
  final VoidCallback? onPressed;
  final Color color;
  final double size;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: Material(
        color: onPressed == null ? color.withValues(alpha: 0.4) : color,
        shape: const CircleBorder(),
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: onPressed,
          child: SizedBox(
            width: 44,
            height: 44,
            child: Icon(icon, color: Colors.white, size: size),
          ),
        ),
      ),
    );
  }
}

/// Chip mã phòng dọc trong rail trái (chuyển từ đè lên đỉnh bàn sang rail).
class RoomCodeChipVertical extends StatelessWidget {
  const RoomCodeChipVertical({super.key, required this.code});

  final String code;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: 'Mã phòng — chạm để copy',
      child: Material(
        color: Colors.white,
        borderRadius: BorderRadius.circular(Rad.md),
        elevation: 1,
        child: InkWell(
          borderRadius: BorderRadius.circular(Rad.md),
          onTap: () => _copy(context),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: Gap.sm, horizontal: Gap.xs),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.group_add, size: 15, color: AppColors.violet),
                const SizedBox(height: 3),
                Text(
                  code,
                  style: const TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w900,
                    color: AppColors.violet,
                    letterSpacing: 1,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _copy(BuildContext context) async {
    await Clipboard.setData(ClipboardData(text: code));
    if (!context.mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text('Đã copy mã phòng $code')));
  }
}
