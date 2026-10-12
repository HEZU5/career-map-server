import 'package:career_map/core/theme/app_colors.dart';
import 'package:career_map/features/game/data/game_api.dart';
import 'package:career_map/features/game/data/game_socket.dart';
import 'package:career_map/features/game/domain/board.dart';
import 'package:career_map/features/game/domain/player.dart';
import 'package:career_map/features/game/domain/riasec.dart';
import 'package:career_map/features/game/presentation/game_controller.dart';
import 'package:flutter_test/flutter_test.dart';

/// API giả mô phỏng ĐÚNG race của server: broadcast/poll tới client TRƯỚC khi
/// HTTP response về tới (chuyện thật xảy ra thường xuyên với WebSocket).
class _BroadcastFirstApi implements GameApi {
  _BroadcastFirstApi(this.onRequest, {this.newPosition = 48});

  final void Function(GameController game) onRequest;
  final int newPosition;

  @override
  Future<ApiResult<Map<String, dynamic>>> post(
    String path,
    Map<String, dynamic> body,
  ) async {
    onRequest(game!);
    return ApiSuccess(<String, dynamic>{
      'diceValue': 6,
      'newPosition': newPosition,
      'moved': true,
    });
  }

  GameController? game;

  @override
  Future<ApiResult<List<Map<String, dynamic>>>> getList(String path) async =>
      const ApiSuccess([]);

  @override
  Future<ApiResult<Map<String, dynamic>>> get(String path) async =>
      const ApiSuccess(<String, dynamic>{});

  @override
  Future<ApiResult<Map<String, dynamic>>> put(
    String path,
    Map<String, dynamic> body,
  ) async => const ApiSuccess(<String, dynamic>{});

  @override
  Future<ApiResult<Map<String, dynamic>>> delete(String path) async =>
      const ApiSuccess(<String, dynamic>{});

  @override
  void dispose() {}
}

const _players = <Player>[
  Player(id: 'host-1', name: 'Bạn', color: AppColors.player1),
  Player(id: 'guest-1', name: 'Minh Anh', color: AppColors.player2),
];

GameSnapshot _snapshot({
  required int activeIndex,
  required List<int> positions,
  bool pendingAnswer = false,
}) {
  return GameSnapshot(
    code: 'A1B2',
    active: true,
    activePlayerIndex: activeIndex,
    pendingAnswer: pendingAnswer,
    orderPhase: false,
    lastDice: null,
    rollId: 1,
    lastRollerKey: null,
    players: [
      for (var i = 0; i < _players.length; i++)
        GameSnapshotPlayer(
          playerKey: _players[i].id,
          name: _players[i].name,
          position: positions[i],
          totalAnswered: 0,
          totalPoints: 0,
          scores: const <Riasec, int>{},
        ),
    ],
  );
}

void main() {
  test('broadcast tới trước HTTP response: ô đích vẫn là ô CỦA MÌNH', () async {
    final api = _BroadcastFirstApi((game) {
      // Server đã đổi lượt cho người kế tiếp (người này đứng ở ô START = 0).
      game.applyGameSnapshot(
        _snapshot(activeIndex: 1, positions: [48, 0], pendingAnswer: true),
      );
    });
    final game = GameController(
      api: api,
      initialPlayers: _players,
      myPlayerKey: 'host-1',
      online: true,
    );
    api.game = game;
    addTearDown(game.dispose);

    final value = await game.rollDice(playerId: 'host-1');

    expect(value, 6);
    // Server đã chuyển lượt sang người kế tiếp trước khi response về.
    expect(game.activePlayer.id, 'guest-1');
    // Đọc `activePlayer.position` sẽ ra ô 0 = START → không có thẻ nào mở ra
    // (đúng triệu chứng "ô thử thách chưa hiện ra").
    expect(BoardDefinition.tiles[game.activePlayer.position], isA<StartTile>());
    // Nhưng ô đích của chính lần tung phải là ô Thử thách.
    expect(game.landingPosition, 48);
    expect(game.landingTile, isA<ChallengeTile>());
  });

  test(
    'ô trang trí (STAR): server tự đổi lượt, không được mở thẻ nào',
    () async {
      // 50 là ô STAR: server đặt pendingAnswer=false và TỰ đổi lượt ngay.
      // Client phải giữ đúng ô đích để không mở nhầm thẻ của người kế tiếp.
      final api = _BroadcastFirstApi((game) {
        game.applyGameSnapshot(
          _snapshot(activeIndex: 1, positions: [50, 0], pendingAnswer: false),
        );
      }, newPosition: 50);
      final game = GameController(
        api: api,
        initialPlayers: _players,
        myPlayerKey: 'host-1',
        online: true,
      );
      api.game = game;
      addTearDown(game.dispose);

      await game.rollDice(playerId: 'host-1');

      expect(game.landingPosition, 50);
      expect(game.landingTile, isA<StarTile>());
      expect(game.pendingAnswer, isFalse);
    },
  );

  test('chưa tung lần nào thì landingTile = null', () {
    final game = GameController(
      api: _FakeNeverApi(),
      initialPlayers: _players,
      myPlayerKey: 'host-1',
      online: true,
    );
    addTearDown(game.dispose);

    expect(game.landingTile, isNull);
    expect(game.landingPosition, isNull);
  });
}

class _FakeNeverApi implements GameApi {
  @override
  Future<ApiResult<Map<String, dynamic>>> post(
    String path,
    Map<String, dynamic> body,
  ) async => const ApiSuccess(<String, dynamic>{});

  @override
  Future<ApiResult<List<Map<String, dynamic>>>> getList(String path) async =>
      const ApiSuccess([]);

  @override
  Future<ApiResult<Map<String, dynamic>>> get(String path) async =>
      const ApiSuccess(<String, dynamic>{});

  @override
  Future<ApiResult<Map<String, dynamic>>> put(
    String path,
    Map<String, dynamic> body,
  ) async => const ApiSuccess(<String, dynamic>{});

  @override
  Future<ApiResult<Map<String, dynamic>>> delete(String path) async =>
      const ApiSuccess(<String, dynamic>{});

  @override
  void dispose() {}
}
