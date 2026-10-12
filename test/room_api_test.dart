import 'package:flutter_test/flutter_test.dart';
import 'package:career_map/features/game/data/game_api.dart';
import 'package:career_map/features/game/data/room_api.dart';
import 'package:career_map/features/game/domain/game_room.dart';

class _FakeGameApi implements GameApi {
  _FakeGameApi(this.response);

  final Map<String, dynamic> response;

  /// Ghi lại body của lần POST gần nhất để assert payload gửi lên server.
  Map<String, dynamic>? lastPostBody;

  /// Ghi lại đường dẫn + body của lần PUT gần nhất (dùng để chọn nhân vật).
  String? lastPutPath;
  Map<String, dynamic>? lastPutBody;

  @override
  Future<ApiResult<List<Map<String, dynamic>>>> getList(String path) async =>
      const ApiSuccess([]);

  @override
  Future<ApiResult<Map<String, dynamic>>> get(String path) async =>
      ApiSuccess(response);

  @override
  Future<ApiResult<Map<String, dynamic>>> post(
    String path,
    Map<String, dynamic> body,
  ) async {
    lastPostBody = body;
    return ApiSuccess(response);
  }

  @override
  Future<ApiResult<Map<String, dynamic>>> delete(String path) async =>
      ApiSuccess(response);

  @override
  Future<ApiResult<Map<String, dynamic>>> put(
    String path,
    Map<String, dynamic> body,
  ) async {
    lastPutPath = path;
    lastPutBody = body;
    return ApiSuccess(response);
  }

  @override
  void dispose() {}
}

void main() {
  group('GameRoom.fromJson', () {
    test('parses room with playerKey/name players', () {
      final room = GameRoom.fromJson(const {
        'code': 'H12345',
        'hostName': 'An',
        'maxPlayers': 4,
        'createdAt': '2026-09-19T00:00:00Z',
        'players': [
          {'playerKey': 'host-H12345', 'name': 'An'},
          {'playerKey': 'guest-H12345-1', 'name': 'Binh'},
        ],
      });

      expect(room.code, 'H12345');
      expect(room.players, hasLength(2));
      expect(room.players[0].playerKey, 'host-H12345');
      expect(room.players[0].name, 'An');
      expect(room.players[1].name, 'Binh');
    });

    test('tolerates missing players', () {
      final room = GameRoom.fromJson(const {
        'code': 'H00000',
        'hostName': 'An',
      });
      expect(room.players, isEmpty);
    });
  });

  group('RoomApi', () {
    test('create maps success to GameRoom', () async {
      final api = RoomApi(
        api: _FakeGameApi(const {
          'code': 'H98765',
          'hostName': 'Bạn',
          'maxPlayers': 4,
          'players': [
            {'playerKey': 'host-H98765', 'name': 'Bạn'},
          ],
        }),
      );

      final result = await api.create('Bạn');
      final room = result as ApiSuccess<GameRoom>;
      expect(room.data.code, 'H98765');
      expect(room.data.players.single.playerKey, 'host-H98765');
    });

    test('create mặc định gửi phòng công khai', () async {
      final fake = _FakeGameApi(const {'code': 'H11111'});
      final api = RoomApi(api: fake);

      await api.create('An');

      expect(fake.lastPostBody!['isPrivate'], isFalse);
    });

    test('create gửi isPrivate = true khi chọn phòng riêng tư', () async {
      final fake = _FakeGameApi(const {'code': 'H22222'});
      final api = RoomApi(api: fake);

      await api.create('An', isPrivate: true);

      expect(fake.lastPostBody!['isPrivate'], isTrue);
      expect(fake.lastPostBody!['hostName'], 'An');
    });

    test('chọn nhân vật gọi endpoint /character, KHÔNG đổi tên người chơi', () async {
      final fake = _FakeGameApi(const {
        'code': 'H33333',
        'players': [
          {
            'playerKey': 'host-H33333',
            'name': 'An',
            'characterName': 'Bác sĩ',
          },
        ],
      });
      final api = RoomApi(api: fake);

      final result = await api.pickCharacter('H33333', 'host-H33333', 'Bác sĩ');

      // Đường dẫn phải là /character chứ không phải /players/{key} của rename.
      expect(fake.lastPutPath, '/api/rooms/H33333/players/host-H33333/character');
      expect(fake.lastPutBody!['characterName'], 'Bác sĩ');
      expect(fake.lastPutBody!.containsKey('name'), isFalse);

      final room = (result as ApiSuccess<GameRoom>).data;
      expect(
        room.players.single.name,
        'An',
        reason: 'chọn nhân vật không được đụng vào tên người chơi',
      );
      expect(room.players.single.characterName, 'Bác sĩ');
    });
  });

  group('RoomPlayer.characterName', () {
    test('đọc từ JSON, rỗng nếu server chưa có field', () {
      final withCharacter = RoomPlayer.fromJson(const {
        'playerKey': 'guest-H1-1',
        'name': 'An',
        'characterName': 'Kỹ sư',
      });
      expect(withCharacter.characterName, 'Kỹ sư');
      expect(withCharacter.name, 'An');

      expect(
        RoomPlayer.fromJson(const {'playerKey': 'k', 'name': 'An'}).characterName,
        isNull,
      );
    });
  });

  group('GameRoom.isPrivate', () {
    test('đọc từ JSON, mặc định false với server cũ', () {
      expect(
        GameRoom.fromJson(const {'code': 'H1'}).isPrivate,
        isFalse,
        reason: 'client cũ/server cũ không có field này thì coi là công khai',
      );
      expect(
        GameRoom.fromJson(const {'code': 'H1', 'isPrivate': true}).isPrivate,
        isTrue,
      );
    });
  });
}
