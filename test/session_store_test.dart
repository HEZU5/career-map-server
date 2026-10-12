import 'package:career_map/core/storage/session_store.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('SessionStore', () {
    test('rỗng khi chưa lưu gì', () {
      final session = SessionStore.memory();
      expect(session.playerName, isNull);
      expect(session.activeRoom, isNull);
    });

    test('nhớ tên qua các lần mở app', () {
      final session = SessionStore.memory();
      session.savePlayerName('Minh Anh');
      expect(
        SessionStore.memory().playerName,
        isNull,
        reason: 'store khác nhau',
      );
    });

    test('saveActiveRoom + activeRoom trả đúng mã và playerKey', () {
      final session = SessionStore.memory()
        ..saveActiveRoom('H12345', 'guest-H12345-1');
      expect(session.activeRoom, (
        roomCode: 'H12345',
        playerKey: 'guest-H12345-1',
      ));
    });

    test('clearActiveRoom giữ tên nhưng xoá phòng', () {
      final session = SessionStore.memory()
        ..savePlayerName('An')
        ..saveActiveRoom('H12345', 'host-H12345')
        ..clearActiveRoom();

      expect(session.activeRoom, isNull);
      expect(session.playerName, 'An', reason: 'tên không liên quan tới phòng');
    });

    test('chỉ có mã phòng mà thiếu playerKey thì coi như phiên hỗng', () {
      // Nếu chấp nhận, người chơi vào phòng với playerKey rỗng -> server coi là
      // khách mới và không khớp lượt nào.
      final store = _FakeStore({'session.roomCode': 'H12345'});
      expect(SessionStore(store).activeRoom, isNull);
    });

    test('playerKey rỗng cũng bị coi là phiên hỗng', () {
      final store = _FakeStore({
        'session.roomCode': 'H12345',
        'session.playerKey': '',
      });
      expect(SessionStore(store).activeRoom, isNull);
    });
  });
}

/// Store tối giản để dựng phiên hỗng mà không qua [SessionStore.memory].
class _FakeStore implements KeyValueStore {
  _FakeStore(this.values);
  final Map<String, String> values;

  @override
  String? getString(String key) => values[key];

  @override
  void setString(String key, String value) => values[key] = value;

  @override
  void remove(String key) => values.remove(key);
}
