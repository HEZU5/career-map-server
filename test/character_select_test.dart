import 'package:career_map/core/ui/components/app_button.dart';
import 'package:career_map/features/welcome/character_select_page.dart';
import 'package:career_map/features/welcome/domain/sample_character.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('character select shows 4 image choices without gender', (
    tester,
  ) async {
    await tester.pumpWidget(const MaterialApp(home: CharacterSelectPage()));

    expect(find.text('Chọn 1 trong 4 nhân vật để nhập vai'), findsOneWidget);
    expect(careerCharacters, hasLength(4));
    for (final c in careerCharacters) {
      expect(find.text(c.name), findsOneWidget);
    }
    // Không còn bộ lọc giới tính nào.
    expect(find.text('Nam'), findsNothing);
    expect(find.text('Nữ'), findsNothing);

    // Nút xác nhận chưa sáng khi chưa chọn.
    expect(find.text('Xác nhận nhân vật'), findsOneWidget);
    final confirm = find.byWidgetPredicate(
      (w) => w is AppButton && w.label == 'Xác nhận nhân vật' && w.onPressed == null,
    );
    expect(confirm, findsOneWidget);
  });

  testWidgets('tapping a character selects it and enables confirm', (
    tester,
  ) async {
    CareerCharacter? picked;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: FilledButton(
                onPressed: () async {
                  picked = await Navigator.push<CareerCharacter>(
                    context,
                    MaterialPageRoute(
                      builder: (_) => const CharacterSelectPage(),
                    ),
                  );
                },
                child: const Text('Mở'),
              ),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('Mở'));
    await tester.pump(const Duration(milliseconds: 50));
    expect(find.text('ĐÃ CHỌN'), findsNothing);

    await tester.ensureVisible(find.text('BUBU'));
    await tester.pump(const Duration(milliseconds: 50));
    await tester.tap(find.text('BUBU'));
    await tester.pump(const Duration(milliseconds: 50));
    expect(find.text('ĐÃ CHỌN'), findsOneWidget);

    await tester.ensureVisible(find.text('Xác nhận nhân vật'));
    await tester.pump(const Duration(milliseconds: 50));
    await tester.tap(find.text('Xác nhận nhân vật'));
    await tester.pump(const Duration(milliseconds: 50));
    expect(picked?.name, 'BUBU');
    expect(picked?.profession, 'The Challenger');
    expect(picked?.assetPath, 'assets/Asset 6.png');
  });
}
