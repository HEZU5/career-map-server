import 'package:career_map/core/theme/app_colors.dart';
import 'package:career_map/features/game/data/game_api.dart';
import 'package:career_map/features/game/data/game_socket.dart';
import 'package:career_map/features/game/domain/player.dart';
import 'package:career_map/features/game/domain/riasec.dart';
import 'package:career_map/features/game/presentation/game_controller.dart';
import 'package:flutter_test/flutter_test.dart';

/// API giả: trả về theo kịch bản đã cấu hình.
class _FakeApi implements GameApi {
  _FakeApi();

  final Map<String, ApiResult<Map<String, dynamic>>> responses = {};
  bool offline = false;

  @override
  Future<ApiResult<Map<String, dynamic>>> post(
    String path,
    Map<String, dynamic> body,
  ) async => offline
      ? const ApiFailure('offline')
      : (responses[path] ?? const ApiSuccess(<String, dynamic>{}));

  @override
  Future<ApiResult<List<Map<String, dynamic>>>> getList(String path) async =>
      const ApiSuccess([]);

  @override
  Future<ApiResult<Map<String, dynamic>>> get(String path) async =>
      responses[path] ?? const ApiSuccess(<String, dynamic>{});

  @override
  Future<ApiResult<Map<String, dynamic>>> put(
    String path,
    Map<String, dynamic> body,
  ) async => responses[path] ?? const ApiSuccess(<String, dynamic>{});

  @override
  Future<ApiResult<Map<String, dynamic>>> delete(String path) async =>
      responses[path] ?? const ApiSuccess(<String, dynamic>{});

  @override
  void dispose() {}
}

const _players = <Player>[
  Player(id: 'host-1', name: 'Bạn', color: AppColors.player1),
  Player(id: 'guest-1', name: 'Minh Anh', color: AppColors.player2),
  Player(id: 'guest-2', name: 'Gia Huy', color: AppColors.player3),
  Player(id: 'guest-3', name: 'Bảo Ngọc', color: AppColors.player4),
];

GameSnapshot _snapshot({
  required int rollId,
  required int? dice,
  String? roller,
  int activeIndex = 0,
  bool pendingAnswer = false,
  bool orderPhase = false,
}) {
  return GameSnapshot(
    code: 'A1B2',
    active: true,
    activePlayerIndex: activeIndex,
    pendingAnswer: pendingAnswer,
    orderPhase: orderPhase,
    lastDice: dice,
    rollId: rollId,
    lastRollerKey: roller,
    players: [
      for (final p in _players)
        GameSnapshotPlayer(
          playerKey: p.id,
          name: p.name,
          position: 0,
          totalAnswered: 0,
          totalPoints: 0,
          scores: const <Riasec, int>{},
        ),
    ],
  );
}

void main() {
  group('rollId — nhận biết lần tung MỚI', () {
    test('snapshot cùng rollId không phát lại kết quả (idempotent)', () {
      final game = GameController(
        api: _FakeApi(),
        initialPlayers: _players,
        myPlayerKey: 'guest-1',
        online: true,
      );
      addTearDown(game.dispose);

      game.applyGameSnapshot(_snapshot(rollId: 7, dice: 4, roller: 'host-1'));
      expect(game.seenRollId, 7);
      expect(game.sharedRoll, 4);
      expect(game.sharedRollerKey, 'host-1');

      // Poll/broadcast lặp lại cùng rollId → không đổi state.
      game.applyGameSnapshot(_snapshot(rollId: 7, dice: 4, roller: 'host-1'));
      expect(game.seenRollId, 7);
      expect(game.sharedRoll, 4);
    });

    test('hai lần tung trùng mặt vẫn là hai lần tung MỚI', () {
      final game = GameController(
        api: _FakeApi(),
        initialPlayers: _players,
        myPlayerKey: 'guest-1',
        online: true,
      );
      addTearDown(game.dispose);

      var events = 0;
      game.addListener(() => events++);

      game.applyGameSnapshot(_snapshot(rollId: 1, dice: 6, roller: 'host-1'));
      final firstId = game.seenRollId;
      game.applyGameSnapshot(_snapshot(rollId: 2, dice: 6, roller: 'guest-2'));

      expect(game.seenRollId, greaterThan(firstId));
      expect(game.sharedRoll, 6);
      expect(game.sharedRollerKey, 'guest-2');
      // 2 lần tung → 2 sự kiện notify (mỗi lần tung mới đúng 1 lần).
      expect(events, 2);
    });

    test('server xoá lastDice sau khi xử lý ô → dọn kết quả chung', () {
      final game = GameController(
        api: _FakeApi(),
        initialPlayers: _players,
        myPlayerKey: 'guest-1',
        online: true,
      );
      addTearDown(game.dispose);

      game.applyGameSnapshot(_snapshot(rollId: 3, dice: 2, roller: 'host-1'));
      expect(game.sharedRoll, 2);

      game.applyGameSnapshot(
        _snapshot(rollId: 3, dice: null, roller: null, pendingAnswer: false),
      );
      expect(game.sharedRoll, isNull);
      expect(game.sharedRollerKey, isNull);
    });
  });

  group('rollDice', () {
    test(
      'offline: mỗi lần tung tăng mã lần tung kể cả khi trùng mặt',
      () async {
        final api = _FakeApi()..offline = true;
        final game = GameController(
          api: api,
          initialPlayers: _players,
          myPlayerKey: 'guest-1',
        );
        addTearDown(game.dispose);

        final first = await game.rollDice(playerId: 'guest-1');
        final firstId = game.seenRollId;
        final second = await game.rollDice(playerId: 'guest-1');

        expect(first, inInclusiveRange(1, 6));
        expect(second, inInclusiveRange(1, 6));
        expect(game.seenRollId, greaterThan(firstId));
        expect(game.sharedRoll, second);
        expect(game.sharedRollerKey, 'guest-1');
      },
    );

    test('online: server từ chối → trả null, không đổi state', () async {
      final api = _FakeApi()..offline = true;
      final game = GameController(
        api: api,
        initialPlayers: _players,
        myPlayerKey: 'guest-1',
        online: true,
      );
      addTearDown(game.dispose);

      expect(await game.rollDice(playerId: 'guest-1'), isNull);
      expect(game.sharedRoll, isNull);
      expect(game.players.firstWhere((p) => p.id == 'guest-1').position, 0);
    });

    test('online: server đồng ý → lấy đúng mặt + vị trí server', () async {
      final api = _FakeApi()
        ..responses['/api/game/roll-dice'] = const ApiSuccess({
          'diceValue': 4,
          'newPosition': 4,
          'moved': true,
        });
      final game = GameController(
        api: api,
        initialPlayers: _players,
        myPlayerKey: 'host-1',
        online: true,
      );
      addTearDown(game.dispose);

      expect(await game.rollDice(playerId: 'host-1'), 4);
      expect(game.players.first.position, 4);
      expect(game.lastRollMoved, isTrue);
    });

    test(
      'online: lần tung xếp thứ tự (server moved=false) → quân đứng yên',
      () async {
        final api = _FakeApi()
          ..responses['/api/game/roll-dice'] = const ApiSuccess({
            'diceValue': 5,
            'newPosition': 0,
            'moved': false,
          });
        final game = GameController(
          api: api,
          initialPlayers: _players,
          myPlayerKey: 'host-1',
          online: true,
        );
        addTearDown(game.dispose);

        expect(await game.rollDice(playerId: 'host-1'), 5);
        expect(game.lastRollMoved, isFalse);
        // Quân phải ĐỨNG YÊN dù mặt xúc xắc là 5 — nếu tự suy đoán theo giai
        // đoạn local (chưa kịp đồng bộ) thì quân sẽ nhảy lung tung.
        expect(game.players.first.position, 0);
        expect(game.players.first.orderDice, 5);
      },
    );
  });

  group('điều kiện hiện nút TUNG', () {
    test('không phải lượt mình → không được tung', () {
      final game = GameController(
        api: _FakeApi(),
        initialPlayers: _players,
        myPlayerKey: 'guest-2',
        online: true,
      );
      addTearDown(game.dispose);
      expect(game.isMyRollTurn, isFalse);
      expect(game.canRoll, isFalse);
    });

    test('giai đoạn quyết định thứ tự: chỉ người tới lượt được tung', () {
      final game = GameController(
        api: _FakeApi(),
        initialPlayers: _players,
        myPlayerKey: 'guest-2',
        online: true,
      );
      addTearDown(game.dispose);

      // Lượt đang ở host-1 (index 0) → guest-2 chưa tới lượt: KHÔNG được tung
      // (tránh nhiều người chen nhau tung cùng lúc ở đầu trận).
      game.applyGameSnapshot(
        _snapshot(rollId: 0, dice: null, orderPhase: true, activeIndex: 0),
      );
      expect(game.isMyRollTurn, isFalse);
      expect(game.canRoll, isFalse);

      // Server xoay lượt sang guest-2 (index 2) → được tung.
      game.applyGameSnapshot(
        _snapshot(rollId: 0, dice: null, orderPhase: true, activeIndex: 2),
      );
      expect(game.isMyRollTurn, isTrue);
      expect(game.canRoll, isTrue);
    });

    test('đã xử lý ô (pendingAnswer) thì chặn tung', () {
      final game = GameController(
        api: _FakeApi(),
        initialPlayers: _players,
        myPlayerKey: 'host-1',
        online: true,
      );
      addTearDown(game.dispose);

      game.applyGameSnapshot(
        _snapshot(rollId: 1, dice: 3, roller: 'host-1', pendingAnswer: true),
      );
      expect(game.pendingAnswer, isTrue);
      expect(game.canRoll, isTrue, reason: 'canRoll chỉ chặn tung trùng');
      expect(game.isRolling, isFalse);
    });

    test('người đã rời phòng không được tung', () {
      final game = GameController(
        api: _FakeApi(),
        initialPlayers: _players,
        myPlayerKey: 'guest-1',
        online: true,
      );
      addTearDown(game.dispose);

      final snapshot = _snapshot(rollId: 1, dice: 3, roller: 'guest-1');
      game.applyGameSnapshot(
        GameSnapshot(
          code: snapshot.code,
          active: snapshot.active,
          activePlayerIndex: snapshot.activePlayerIndex,
          pendingAnswer: snapshot.pendingAnswer,
          orderPhase: snapshot.orderPhase,
          lastDice: snapshot.lastDice,
          rollId: snapshot.rollId,
          lastRollerKey: snapshot.lastRollerKey,
          players: [
            GameSnapshotPlayer(
              playerKey: _players[0].id,
              name: _players[0].name,
              position: 0,
              totalAnswered: 0,
              totalPoints: 0,
              left: true,
              scores: const <Riasec, int>{},
            ),
            for (final p in _players.skip(1))
              GameSnapshotPlayer(
                playerKey: p.id,
                name: p.name,
                position: 0,
                totalAnswered: 0,
                totalPoints: 0,
                scores: const <Riasec, int>{},
              ),
          ],
        ),
      );
      expect(game.isMyRollTurn, isFalse);
    });
  });
}
