import 'package:career_map/core/storage/session_store.dart';
import 'package:career_map/features/game/data/game_api.dart';
import 'package:career_map/features/game/data/room_api.dart';
import 'package:career_map/features/welcome/lobby_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// Lobby với API giả: kiểm tra 3 trạng thái danh sách phòng công khai và
/// thẻ "vào lại ván" — không gọi network thật.
class _StubApi implements GameApi {
  _StubApi({this.rooms = const [], this.failure});

  final List<Map<String, dynamic>> rooms;
  final String? failure;

  @override
  Future<ApiResult<List<Map<String, dynamic>>>> getList(String path) async {
    final message = failure;
    if (message != null) {
      return ApiFailure<List<Map<String, dynamic>>>(message);
    }
    return ApiSuccess(rooms);
  }

  @override
  Future<ApiResult<Map<String, dynamic>>> get(String path) async =>
      ApiSuccess(const {});

  @override
  Future<ApiResult<Map<String, dynamic>>> post(
    String path,
    Map<String, dynamic> body,
  ) async => const ApiSuccess({});

  @override
  Future<ApiResult<Map<String, dynamic>>> put(
    String path,
    Map<String, dynamic> body,
  ) async => ApiSuccess(const {});

  @override
  Future<ApiResult<Map<String, dynamic>>> delete(String path) async =>
      ApiSuccess(const {});

  @override
  void dispose() {}
}

Future<void> _pump(
  WidgetTester tester, {
  required _StubApi api,
  SessionStore? session,
}) async {
  // Lobby là ListView dài; mặc định 600px cao thì card cuối ("Phòng công
  // khai") chưa được build. Cho màn hình cao để mọi thứ hiện cùng lúc.
  tester.view.physicalSize = const Size(1000, 2600);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  await tester.pumpWidget(
    MaterialApp(home: LobbyPage(session: session, roomApi: RoomApi(api: api))),
  );
  await tester.pump();
}

void main() {
  testWidgets('không có phòng nào thì hiện thông báo rỗng, không trống trơn', (
    tester,
  ) async {
    await _pump(tester, api: _StubApi());
    await tester.pumpAndSettle();

    // Thông báo rỗng là Text 2 dòng nên so `textContaining`, không so `text`.
expect(find.textContaining('Chưa có phòng công khai'), findsOneWidget);
expect(find.textContaining('Hãy tạo phòng'), findsOneWidget);
  });

  testWidgets('có phòng thì hiện tên chủ phòng, mã phòng và nút Vào', (
    tester,
  ) async {
    await _pump(
      tester,
      api: _StubApi(
        rooms: [
          {
            'code': 'H12345',
            'hostName': 'Minh Anh',
            'playerCount': 2,
            'maxPlayers': 4,
          },
        ],
      ),
    );
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.text('Minh Anh'), findsOneWidget);
    expect(find.text('Mã H12345 · còn 2 chỗ'), findsOneWidget);
    expect(find.text('Vào'), findsOneWidget);
    expect(find.textContaining('Chưa có phòng'), findsNothing);
  });

  testWidgets('lỗi tải danh sách thì hiện thông báo + nút Thử lại', (
    tester,
  ) async {
    await _pump(tester, api: _StubApi(failure: 'Server trả 503.'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Không tải được danh sách'), findsOneWidget);
    expect(find.widgetWithText(TextButton, 'Thử lại'), findsOneWidget);
  });

  testWidgets('có phiên phòng cũ thì hiện thẻ vào lại ván', (tester) async {
    final session = SessionStore.memory()
      ..saveActiveRoom('H77777', 'guest-H77777-1');
    await _pump(tester, api: _StubApi(), session: session);
    await tester.pumpAndSettle();

    expect(find.text('Bạn đang có ván ở phòng này'), findsOneWidget);
    expect(find.text('Mã phòng H77777'), findsOneWidget);

    // Bỏ ván cũ thì thẻ biến mất.
    await tester.tap(find.byTooltip('Không phải, bỏ ván này'));
    await tester.pumpAndSettle();

    expect(find.text('Bạn đang có ván ở phòng này'), findsNothing);
    expect(session.activeRoom, isNull);
  });

  testWidgets('tên đã lưu được điền sẵn vào ô tên', (tester) async {
    final session = SessionStore.memory()..savePlayerName('Bảo Ngọc');
    await _pump(tester, api: _StubApi(), session: session);
    await tester.pumpAndSettle();

    expect(find.widgetWithText(TextFormField, 'Bảo Ngọc'), findsOneWidget);
  });
}
