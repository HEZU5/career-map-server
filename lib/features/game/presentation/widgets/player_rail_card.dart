import 'package:flutter/material.dart';

import '../../../../core/theme/tokens.dart';
import '../../domain/player.dart';
import '../../domain/riasec.dart';
import 'dice_face.dart';
import 'player_corner.dart';

/// Thẻ người chơi dọc, dùng trong RAIL TRÁI / RAIL PHẢI (không nằm trên bàn).
///
/// Desktop: avatar + tên (cắt ngắn) + dải điểm 6 nhóm + Điểm chiến thắng.
/// Mobile ngang: thu gọn còn avatar tròn ~40px + badge điểm nhỏ; chạm mở popup.
///
/// Người đang tới lượt: viền sáng + phóng nhẹ + nhãn "Đến lượt".
/// Chính mình: nhãn "Bạn".
/// Đang tung (có [diceBadge]) → badge mặt xúc xắc trên avatar, tới hết lượt.
class PlayerRailCard extends StatelessWidget {
  const PlayerRailCard({
    super.key,
    required this.player,
    required this.active,
    this.isMe = false,
    this.onTap,
    this.asset,
    this.diceBadge = 0,
    this.compact = false,
    this.showGroupChips = false,
    this.layout = PlayerCardLayout.rail,
  });

  final Player player;
  final bool active;
  final bool isMe;
  final ValueChanged<Player>? onTap;
  final String? asset;

  /// > 0 → hiện badge mặt xúc xắc (người vừa tung, tới hết lượt).
  final int diceBadge;

  /// Mobile ngang: thu gọn thành avatar tròn + badge điểm.
  final bool compact;

  /// Desktop: thêm lưới nhóm RIASEC nhỏ (2x3) bên dưới dải điểm.
  final bool showGroupChips;

  /// [PlayerCardLayout.rail] = thẻ dọc trong rail; [PlayerCardLayout.row] =
  /// thẻ nằm ngang trong hàng thẻ ở màn dọc.
  final PlayerCardLayout layout;

  @override
  Widget build(BuildContext context) {
    final color = player.left ? AppColors.leftOverlay : player.color;
    if (compact) return _compact(color);
    if (layout == PlayerCardLayout.row) return _row(color);
    return _full(color);
  }

  /// Bản desktop: thẻ dọc đầy đủ.
  Widget _full(Color color) {
    return AnimatedScale(
      scale: active && !player.left ? 1.04 : 1,
      duration: AppMotion.normal,
      child: AnimatedContainer(
        duration: AppMotion.normal,
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(Rad.lg),
          border: Border.all(
            color: active && !player.left ? color : Colors.black12,
            width: active && !player.left ? 3 : 1,
          ),
          boxShadow: active && !player.left
              ? [BoxShadow(color: color.withValues(alpha: 0.35), blurRadius: 10, spreadRadius: 1)]
              : null,
        ),
        child: Material(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(Rad.lg),
          child: InkWell(
            borderRadius: BorderRadius.circular(Rad.lg),
            onTap: onTap == null ? null : () => onTap!(player),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: Gap.md, vertical: Gap.md),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  _avatar(color, size: 34),
                  const SizedBox(height: Gap.sm),
                  Text(
                    player.name,
                    maxLines: 2,
                    textAlign: TextAlign.center,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 11.5,
                      height: 1.15,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: Gap.xs),
                  _scoreBar(),
                  const SizedBox(height: Gap.xs),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      const Icon(Icons.emoji_events, size: 12, color: AppColors.gold),
                      const SizedBox(width: 2),
                      Text(
                        '${player.winPoints}',
                        style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w900),
                      ),
                    ],
                  ),
                  if (showGroupChips) ...[const SizedBox(height: Gap.sm), _groupChips()],
                  _tags(color),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// Bản màn DỌC: thẻ nằm ngang trong hàng thẻ dưới bàn.
  Widget _row(Color color) {
    final tags = _tagStack(color, compact: true);
    return AnimatedScale(
      scale: active && !player.left ? 1.03 : 1,
      duration: AppMotion.normal,
      child: AnimatedContainer(
        duration: AppMotion.normal,
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(Rad.md),
          border: Border.all(
            color: active && !player.left ? color : Colors.black12,
            width: active && !player.left ? 2.5 : 1,
          ),
          boxShadow: active && !player.left
              ? [BoxShadow(color: color.withValues(alpha: 0.35), blurRadius: 8)]
              : null,
        ),
        child: Material(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(Rad.md),
          child: InkWell(
            borderRadius: BorderRadius.circular(Rad.md),
            onTap: onTap == null ? null : () => onTap!(player),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: Gap.sm, vertical: Gap.sm),
              child: Row(
                children: [
                  Stack(
                    clipBehavior: Clip.none,
                    children: [
                      _avatar(color, size: 36),
                      if (diceBadge > 0)
                        Positioned(top: -5, right: -6, child: DiceFace(value: diceBadge, size: 18)),
                      if (tags != SizedBox.shrink()) Positioned(bottom: -7, left: -4, child: tags),
                    ],
                  ),
                  const SizedBox(width: Gap.sm),
                  Expanded(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          player.name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 11.5, fontWeight: FontWeight.w800),
                        ),
                        const SizedBox(height: 2),
                        _scoreBar(),
                        const SizedBox(height: 2),
                        Row(
                          children: [
                            const Icon(Icons.emoji_events, size: 11, color: AppColors.gold),
                            const SizedBox(width: 2),
                            Text(
                              '${player.winPoints}',
                              style: const TextStyle(fontSize: 10.5, fontWeight: FontWeight.w900),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// Bản mobile ngang: avatar tròn + badge điểm, chạm mở popup.
  Widget _compact(Color color) {
    return Tooltip(
      message: '${player.name} • 🏆 ${player.winPoints}',
      child: InkWell(
        customBorder: const CircleBorder(),
        onTap: onTap == null ? null : () => onTap!(player),
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            AnimatedContainer(
              duration: const Duration(milliseconds: 260),
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: Colors.white,
                border: Border.all(
                  color: active && !player.left ? color : Colors.black26,
                  width: active && !player.left ? 3 : 1.5,
                ),
                boxShadow: [
                  if (active && !player.left)
                    BoxShadow(color: color.withValues(alpha: 0.45), blurRadius: 8),
                ],
              ),
              child: ClipOval(child: _avatar(color, size: 44, avatarOnly: true)),
            ),
            // Badge mặt xúc xắc (người vừa tung).
            if (diceBadge > 0)
              Positioned(top: -4, right: -4, child: DiceFace(value: diceBadge, size: 18)),
            // Badge điểm chiến thắng nhỏ góc dưới.
            Positioned(
              bottom: -2,
              right: -2,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
                decoration: BoxDecoration(
                  color: AppColors.violet,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.white, width: 1),
                ),
                child: Text(
                  '${player.winPoints}',
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 8,
                    fontWeight: FontWeight.w900,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _avatar(Color color, {required double size, bool avatarOnly = false}) {
    return CircleAvatar(
      radius: size / 2,
      backgroundColor: color,
      backgroundImage: asset == null ? null : AssetImage(asset!),
      child: asset == null
          ? Text(
              player.left ? '✕' : (player.name.isNotEmpty ? player.name[0] : '?'),
              style: TextStyle(
                color: Colors.white,
                fontSize: size * 0.42,
                fontWeight: FontWeight.w900,
              ),
            )
          : null,
    );
  }

  Widget _scoreBar() {
    return Row(
      children: [
        for (final e in Riasec.values)
          Expanded(
            child: Container(
              height: 4,
              margin: const EdgeInsets.symmetric(horizontal: 0.5),
              decoration: BoxDecoration(
                color: e.color.withValues(alpha: (player.scores[e] ?? 0) == 0 ? 0.22 : 1),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
      ],
    );
  }

  Widget _groupChips() {
    return Column(
      children: [
        for (final row in [Riasec.values.take(3).toList(), Riasec.values.skip(3).toList()])
          Padding(
            padding: const EdgeInsets.only(bottom: 2),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                for (final g in row)
                  Container(
                    width: 15,
                    height: 15,
                    margin: const EdgeInsets.symmetric(horizontal: 1),
                    decoration: BoxDecoration(
                      color: g.color.withValues(alpha: 0.2),
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: Center(
                      child: Text(
                        g.code,
                        style: TextStyle(
                          fontSize: 8.5,
                          fontWeight: FontWeight.w900,
                          color: g.color,
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
      ],
    );
  }

  /// Nhãn trạng thái: "Đến lượt" (đang tới lượt) / "Bạn" (chính mình).
  /// [compact] xếp cạnh nhau thay vì chồng dọc (dùng ở thẻ nằm ngang).
  Widget _tags(Color color) => _tagStack(color);

  Widget _tagStack(Color color, {bool compact = false}) {
    final tags = <Widget>[];
    if (active && !player.left) {
      tags.add(_tagChip('Đến lượt', color, compact: compact));
    }
    if (isMe) {
      tags.add(_tagChip('Bạn', AppColors.violet, compact: compact));
    }
    if (compact) {
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: 2),
        child: Row(mainAxisSize: MainAxisSize.min, children: tags),
      );
    }
    return Column(mainAxisSize: MainAxisSize.min, children: tags);
  }

  Widget _tagChip(String text, Color color, {bool compact = false}) {
    return Container(
      margin: EdgeInsets.only(top: compact ? 0 : 5, right: 2),
      padding: EdgeInsets.symmetric(horizontal: compact ? 3 : 6, vertical: 1),
      decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(8)),
      child: Text(
        text,
        style: TextStyle(
          color: Colors.white,
          fontSize: compact ? 7.5 : 8.5,
          fontWeight: FontWeight.w900,
        ),
      ),
    );
  }
}

/// Cách xếp thẻ người chơi.
enum PlayerCardLayout {
  /// Thẻ dọc trong rail trái/phải.
  rail,

  /// Thẻ nằm ngang trong hàng thẻ ở màn dọc.
  row,
}

/// Lưới nhóm RIASEC dạng 2 hàng x 3 cột (dải RIASEC riêng trong rail, hoặc
/// dưới thẻ "Bạn"). Tự thu nhỏ khi bề rộng hẹp (rail mobile ngang ~54px).
class RiasecGridChips extends StatelessWidget {
  const RiasecGridChips({super.key, required this.onTap, this.title = 'NHÓM'});

  final ValueChanged<Riasec> onTap;
  final String title;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, c) {
        final dense = c.maxWidth < 90;
        return Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (!dense) ...[
              Text(
                title,
                style: const TextStyle(
                  fontSize: 9,
                  fontWeight: FontWeight.w900,
                  color: AppColors.primaryDark,
                  letterSpacing: 0.8,
                ),
              ),
              const SizedBox(height: Gap.xs),
            ],
            // 2 hàng x 3 cột = 6 nhóm R I A S E C.
            for (final row in [Riasec.values.take(3).toList(), Riasec.values.skip(3).toList()])
              Padding(
                padding: EdgeInsets.only(bottom: dense ? 2 : 2),
                child: Row(
                  children: [
                    for (final g in row)
                      Expanded(
                        child: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 1.5),
                          child: Tooltip(
                            message: '${g.code} · ${g.displayName}',
                            child: Material(
                              color: g.color.withValues(alpha: 0.2),
                              borderRadius: BorderRadius.circular(Rad.sm),
                              child: InkWell(
                                borderRadius: BorderRadius.circular(Rad.sm),
                                onTap: () => onTap(g),
                                child: Padding(
                                  padding: EdgeInsets.symmetric(vertical: dense ? 2 : Gap.sm),
                                  child: Center(
                                    child: Text(
                                      g.code,
                                      style: TextStyle(
                                        fontSize: dense ? 9 : 11,
                                        fontWeight: FontWeight.w900,
                                        color: g.color,
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
          ],
        );
      },
    );
  }
}

/// Dải RIASEC nằm ngang 1 hàng (dùng ở màn dọc, dưới hàng thẻ người chơi).
class RiasecStrip extends StatelessWidget {
  const RiasecStrip({super.key, required this.onTap});

  final ValueChanged<Riasec> onTap;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 34,
      child: Row(
        children: [
          for (final g in Riasec.values)
            Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 2),
                child: Tooltip(
                  message: '${g.code} · ${g.displayName}',
                  child: Material(
                    color: g.color.withValues(alpha: 0.2),
                    borderRadius: BorderRadius.circular(Rad.sm),
                    child: InkWell(
                      borderRadius: BorderRadius.circular(Rad.sm),
                      onTap: () => onTap(g),
                      child: Center(
                        child: Text(
                          g.code,
                          style: TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w900,
                            color: g.color,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// Đường cung cấp ảnh nhân vật (tái sử dụng helper có sẵn từ player_corner.dart).
String? railCharacterAsset(Player player, int index) =>
    characterAssetOf(player, fallbackIndex: index);
