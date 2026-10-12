import 'package:flutter_test/flutter_test.dart';

import 'package:career_map/main.dart';

void main() {
  testWidgets('shows the Holland Career Explorer landing page', (tester) async {
    await tester.pumpWidget(CareerMapApp());

    expect(find.text('HOLLAND\nCAREER EXPLORER'), findsOneWidget);
    expect(find.text('Bắt đầu chơi'), findsOneWidget);
  });
}
