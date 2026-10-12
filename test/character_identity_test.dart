import 'package:career_map/features/game/domain/game_room.dart';
import 'package:career_map/features/game/domain/player.dart';
import 'package:career_map/features/game/presentation/room_players.dart';
import 'package:career_map/features/game/presentation/widgets/player_corner.dart';
import 'package:career_map/features/welcome/domain/sample_character.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

CareerCharacter? byNameViaHelper(String? name) => CareerCharacter.byName(name);

/// Nhân vật và tên người chơi là hai thứ KHÁC NHAU. Trước đây UI resolve nhân
/// vật từ `name`, nên chọn nhân vật buộc phải ghi đè tên người chơi — mất tên
/// thật của họ. Các test dưới đây chốt lại ranh giới này.
void main() {
  final character = careerCharacters.first;

  test('4 nhân vật đúng tên và thứ tự đã chốt', () {
    // `name` được lưu vào DB (`players.character_name`) và resolve bằng so
    // khớp chuỗi — đổi tên là phòng cũ mất nhân vật. Chốt lại ở đây.
    expect(
      careerCharacters.map((c) => '${c.name} - ${c.profession}').toList(),
      [
        'MIKA - The Explorer',
        'PIP - The Wonderer',
        'BUBU - The Challenger',
        'KIKI - The Idea Maker',
      ],
    );
  });

  test('mỗi nhân vật có ảnh khác nhau', () {
    final assets = careerCharacters.map((c) => c.assetPath).toSet();
    expect(assets, hasLength(careerCharacters.length));
  });

  test('byName tìm đúng nhân vật, trả null khi chưa chọn', () {
    for (final c in careerCharacters) {
      expect(byNameViaHelper(c.name), c);
    }
    expect(byNameViaHelper(null), isNull);
    expect(byNameViaHelper('Nhân vật 1'), isNull, reason: 'tên cũ không còn hợp lệ');
  });

  test('avatar trên bàn theo nhân vật đã chọn, không theo tên người chơi', () {
    final player = Player(
      id: 'guest-H1-1',
      name: 'Nguyễn Văn A', // tên thật của người chơi
      characterName: character.name,
      color: const Color(0xFF000000),
    );

    expect(characterAssetOf(player), character.assetPath);
  });

  test('chưa chọn nhân vật thì rơi về ảnh theo thứ tự góc, không đoán theo tên', () {
    final player = Player(
      id: 'guest-H1-2',
      name: 'Trần Thị B',
      color: const Color(0xFF000000),
    );

    expect(characterAssetOf(player, fallbackIndex: 2), characterAssetFor(2));
  });

  test('phòng cũ lưu nhân vật trong name thì vẫn vẽ đúng avatar', () {
    // Tương thích dữ liệu cũ: trước khi tách trường, chọn nhân vật ghi tên
    // nhân vật vào `name`.
    final player = Player(
      id: 'guest-H1-3',
      name: character.name,
      color: const Color(0xFF000000),
    );

    expect(characterAssetOf(player), character.assetPath);
  });

  test('danh sách người chơi online giữ cả tên lẫn nhân vật', () {
    final room = GameRoom.fromJson(const {
      'code': 'H1',
      'hostName': 'A',
      'players': [
        {
          'playerKey': 'host-H1',
          'name': 'Nguyễn Văn A',
          'characterName': 'MIKA',
        },
      ],
    });

    final players = gamePlayersFromRoom(room);
    expect(players.single.name, 'Nguyễn Văn A');
    expect(players.single.characterName, 'MIKA');
  });
}