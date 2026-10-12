import 'package:career_map/features/welcome/lobby_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// Widget test chạy ở non-release nên `ApiConfig.isLocalhost` = true, lobby
/// bỏ qua bước đánh thức server trong `initState` → không gọi network.
void main() {
  // Lobby dài hơn màn hình test mặc định 600px → card lựa chọn công khai/riêng
  // tư nằm dưới màn hình. Cho cao để test không phụ thuộc thứ tự bố cục.
  void useTallScreen(WidgetTester tester) {
    tester.view.physicalSize = const Size(1000, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
  }

  testWidgets(
    'lobby không còn section "Bạn bè" và có chọn công khai/riêng tư',
    (tester) async {
      useTallScreen(tester);
      await tester.pumpWidget(const MaterialApp(home: LobbyPage()));
      await tester.pump();

      expect(find.text('Bạn bè'), findsNothing);
      expect(find.text('Mời'), findsNothing);

      // Nhãl "Tạo phòng mới" có ở cả tiêu đề card lẫn nút bấm.
      expect(find.text('Tạo phòng mới'), findsWidgets);
      expect(find.text('Công khai'), findsOneWidget);
      expect(find.text('Riêng tư'), findsOneWidget);
    },
  );

  testWidgets('chọn "Riêng tư" đổi mô tả bên dưới', (tester) async {
    useTallScreen(tester);
    await tester.pumpWidget(const MaterialApp(home: LobbyPage()));
    await tester.pump();

    // Mặc định là công khai.
    expect(find.textContaining('xuất hiện trong danh sách'), findsOneWidget);

    await tester.tap(find.text('Riêng tư'));
    await tester.pumpAndSettle();

    expect(find.textContaining('không hiện trong danh sách'), findsOneWidget);

    // Bấm lại công khai thì mô tả quay về ban đầu.
    await tester.tap(find.text('Công khai'));
    await tester.pumpAndSettle();
    expect(find.textContaining('xuất hiện trong danh sách'), findsOneWidget);
  });
}
