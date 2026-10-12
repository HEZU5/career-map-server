import 'package:shared_preferences/shared_preferences.dart';

/// Lưu chuỗi, đủ dùng cho phiên người chơi. Có hai bản hiện thực: bọc
/// `SharedPreferences` (localStorage trên web, đọc được sau F5) và bộ nhớ tạm
/// cho test.
abstract class KeyValueStore {
  String? getString(String key);
  void setString(String key, String value);
  void remove(String key);
}

class _PrefsStore implements KeyValueStore {
  const _PrefsStore(this._prefs);
  final SharedPreferences _prefs;

  @override
  String? getString(String key) => _prefs.getString(key);

  @override
  void setString(String key, String value) => _prefs.setString(key, value);

  @override
  void remove(String key) => _prefs.remove(key);
}

class _MemoryStore implements KeyValueStore {
  final Map<String, String> _values = {};

  @override
  String? getString(String key) => _values[key];

  @override
  void setString(String key, String value) => _values[key] = value;

  @override
  void remove(String key) => _values.remove(key);
}

/// Phiên của người chơi **trên thiết bị này** — KHÔNG cần đăng nhập.
///
/// Vì sao không cần login: toàn bộ ván đã nằm trên server (Postgres), client
/// chỉ giữ "tôi là ai" và "tôi đang ở phòng nào". Hai thứ đó vừa đủ để sau khi
/// bấm F5 (hay đóng tab rồi mở lại) quay lại đúng chỗ đang chơi thay vì mất
/// sạch. Đăng nhập chỉ cần khi muốn chơi **nhiều thiết bị** cùng lúc.
class SessionStore {
  SessionStore(this._store);

  /// Bộ nhớ tạm cho test widget (không cần SharedPreferences thật).
  factory SessionStore.memory([Map<String, String>? seed]) {
    final store = _MemoryStore();
    seed?.forEach(store.setString);
    return SessionStore(store);
  }

  final KeyValueStore _store;

  static const _kName = 'session.playerName';
  static const _kRoomCode = 'session.roomCode';
  static const _kPlayerKey = 'session.playerKey';

  /// Nạp phiên đã lưu. Gọi một lần ở `main()` rồi truyền xuống widget để mọi
  /// chỗ đọc/ghi đều ĐỒNG BỘ (không phải `await` giữa lúc dựng UI).
  static Future<SessionStore> load() async =>
      SessionStore(_PrefsStore(await SharedPreferences.getInstance()));

  String? get playerName => _store.getString(_kName);

  /// Phòng đang chơi: mã phòng + chìa khoá người chơi trên server.
  ///
  /// Cả hai phải cùng tồn tại thì mới coi là phiên hợp lệ — tránh vào phòng với
  /// `playerKey` rỗng (sẽ thành "khách" và không khớp lượt nào).
  ({String roomCode, String playerKey})? get activeRoom {
    final code = _store.getString(_kRoomCode);
    final key = _store.getString(_kPlayerKey);
    if (code == null || code.isEmpty || key == null || key.isEmpty) return null;
    return (roomCode: code, playerKey: key);
  }

  void savePlayerName(String name) => _store.setString(_kName, name);

  /// Ghi lại phòng vừa tạo/vừa vào. Ghi ở phòng CHỜ là đủ, vì mọi đường vào ván
  /// đều đi qua phòng chờ trước.
  void saveActiveRoom(String roomCode, String playerKey) {
    _store.setString(_kRoomCode, roomCode);
    _store.setString(_kPlayerKey, playerKey);
  }

  /// Xoá phiên phòng (rời phòng, hoặc ván đã kết thúc) để không hiện lại thẻ
  /// "vào lại ván cũ".
  void clearActiveRoom() {
    _store.remove(_kRoomCode);
    _store.remove(_kPlayerKey);
  }
}
