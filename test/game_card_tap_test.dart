import 'package:career_map/features/game/presentation/game_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// Bàn cờ có animation LẶP VÔ HẠN (nút TUNG nhấp nháy) nên `pumpAndSettle`
/// không bao giờ settle — phải tự pump theo thời gian ảo.
Future<void> _pump(WidgetTester tester, {int frames = 70}) async {
  for (var i = 0; i < frames; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<void> bootGame(WidgetTester tester) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = const Size(1366, 768);
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(body: GamePage(roomCode: 'A1B2')),
      ),
    );
    await _pump(tester, frames: 5);
  }

  testWidgets(
    'chọn được đáp án và bấm được nút "Xác nhận đáp án" trên ô RIASEC',
    (tester) async {
      await bootGame(tester);

      var sawQuestion = false;
      for (var turn = 0; turn < 40 && !sawQuestion; turn++) {
        final roll = find.byIcon(Icons.casino);
        if (roll.evaluate().isNotEmpty) {
          await tester.tap(roll);
          await _pump(tester);
        }

        final confirm = find.widgetWithText(FilledButton, 'Xác nhận đáp án');
        if (confirm.evaluate().isNotEmpty) {
          sawQuestion = true;

          // Chưa chọn → nút phải khoá.
          expect(
            tester.widget<FilledButton>(confirm).onPressed,
            isNull,
            reason: 'chưa chọn đáp án thì nút xác nhận phải khoá',
          );

          // Bấm đáp án đầu tiên → phải chọn được (nút bật lên).
          // Nhận diện ô đáp án qua chuỗi '(+' vì nhãn 'A'/'B'/'C' trùng với
          // dải nhóm RIASEC ở rail phải.
          final options = find.textContaining('(+');
          expect(options, findsNWidgets(3), reason: 'thẻ phải có 3 ô đáp án');
          await tester.tap(options.first);
          await _pump(tester, frames: 4);
          expect(
            tester.widget<FilledButton>(confirm).onPressed,
            isNotNull,
            reason: 'đã bấm chọn đáp án thì nút xác nhận phải bấm được',
          );

          // Bấm xác nhận → thẻ phải đóng.
          await tester.tap(confirm);
          await _pump(tester);
          break;
        }

        // Ô sự kiện chen vào giữa đường thì đóng lại để đi tiếp.
        for (final label in const ['Hoàn thành', 'Nhận thưởng']) {
          final bonus = find.text(label);
          if (bonus.evaluate().isNotEmpty) {
            await tester.tap(bonus);
            await _pump(tester);
          }
        }

        final next = find.byIcon(Icons.skip_next);
        if (next.evaluate().isNotEmpty) {
          await tester.tap(next);
          await _pump(tester, frames: 4);
        }
      }

      expect(
        sawQuestion,
        isTrue,
        reason: 'phải gặp được thẻ câu hỏi RIASEC trong 40 lượt',
      );
      expect(
        find.text('Xác nhận đáp án'),
        findsNothing,
        reason: 'bấm xác nhận xong thẻ phải đóng lại',
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('bấm được nút của thẻ Cơ hội / Thử thách', (tester) async {
    await bootGame(tester);

    var sawBonus = false;
    for (var turn = 0; turn < 40 && !sawBonus; turn++) {
      final roll = find.byIcon(Icons.casino);
      if (roll.evaluate().isNotEmpty) {
        await tester.tap(roll);
        await _pump(tester);
      }

      // Gặp thẻ câu hỏi trước thì đóng lại để tìm ô sự kiện.
      final confirm = find.widgetWithText(FilledButton, 'Xác nhận đáp án');
      if (confirm.evaluate().isNotEmpty) {
        await tester.tap(find.textContaining('(+').first);
        await _pump(tester, frames: 4);
        await tester.tap(confirm);
        await _pump(tester);
        continue;
      }

      for (final label in const ['Hoàn thành', 'Nhận thưởng']) {
        final bonus = find.text(label);
        if (bonus.evaluate().isNotEmpty) {
          sawBonus = true;
          // Nút phải bấm được → thẻ đóng lại sau khi bấm.
          await tester.tap(bonus);
          await _pump(tester);
          expect(
            find.text(label),
            findsNothing,
            reason: 'bấm nút "$label" của thẻ sự kiện phải đóng được thẻ',
          );
        }
      }

      final next = find.byIcon(Icons.skip_next);
      if (next.evaluate().isNotEmpty) {
        await tester.tap(next);
        await _pump(tester, frames: 4);
      }
    }

    expect(
      sawBonus,
      isTrue,
      reason: 'phải gặp được thẻ Cơ hội hoặc Thử thách trong 40 lượt',
    );
    expect(tester.takeException(), isNull);
  });
}
