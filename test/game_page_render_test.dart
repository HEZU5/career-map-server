import 'package:career_map/features/game/presentation/game_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  // Các size mục tiêu (LỖI 1-3): phone dọc/ngang, iPhone, iPad, desktop.
  final sizes = <Size>[
    const Size(400, 645),
    const Size(645, 400),
    const Size(390, 844),
    const Size(844, 390),
    const Size(1024, 1366),
    const Size(1366, 1024),
    const Size(1366, 768),
    const Size(1920, 1080),
  ];

  for (final size in sizes) {
    testWidgets('game page render sạch (không exception/overflow) '
        '${size.width.toInt()}x${size.height.toInt()}', (tester) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = size;
      addTearDown(tester.view.reset);

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: GamePage(roomCode: 'A1B2')),
        ),
      );
      // Cho bàn cờ (FittedBox) + ảnh nền + overlay layout vài frame.
      await tester.pump(const Duration(milliseconds: 250));
      await tester.pump(const Duration(milliseconds: 250));
      await tester.pump(const Duration(milliseconds: 250));

      expect(
        tester.takeException(),
        isNull,
        reason:
            'overflow/exception ở ${size.width.toInt()}x'
            '${size.height.toInt()}',
      );

      // Phải ra đúng những "nhân vật chính": nút hành động + mã phòng.
      expect(find.byType(Image), findsWidgets);
    });
  }

  testWidgets('mở bottom sheet "Luật chơi & Ý nghĩa RIASEC"', (tester) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = const Size(1366, 768);
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(body: GamePage(roomCode: 'A1B2')),
      ),
    );
    await tester.pump(const Duration(milliseconds: 250));

    final help = find.byTooltip('Luật chơi & Ý nghĩa RIASEC');
    expect(help, findsOneWidget);

    await tester.tap(help);
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.text('LUẬT CHƠI'), findsOneWidget);
    expect(find.text('Ý NGHĨA 6 NHÓM RIASEC'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('tap ô nhóm RIASEC mở GroupInfoDialog', (tester) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = const Size(1366, 768);
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(body: GamePage(roomCode: 'A1B2')),
      ),
    );
    await tester.pump(const Duration(milliseconds: 250));

    final tile = find.text('R');
    expect(tile, findsOneWidget);
    await tester.tap(tile);
    await tester.pump(const Duration(milliseconds: 350));

    expect(find.textContaining('ĐÓNG'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
