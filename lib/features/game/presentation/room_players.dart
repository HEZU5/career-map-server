import 'package:flutter/material.dart';

import '../../../core/theme/app_colors.dart';
import '../domain/game_room.dart';
import '../domain/player.dart';

const List<Color> _cornerColors = [
  AppColors.player1,
  AppColors.player2,
  AppColors.player3,
  AppColors.player4,
];

const List<(String, Color)> _cornerSamples = [
  ('Minh Anh', AppColors.player2),
  ('Gia Huy', AppColors.player3),
  ('Bảo Ngọc', AppColors.player4),
];

/// Lấy 4 người chơi cho bàn cờ: người chơi trong phòng xếp trước,
/// thiếu thì chèn bạn bè mẫu để luôn đủ 4 góc.
List<Player> playersFromRoom(GameRoom room) {
  final result = <Player>[];
  for (var i = 0; i < room.players.length; i++) {
    final rp = room.players[i];
    result.add(
      Player(
        id: rp.playerKey,
        name: rp.name,
        color: _cornerColors[i % _cornerColors.length],
        left: rp.left,
      ),
    );
  }
  for (var i = room.players.length; i < 4; i++) {
    final sample = _cornerSamples[i - 1];
    result.add(Player(id: 'p${i + 1}', name: sample.$1, color: sample.$2));
  }
  return List<Player>.unmodifiable(result);
}

/// Bàn chơi 4 người chơi local (chơi không có server),
/// người chơi tự chọn nhân vật ngồi góc 1.
List<Player> buildLocalPlayers({required String selfName, required Color selfColor}) {
  return List<Player>.unmodifiable([
    Player(id: 'p1', name: selfName, color: selfColor),
    for (var i = 0; i < _cornerSamples.length; i++)
      Player(id: 'p${i + 2}', name: _cornerSamples[i].$1, color: _cornerSamples[i].$2),
  ]);
}

/// Bàn chơi online: đúng số người trong phòng (không chèn bạn bè mẫu),
/// mỗi người một góc theo thứ tự server trả về.
List<Player> gamePlayersFromRoom(GameRoom room) {
  return List<Player>.unmodifiable([
    for (var i = 0; i < room.players.length; i++)
      Player(
        id: room.players[i].playerKey,
        name: room.players[i].name,
        characterName: room.players[i].characterName,
        color: _cornerColors[i % _cornerColors.length],
        left: room.players[i].left,
      ),
  ]);
}
