import 'riasec.dart';

/// Định nghĩa bàn cờ dưới dạng typed model.
/// Code cũ: `List<String> spaces = ['START','R','CƠ HỘI',...]` + check
/// `'RIASEC'.contains(tile)` — rất giòn, sai chính tả là vỡ logic.
sealed class BoardTile {
  const BoardTile();
}

class StartTile extends BoardTile {
  const StartTile();
}

/// Ô trang trí ⭐ (rỗng): quân dừng lại nhưng không rút thẻ / không phạt.
class StarTile extends BoardTile {
  const StarTile();
}

class OpportunityTile extends BoardTile {
  const OpportunityTile();
}

class ChallengeTile extends BoardTile {
  const ChallengeTile();
}

class RiasecTile extends BoardTile {
  const RiasecTile(this.group);
  final Riasec group;
}

/// Vòng cờ 53 ô khớp 1:1 với `assets/Board game_new.jpg` (đã đo tâm từng ô).
///  - 24 ô RIASEC (mỗi nhóm 4 ô), 10 ô Cơ hội, 7 ô Thử thách (gồm 4 góc),
///    11 ô trang trí ⭐, 1 ô START.
abstract final class BoardDefinition {
  /// HƯỚNG DI CHUYỂN trên vòng cờ (theo mảng [tiles]/[cellCenters]):
  ///   +1: START → 1 → 2 → ... → 51 → 52 (chạy ngang cạnh dưới từ TRÁI
  ///       sang PHẢI) — SAI so với mũi tên `<<<<` trên bàn.
  ///   -1: START → 52 → 51 → ... → 2 → 1 (chạy ngang cạnh dưới từ PHẢI sang
  ///       TRÁI về START, đúng mũi tên) — ĐÚNG.
  /// Người chơi phát hiện quân đi ngược hướng nên HƯỚNG này được cố định =
  /// -1. Mảng [tiles] / `cellCenters` KHÔNG đổi thứ tự: chỉ HƯỚNG DI CHUYỂN
  /// thay đổi. Muốn thử lại hướng cũ chỉ cần đổi hằng này thành 1.
  static const int direction = -1;

  /// Vị trí sau khi đi [steps] ô từ ô [from], theo [direction].
  /// Wrap vòng cờ — đi ngược qua ô 0 (START) là hoàn thành thêm 1 vòng.
  static int moveIndex(int from, int steps) {
    final len = tiles.length;
    final raw = from + steps * direction;
    final result = raw % len;
    return result < 0 ? result + len : result;
  }

  static const List<BoardTile> tiles = [
    StartTile(), // 0
    OpportunityTile(), // 1
    RiasecTile(Riasec.realistic), // 2
    ChallengeTile(), // 3 góc dưới-trái
    RiasecTile(Riasec.investigative), // 4
    OpportunityTile(), // 5
    RiasecTile(Riasec.artistic), // 6
    StarTile(), // 7
    RiasecTile(Riasec.social), // 8
    OpportunityTile(), // 9
    RiasecTile(Riasec.enterprising), // 10
    StarTile(), // 11
    ChallengeTile(), // 12 góc trên-trái
    RiasecTile(Riasec.conventional), // 13
    OpportunityTile(), // 14
    RiasecTile(Riasec.realistic), // 15
    StarTile(), // 16
    RiasecTile(Riasec.investigative), // 17
    ChallengeTile(), // 18 Thử thách
    RiasecTile(Riasec.artistic), // 19
    StarTile(), // 20
    RiasecTile(Riasec.social), // 21
    OpportunityTile(), // 22
    RiasecTile(Riasec.enterprising), // 23
    StarTile(), // 24
    RiasecTile(Riasec.conventional), // 25
    ChallengeTile(), // 26 Thử thách
    RiasecTile(Riasec.realistic), // 27
    StarTile(), // 28
    RiasecTile(Riasec.investigative), // 29
    OpportunityTile(), // 30
    RiasecTile(Riasec.artistic), // 31
    StarTile(), // 32
    ChallengeTile(), // 33 góc trên-phải
    RiasecTile(Riasec.social), // 34
    OpportunityTile(), // 35
    RiasecTile(Riasec.enterprising), // 36
    StarTile(), // 37
    RiasecTile(Riasec.conventional), // 38
    OpportunityTile(), // 39
    RiasecTile(Riasec.realistic), // 40
    StarTile(), // 41
    ChallengeTile(), // 42 góc dưới-phải
    RiasecTile(Riasec.investigative), // 43
    OpportunityTile(), // 44
    RiasecTile(Riasec.artistic), // 45
    StarTile(), // 46
    RiasecTile(Riasec.social), // 47
    ChallengeTile(), // 48 Thử thách
    RiasecTile(Riasec.enterprising), // 49
    StarTile(), // 50
    RiasecTile(Riasec.conventional), // 51
    OpportunityTile(), // 52
  ];

  static int get length => tiles.length;
}
