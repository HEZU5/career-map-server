import '../domain/game_room.dart';
import 'game_api.dart';
import 'game_socket.dart';

/// Truy cập API phòng. Dùng chung `GameApi` để tái sử dụng
/// cơ chế online/offline + đóng `http.Client`.
class RoomApi {
  RoomApi({GameApi? api}) : _api = api ?? HttpGameApi();

  final GameApi _api;

  /// [isPrivate] = phòng riêng tư — không hiện trong danh sách phòng công khai
  /// (`GET /api/rooms`) nhưng vẫn vào được bằng mã phòng.
  Future<ApiResult<GameRoom>> create(String displayName, {bool isPrivate = false}) async {
    final result = await _api.post('/api/rooms', {'hostName': displayName, 'isPrivate': isPrivate});
    return _toRoom(result);
  }

  /// Phòng công khai còn mở (chưa bắt đầu, chưa đủ người, mới tạo) — danh
  /// sách để người chơi tìm và vào. Phòng riêng tư không có ở đây.
  Future<ApiResult<List<OpenRoom>>> listOpen() async {
    final result = await _api.getList('/api/rooms/open');
    return switch (result) {
      ApiSuccess<List<Map<String, dynamic>>>(:final data) => ApiSuccess(
        data.map(OpenRoom.fromJson).toList(),
      ),
      ApiFailure<List<Map<String, dynamic>>>(:final message) => ApiFailure(message),
    };
  }

  Future<ApiResult<GameRoom>> join(String code, String displayName) async {
    final result = await _api.post('/api/rooms/${code.trim().toUpperCase()}/players', {
      'displayName': displayName,
    });
    return _toRoom(result);
  }

  Future<ApiResult<GameRoom>> fetch(String code) async {
    return _toRoom(await _api.get('/api/rooms/${code.trim().toUpperCase()}'));
  }

  /// Chủ phòng bấm bắt đầu — server kích hoạt ván và đẩy mọi người qua WS.
  Future<ApiResult<GameRoom>> start(String code) async {
    return _toRoom(await _api.post('/api/rooms/${code.trim().toUpperCase()}/start', const {}));
  }

  /// Đổi nhân vật (đổi tên hiển thị) khi đang ở phòng chờ.
  Future<ApiResult<GameRoom>> rename(String code, String playerKey, String name) async {
    return _toRoom(
      await _api.put(
        '/api/rooms/${code.trim().toUpperCase()}/players/${Uri.encodeComponent(playerKey)}',
        {'name': name},
      ),
    );
  }

  /// Chọn nhân vật ở phòng chờ. Gọi API riêng chứ không dùng [rename] — rename
  /// đổi TÊN NGƯỜI CHƠI, dùng nó để chọn nhân vật sẽ xoá mất tên thật.
  Future<ApiResult<GameRoom>> pickCharacter(
    String code,
    String playerKey,
    String characterName,
  ) async {
    return _toRoom(
      await _api.put(
        '/api/rooms/${code.trim().toUpperCase()}/players/${Uri.encodeComponent(playerKey)}/character',
        {'characterName': characterName},
      ),
    );
  }

  /// Người chơi rời phòng/trận — server đánh dấu thoát và bỏ qua lượt của họ.
  Future<ApiResult<GameRoom>> leave(String code, String playerKey) async {
    return _toRoom(
      await _api.delete(
        '/api/rooms/${code.trim().toUpperCase()}/players/${Uri.encodeComponent(playerKey)}',
      ),
    );
  }

  /// Ảnh chụp state ván hiện tại — dùng khi WebSocket rớt/chậm:
  /// client poll mỗi vài giây vẫn khớp được state dù không nhận GAME.
  Future<ApiResult<GameSnapshot>> fetchGameSnapshot(String code) async {
    final result = await _api.get('/api/rooms/${code.trim().toUpperCase()}/snapshot');
    return switch (result) {
      ApiSuccess<Map<String, dynamic>>(:final data) => ApiSuccess<GameSnapshot>(
        GameSnapshot.fromJson(data),
      ),
      ApiFailure<Map<String, dynamic>>(:final message) => ApiFailure<GameSnapshot>(message),
    };
  }

  /// Chủ phòng đóng phòng giữa ván. Server xóa hẳn phòng và bắn `ROOM_CLOSED`
  /// cho mọi người còn ở đó.
  ///
  /// `playerKey` đi qua query string vì đây là thao tác xác thực chủ phòng, không
  /// phải nội dung cần lưu.
  Future<ApiResult<Map<String, dynamic>>> closeRoom(String code, String playerKey) async {
    final path = Uri.parse('/api/rooms/${code.trim().toUpperCase()}').replace(
      queryParameters: {'playerKey': playerKey},
    );
    return _api.delete(path.toString());
  }

  /// Báo còn sống — tín hiệu duy nhất server có về người chơi đang ở trong phòng.
  /// Trả `true` nếu phòng còn tồn tại (server đã báo giờ để hiệu chỉnh đồng hồ).
  Future<bool> heartbeat(String code, String playerKey) async {
    final path = Uri.parse('/api/rooms/${code.trim().toUpperCase()}/heartbeat').replace(
      queryParameters: {'playerKey': playerKey},
    );
    final result = await _api.post(path.toString(), const {});
    return switch (result) {
      ApiSuccess(:final data) => data['roomClosed'] != true,
      ApiFailure() => false,
    };
  }

  ApiResult<GameRoom> _toRoom(ApiResult<Map<String, dynamic>> result) {
    return switch (result) {
      ApiSuccess<Map<String, dynamic>>(:final data) => ApiSuccess<GameRoom>(
        GameRoom.fromJson(data),
      ),
      ApiFailure<Map<String, dynamic>>(:final message) => ApiFailure<GameRoom>(message),
    };
  }

  void dispose() => _api.dispose();
}
