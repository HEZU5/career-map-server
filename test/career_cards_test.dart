import 'package:flutter_test/flutter_test.dart';

import 'package:career_map/features/game/data/career_cards.dart';

void main() {
  test('có đủ 72 lá Holland: mỗi nhóm 12 lá', () {
    expect(kHollandCards.length, 72);
    for (final group in const ['R', 'I', 'A', 'S', 'E', 'C']) {
      final cards = kHollandCards.where((c) => c.group.code == group);
      expect(cards.length, 12, reason: 'nhóm $group phải có đủ 12 lá');
    }
  });

  test('mỗi lá có đúng 3 đáp án A/B/C', () {
    for (final card in kHollandCards) {
      expect(card.options.map((o) => o.label).toSet(), {
        'A',
        'B',
        'C',
      }, reason: '${card.id} phải có đủ A/B/C');
    }
  });

  test('quy tắc chấm: +2 cho nhóm của lá, 2 đáp án còn lại +1', () {
    for (final card in kHollandCards) {
      var hasMain = false;
      var sum = 0;
      final targetByPoints = <int, List<String>>{};
      for (final option in card.options) {
        expect(
          option.points == 1 || option.points == 2,
          isTrue,
          reason: '${card.id} điểm chỉ là 1 hoặc 2',
        );
        sum += option.points;
        targetByPoints
            .putIfAbsent(option.points, () => [])
            .add(option.category.code);
        if (option.points == 2) hasMain = true;
      }
      expect(sum, 4, reason: '${card.id} tổng điểm 2+1+1 = 4');
      expect(hasMain, isTrue, reason: '${card.id} phải có đáp án +2');
      expect(
        targetByPoints[2]!.single,
        card.group.code,
        reason: '${card.id} đáp án +2 phải là nhóm của lá',
      );
      expect(
        targetByPoints[1]!.length,
        2,
        reason: '${card.id} có đúng 2 đáp án +1',
      );
    }
  });

  test('bộ Cơ hội và Thử thách đầy đủ 10 lá mỗi loại', () {
    expect(kOpportunityCards.length, 10);
    expect(kChallengeCards.length, 10);
    for (final card in kOpportunityCards) {
      expect(card.winPoints, greaterThan(0));
      expect(card.title, isNotEmpty);
    }
    for (final card in kChallengeCards) {
      expect(card.winPoints, greaterThan(0));
      expect(card.task, isNotEmpty);
    }
  });
}
