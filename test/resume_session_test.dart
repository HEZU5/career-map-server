import 'package:career_map/core/storage/session_store.dart';
import 'package:career_map/features/game/domain/player.dart';
import 'package:career_map/features/game/presentation/game_controller.dart';
import 'package:career_map/features/welcome/welcome_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// Màn chào là màn ĐẦU TIÊN sau khi F5 → câu hỏi "vào lại trận đang chơi
/// không?" phải hiện ở đây, không nằm khuất trong lobby.
void main() {
  Widget app(SessionStore? session) => MaterialApp(home: WelcomePage(session: session));

  // Màn chào có ảnh hero lớn; mặc định 600px cao thì thẻ nhắc nằm dưới màn hình.
  void useTallScreen(WidgetTester tester) {
    tester.view.physicalSize = const Size(1000, 1800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
  }

  testWidgets('không có phiên thì không hiện nhắc vào lại', (tester) async {
    useTallScreen(tester);
    await tester.pumpWidget(app(SessionStore.memory()));
    await tester.pumpAndSettle();

    expect(find.text('Bạn đang chơi ván ở phòng này'), findsNothing);
    expect(find.text('Bắt đầu chơi'), findsOneWidget);
  });

  testWidgets('có phiên phòng thì hiện nút vào lại trận kèm mã phòng', (
    tester,
  ) async {
    useTallScreen(tester);
    final session = SessionStore.memory()
      ..saveActiveRoom('H24680', 'guest-H24680-1');
    await tester.pumpWidget(app(session));
    await tester.pumpAndSettle();

    expect(find.text('Bạn đang chơi ván ở phòng này'), findsOneWidget);
    expect(find.text('Mã phòng H24680'), findsOneWidget);
    expect(find.text('Vào lại trận'), findsOneWidget);
  });

  testWidgets('bấm "Bỏ ván này" thì xoá phiên và ẩn thẻ nhắc', (tester) async {
    useTallScreen(tester);
    final session = SessionStore.memory()
      ..saveActiveRoom('H24680', 'guest-H24680-1');
    await tester.pumpWidget(app(session));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Bỏ ván này'));
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.text('Bạn đang chơi ván ở phòng này'), findsNothing);
    expect(session.activeRoom, isNull);
  });

  group('lostSeat', () {
    Player player(String id) => Player(id: id, name: id, color: Colors.blue);

    test('offline thì không bao giờ mất chỗ', () {
      final game = GameController(online: false, myPlayerKey: 'khong-ton-tai');
      expect(game.lostSeat, isFalse);
    });

    test('online mà key có trong phòng thì giữ chỗ', () {
      final game = GameController(
        online: true,
        myPlayerKey: 'host-H1',
        initialPlayers: [player('host-H1'), player('guest-H1-1')],
      );
      expect(game.lostSeat, isFalse);
    });

    test('online mà key không có trong phòng thì báo mất chỗ', () {
      // Đây là tình huống "vào lại trận" sau khi đã rời phòng: server không còn
      // người chơi đó, nếu không báo thì UI rơi về nhân vật đang tới lượt.
      final game = GameController(
        online: true,
        myPlayerKey: 'guest-H1-9',
        initialPlayers: [player('host-H1'), player('guest-H1-1')],
      );
      expect(game.lostSeat, isTrue);
    });

    test('đã đánh dấu rời (left) thì cũng mất chỗ', () {
      // Server giữ record với left=true sau khi rời phòng giữa ván và chặn
      // vào lại (phòng đã start). Phải báo, không cho vào làm avatar tối.
      final game = GameController(
        online: true,
        myPlayerKey: 'guest-H1-1',
        initialPlayers: [
          player('host-H1'),
          player('guest-H1-1').copyWith(left: true),
        ],
      );
      expect(game.lostSeat, isTrue);
    });
  });
}