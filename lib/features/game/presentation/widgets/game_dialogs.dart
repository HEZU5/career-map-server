import 'package:flutter/material.dart';

import '../../../../core/theme/tokens.dart';
import '../../domain/player.dart';
import '../../domain/riasec.dart';

/// Dialog chi tiết điểm RIASEC của 1 người chơi.
/// Code cũ build `Wrap` + `Chip` inline trong `_GamePageState`.
class PlayerDetailsDialog extends StatelessWidget {
  const PlayerDetailsDialog({super.key, required this.player});

  final Player player;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(player.name),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: Gap.md,
            runSpacing: Gap.md,
            children: [
              for (final entry in player.scores.entries)
                Chip(
                  label: Text('${entry.key.code}: ${entry.value}'),
                  backgroundColor: entry.key.color.withValues(alpha: 0.2),
                ),
            ],
          ),
          const SizedBox(height: 10),
          Text(
            'Điểm chiến thắng: ${player.winPoints}',
            style: const TextStyle(fontWeight: FontWeight.bold),
          ),
        ],
      ),
      actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Đóng'))],
    );
  }
}

/// Dialog thông tin 1 nhóm Holland + điểm của cả bàn.
class GroupInfoDialog extends StatelessWidget {
  const GroupInfoDialog({super.key, required this.group, required this.players});

  final Riasec group;
  final List<Player> players;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Material(
        color: Colors.transparent,
        child: Container(
          width: 360,
          padding: const EdgeInsets.all(Gap.xxl),
          decoration: BoxDecoration(color: group.color, borderRadius: BorderRadius.circular(Rad.xl)),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(group.icon, color: Colors.white, size: 50),
              const SizedBox(height: 8),
              Text(
                '${group.code} - ${group.displayName}',
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 25,
                  fontWeight: FontWeight.w900,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                group.description,
                textAlign: TextAlign.center,
                style: const TextStyle(color: Colors.white),
              ),
              const SizedBox(height: 16),
              for (final p in players)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 2),
                  child: Row(
                    children: [
                      CircleAvatar(
                        radius: 13,
                        backgroundColor: p.color,
                        child: Text(p.name[0], style: const TextStyle(color: Colors.white)),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(p.name, style: const TextStyle(color: Colors.white)),
                      ),
                      Text(
                        '${p.scores[group]} điểm',
                        style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
                      ),
                    ],
                  ),
                ),
              const SizedBox(height: 12),
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('ĐÓNG', style: TextStyle(color: Colors.white)),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
