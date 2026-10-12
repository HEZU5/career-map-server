import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../features/welcome/domain/sample_character.dart';
import '../../domain/game_room.dart';
import '../../domain/player.dart';
import '../../domain/riasec.dart';

/// Avatar góc màn hình cho từng người chơi.
/// Nhận [width] để co giãn theo màn hình (điện thoại → desktop).
/// Người chơi đang tới lượt được đánh dấu RÕ bằng viền + dải "TỚI LƯỢT".
class PlayerCorner extends StatelessWidget {
  const PlayerCorner({
    super.key,
    required this.player,
    required this.active,
    required this.alignment,
    required this.onTap,
    this.asset,
    this.width = 108,
  });

  final Player player;
  final bool active;
  final Alignment alignment;
  final ValueChanged<Player> onTap;

  /// Ảnh nhân vật cho avatar góc; [null] → chữ cái đầu tên.
  final String? asset;

  /// Chiều rộng thẻ (tính theo màn hình ở nơi gọi).
  final double width;

  @override
  Widget build(BuildContext context) {
    final gone = player.gone;
    final color = gone ? AppColors.leftOverlay : player.color;
    final avatarText = gone ? '✕' : player.name[0];
    final avatarRadius = width * 0.15;
    // Vắng mặt → giảm bão hoà (còn viền màu riêng để phân biệt quân); đã rời
    // phòng thì tối hẳn như trước.
    final muted = player.presence == Presence.offline || player.presence == Presence.away;
    final avatarOpacity = gone ? 0.45 : (muted ? 0.6 : 1.0);
    return Align(
      alignment: alignment,
      child: Padding(
        padding: EdgeInsets.all(width * 0.08),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 300),
          width: width,
          padding: EdgeInsets.all(width * 0.05),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(width * 0.14),
            border: Border.all(
              color: active && !gone ? color : Colors.black12,
              width: active && !gone ? width * 0.04 : 1,
            ),
            boxShadow: active && !gone
                ? [
                    BoxShadow(
                      color: color.withValues(alpha: 0.45),
                      blurRadius: width * 0.35,
                      spreadRadius: 2,
                    ),
                  ]
                : const [BoxShadow(color: Colors.black12, blurRadius: 4)],
          ),
          child: InkWell(
            onTap: () => onTap(player),
            borderRadius: BorderRadius.circular(width * 0.1),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(
                  children: [
                    Opacity(
                      opacity: avatarOpacity,
                      child: CircleAvatar(
                        radius: avatarRadius,
                        backgroundColor: color,
                        backgroundImage: asset == null ? null : AssetImage(asset!),
                        child: asset == null
                            ? Text(
                                avatarText,
                                style: TextStyle(
                                  color: Colors.white,
                                  fontSize: avatarRadius,
                                  fontWeight: FontWeight.bold,
                                ),
                              )
                            : null,
                      ),
                    ),
                    SizedBox(width: width * 0.05),
                    Expanded(
                      child: Text(
                        player.name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: width * 0.13,
                          fontWeight: FontWeight.bold,
                          color: gone ? AppColors.leftOverlay : null,
                        ),
                      ),
                    ),
                    // Nhãn trạng thái: vắng mặt / rời phòng / máy điều khiển.
                    // Chỉ hiện khi cần — người đang online thì không ghi gì.
                    if (!gone && (muted || player.bot))
                      _PresenceBadge(presence: player.presence, bot: player.bot, size: width),
                  ],
                ),
                SizedBox(height: width * 0.06),
                Row(
                  children: [
                    for (final e in Riasec.values)
                      Expanded(
                        child: Container(
                          height: width * 0.045,
                          margin: const EdgeInsets.symmetric(horizontal: 1),
                          color: e.color.withValues(alpha: (player.scores[e] ?? 0) == 0 ? 0.25 : 1),
                        ),
                      ),
                  ],
                ),
                if (active && !gone) ...[
                  SizedBox(height: width * 0.06),
                  Container(
                    padding: EdgeInsets.symmetric(horizontal: width * 0.08, vertical: width * 0.02),
                    decoration: BoxDecoration(
                      color: color,
                      borderRadius: BorderRadius.circular(width * 0.2),
                    ),
                    child: Text(
                      'TỚI LƯỢT',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: width * 0.085,
                        fontWeight: FontWeight.w900,
                        letterSpacing: 1,
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Nhãn nhỏ trạng thái hiện diện: "Vắng mặt" / "Máy đang đi" (bot).
///
/// Chỉ hiện khi trạng thái khác `online`, để màn bình thường không bị rối chữ.
class _PresenceBadge extends StatelessWidget {
  const _PresenceBadge({required this.presence, required this.bot, required this.size});

  final Presence presence;
  final bool bot;
  final double size;

  @override
  Widget build(BuildContext context) {
    final away = presence == Presence.away;
    final label = bot ? 'Máy đang đi' : (away ? 'Vắng mặt' : 'Mất kết nối');
    final color = away ? AppColors.gold : AppColors.leftOverlay;
    return Semantics(
      label: label,
      excludeSemantics: true,
      child: Container(
        margin: EdgeInsets.only(left: size * 0.04),
        padding: EdgeInsets.symmetric(horizontal: size * 0.05, vertical: size * 0.015),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.16),
          borderRadius: BorderRadius.circular(999),
          border: Border.all(color: color, width: 1),
        ),
        child: Text(
          label,
          style: TextStyle(
            color: color,
            fontSize: size * 0.075,
            fontWeight: FontWeight.w700,
            height: 1.1,
          ),
        ),
      ),
    );
  }
}

/// Con tốt trên bàn cờ. Bán kính [radius] theo % bàn → co giãn cùng bàn cờ.
/// Dùng ảnh nhân vật làm hình quân cờ; quân bị rời phòng hiện ✕ + tối.
const List<String> characterAssets = [
  'assets/Asset 3.png',
  'assets/Asset 5.png',
  'assets/Asset 6.png',
  'assets/Asset 7.png',
];

/// Ảnh nhân vật cho người chơi thứ [index] (xoay vòng 4 hình).
String characterAssetFor(int index) => characterAssets[index % characterAssets.length];

/// Ảnh nhân vật cho một người chơi: ưu tiên nhân vật họ đã chọn ở phòng
/// chờ (`characterName`); nếu không có thì thử tên (phòng cũ lưu nhân vật trong
/// `name`), cuối cùng dùng ảnh theo [fallbackIndex].
String characterAssetOf(Player player, {int fallbackIndex = 0}) =>
    CareerCharacter.byName(player.characterName ?? player.name)?.assetPath ??
    characterAssetFor(fallbackIndex);

class PlayerPawn extends StatelessWidget {
  const PlayerPawn({
    super.key,
    required this.player,
    required this.active,
    this.asset,
    this.radius = 15,
  });

  final Player player;
  final bool active;

  /// Ảnh nhân vật; [null] → fallback vòng tròn chữ cái như cũ.
  final String? asset;

  /// Bán kính quân cờ.
  final double radius;

  @override
  Widget build(BuildContext context) {
    final gone = player.gone;
    final isHost = player.isHost;
    final color = gone ? AppColors.leftOverlay : player.color;
    // Quân của người vắng mặt mờ đi — vẫn thấy rõ màu riêng, không như đã rời.
    final muted =
        player.presence == Presence.offline || player.presence == Presence.away;
    return Container(
      width: radius * 2,
      height: radius * 2,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: Colors.white,
        shape: BoxShape.circle,
        border: Border.all(
          color: active && !gone ? AppColors.gold : Colors.white,
          width: active ? radius * 0.18 : radius * 0.1,
        ),
        boxShadow: active && !gone
            ? [
                BoxShadow(
                  color: AppColors.gold.withValues(alpha: 0.9),
                  blurRadius: radius * 0.45,
                  spreadRadius: 1,
                ),
              ]
            : const [BoxShadow(color: Colors.black26, blurRadius: 3)],
      ),
      child: Stack(
        fit: StackFit.expand,
        alignment: Alignment.center,
        children: [
          Opacity(
            opacity: gone ? 0.45 : (muted ? 0.65 : 1),
            child: CircleAvatar(
              backgroundColor: color,
              backgroundImage: asset == null ? null : AssetImage(asset!),
              child: asset == null
                  ? Text(
                      player.name[0],
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: radius * 0.7,
                        fontWeight: FontWeight.w900,
                      ),
                    )
                  : null,
            ),
          ),
          if (gone)
            const CircleAvatar(
              backgroundColor: Colors.black54,
              child: Text(
                '✕',
                style: TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w900),
              ),
            ),
          // Chủ phòng / người giữ quyền tạm: vòng vàng nhỏ trên đầu quân.
          if (!gone && isHost)
            Positioned(
              top: 0,
              right: 0,
              child: Semantics(
                label: 'Chủ phòng',
                excludeSemantics: true,
                child: Container(
                  width: radius * 0.62,
                  height: radius * 0.62,
                  alignment: Alignment.center,
                  decoration: const BoxDecoration(
                    color: AppColors.gold,
                    shape: BoxShape.circle,
                  ),
                  child: Icon(
                    Icons.star,
                    size: radius * 0.42,
                    color: Colors.white,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
