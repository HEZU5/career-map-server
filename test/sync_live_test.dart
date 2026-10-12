import 'dart:async';

import 'package:flutter_test/flutter_test.dart';

import 'package:career_map/features/game/data/game_api.dart';
import 'package:career_map/features/game/data/game_socket.dart';
import 'package:career_map/features/game/data/room_api.dart';
import 'package:career_map/features/game/domain/game_room.dart';
import 'package:career_map/features/game/presentation/game_controller.dart';
import 'package:career_map/features/game/presentation/room_players.dart';

/// Test đồng bộ TRỰC TIẾP với server thật (phải đang chạy ở 127.0.0.1:8080).
/// Mô phỏng đúng những gì 2 máy chơi làm: mỗi "máy" một RoomApi/Cột điều
/// khiển + một GameSocket lắng nghe phòng, áp GameSnapshot vào controller.
/// Nếu không kết nối được server thì test được bỏ qua (không fail CI).
Future<bool> serverUp() async {
  final api = HttpGameApi();
  try {
    final result = await api.get('/api/rooms/__PROBE__');
    api.dispose();
    return switch (result) {
      ApiSuccess<Map<String, dynamic>>() ||
      ApiFailure<Map<String, dynamic>>() => true,
    };
  } catch (_) {
    api.dispose();
    return false;
  }
}

class _PlayerClient {
  _PlayerClient({required this.roomApi, required this.playerKey});

  final RoomApi roomApi;
  final String playerKey;
  GameSocket? socket;
  final List<GameSnapshot> games = [];
  late GameController controller;
  StreamSubscription<GameSocketEvent>? sub;
  bool started = false;

  /// Server báo phòng đã bị xóa (chủ phòng đóng / hết TTL).
  bool roomClosed = false;
  String? closedReason;

  /// Toast mất kết nối / quay lại / chuyển quyền chủ phòng.
  final List<String> presenceEvents = [];

  /// Chỉ còn một người trong trận.
  String? soloPlayerKey;

  /// Ván kết thúc.
  bool finished = false;

  void connect(String roomCode) {
    socket = GameSocket(roomCode: roomCode);
    sub = socket!.events.listen((event) {
      switch (event.type) {
        case GameSocketEventType.started:
          started = true;
        case GameSocketEventType.game:
          if (event.game != null) {
            games.add(event.game!);
            controller.applyGameSnapshot(event.game!);
          }
        case GameSocketEventType.room:
          break;
        case GameSocketEventType.closed:
          roomClosed = true;
          closedReason = event.reason;
        case GameSocketEventType.presence:
          presenceEvents.addAll(event.messages);
        case GameSocketEventType.solo:
          soloPlayerKey = event.playerKey;
        case GameSocketEventType.finished:
          finished = true;
          if (event.game != null) {
            games.add(event.game!);
            controller.applyGameSnapshot(event.game!);
          }
      }
    });
    socket!.connect();
  }

  Future<void> waitFor(
    bool Function() cond, {
    required String what,
    Duration timeout = const Duration(seconds: 8),
  }) async {
    final deadline = DateTime.now().add(timeout);
    while (!cond()) {
      if (DateTime.now().isAfter(deadline)) {
        fail('Hết thời gian chờ: $what\nsnapshot cuối: ${describe()}');
      }
      await Future<void>.delayed(const Duration(milliseconds: 40));
    }
  }

  /// Mô tả ngắn trạng thái ván hiện tại để khi timeout biết ngay server đang
  /// ở bước nào (đang chờ trả lời, đã đổi lượt, ...) thay vì phải debug lại.
  String describe() {
    if (games.isEmpty) return '<chưa nhận snapshot nào qua WebSocket>';
    final g = games.last;
    final me = _idxOf(g.players, playerKey);
    return 'orderPhase=${g.orderPhase} active=${g.activePlayerIndex} '
        'pendingAnswer=${g.pendingAnswer} lastDice=${g.lastDice} '
        'rollId=${g.rollId} lastRoller=${g.lastRollerKey} '
        'myIdx=$me vịTríTôi=${me >= 0 ? g.players[me].position : "?"} '
        '(nhận ${games.length} snapshot)';
  }

  void dispose() {
    sub?.cancel();
    socket?.dispose();
  }
}

/// Tìm chỉ số người chơi theo [playerKey] trong danh sách snapshot.
int _idxOf(List<GameSnapshotPlayer> players, String playerKey) {
  for (var i = 0; i < players.length; i++) {
    if (players[i].playerKey == playerKey) return i;
  }
  return -1;
}

/// Tung cho tới khi có người chơi dừng ở ô BẮT BUỘC trả lời, rồi trả về
/// máy đó.
///
/// 53 ô đường đi có 12 ô không cần trả lời (11 ô ⭐ + START). Dừng ở đó
/// server tự `advanceTurn()` và `pendingAnswer` vẫn là false
/// (GameService.rollDice). Tung đúng 1 lần không bảo đảm được tình huống
/// này — xác suất trượt ~23% — nên test cứ đòi `pendingAnswer == true` là
/// flaky. Tung lại cho tới khi đúng tình huống cần kiểm chứng mới ổn định.
Future<_PlayerClient> _rollUntilNeedsAnswer(List<_PlayerClient> clients) async {
  for (var attempt = 0; attempt < 30; attempt++) {
    // Ai đang giữ lượt? Lấy theo snapshot (nguồn sự thật của server) rồi mới
    // quy ra client tương ứng. `isMyRollTurn` của controller local có thể
    // chưa kịp nhận broadcast nên không dùng làm tiêu chí duy nhất.
    _PlayerClient? holder;
    for (final c in clients) {
      final g = c.games.last;
      final myIdx = _idxOf(g.players, c.playerKey);
      if (g.activePlayerIndex >= 0 && g.activePlayerIndex == myIdx) {
        holder = c;
        break;
      }
    }
    holder ??= fail(
      'Xác định không được ai giữ lượt ở lần #$attempt.\n'
      '${clients.map((c) => '${c.playerKey}: ${c.describe()}').join('\n')}',
    );

    // Người giữ lượt đang dừng ở ô BẮT BUỘC trả lời thì không ai tung được
    // (server trả 409) → phải trả lời trước rồi mới quay lại tung.
    if (holder.games.last.pendingAnswer) {
      await holder.controller.answerCurrentTile(selectedOption: 'A');
      await holder.waitFor(
        () => holder!.games.last.pendingAnswer == false,
        what: 'ô đang chờ trả lời được xử lý xong (lần #$attempt)',
      );
      continue;
    }

    final value = await holder.controller.rollDice();
    if (value == null) {
      // Server từ chối (sai lượt / lượt bận): chờ broadcast rồi thử lại.
      await Future<void>.delayed(const Duration(milliseconds: 200));
      continue;
    }
    for (final c in clients) {
      await c.waitFor(
        () => c.games.last.lastDice == value,
        what: 'mọi máy thấy xúc xắc $value (lần #$attempt)',
      );
    }
    if (clients.every((c) => c.games.last.pendingAnswer == true)) return holder;
    // Rơi vào ô không cần trả lời (11 ⭐ + START) → server đã tự đổi lượt,
    // vòng sau lặp lại.
  }
  fail('30 lần lặp vẫn chưa có ai dừng ở ô cần trả lời.');
}

void main() {
  final roomApi = RoomApi();
  var serverReady = false;

  setUpAll(() async {
    serverReady = await serverUp();
  });

  tearDownAll(() => roomApi.dispose());

  test('bắt đầu cần ít nhất 2 người; tung quyết định thứ tự nằm TRONG trận '
      '(không theo lượt, không di chuyển)', () async {
    if (!serverReady) {
      markTestSkipped('Server chưa chạy ở 127.0.0.1:8080.');
      return;
    }
    final createdResult = await roomApi.create('Host');
    if (createdResult is ApiFailure<GameRoom>) {
      markTestSkipped('Không tạo được phòng: ${createdResult.message}');
      return;
    }
    final created = (createdResult as ApiSuccess<GameRoom>).data;
    final code = created.code;
    final hostKey = created.players[0].playerKey;

    // 1 người → start bị từ chối (không liên quan gì tới tung xúc xắc).
    expect(await roomApi.start(code), isA<ApiFailure<GameRoom>>());

    final joinedResult = await roomApi.join(code, 'Guest');
    if (joinedResult is ApiFailure<GameRoom>) {
      markTestSkipped('Không vào được phòng: ${joinedResult.message}');
      return;
    }
    final joined = (joinedResult as ApiSuccess<GameRoom>).data;
    final guestKey = joined.players[1].playerKey;

    // 2 người → start thành công ngay (không yêu cầu tung ở phòng chờ).
    final started = await roomApi.start(code);
    expect(started, isA<ApiSuccess<GameRoom>>());
    expect((started as ApiSuccess<GameRoom>).data.active, isTrue);

    final host = GameController(
      initialPlayers: gamePlayersFromRoom(joined),
      myPlayerKey: hostKey,
      online: true,
    );
    final guest = GameController(
      initialPlayers: gamePlayersFromRoom(joined),
      myPlayerKey: guestKey,
      online: true,
    );

    // Trạng thái ván: đang giai đoạn quyết định thứ tự, chưa ai di chuyển.
    final s0 = (await roomApi.fetchGameSnapshot(
      code,
    ) as ApiSuccess<GameSnapshot>).data;
    expect(s0.orderPhase, isTrue);
    expect(s0.activePlayerIndex, 0);
    for (final p in s0.players) {
      expect(p.position, 0);
      expect(p.orderRolled, isFalse);
    }

    // Host tung (thứ tự bất kỳ) → chỉ ghi orderDice, KHÔNG di chuyển.
    host.applyGameSnapshot(s0);
    expect(host.orderPhase, isTrue);
    expect(host.isMyRollTurn, isTrue);
    await host.rollDice(playerId: hostKey);
    final s1 = (await roomApi.fetchGameSnapshot(
      code,
    ) as ApiSuccess<GameSnapshot>).data;
    final hostIdx = _idxOf(s1.players, hostKey);
    expect(s1.players[hostIdx].orderRolled, isTrue);
    expect(s1.players[hostIdx].orderDice, inInclusiveRange(1, 6));
    expect(s1.players[hostIdx].position, 0);
    expect(s1.orderPhase, isTrue); // vẫn chưa xong vì guest chưa tung

    // Guest tung → khép giai đoạn: ai cao nhất đi trước.
    guest.applyGameSnapshot(s1);
    expect(guest.isMyRollTurn, isTrue); // guest chưa tung → vẫn tung được
    await guest.rollDice(playerId: guestKey);
    final s2 = (await roomApi.fetchGameSnapshot(
      code,
    ) as ApiSuccess<GameSnapshot>).data;
    expect(s2.orderPhase, isFalse);
    final firstIdx = s2.activePlayerIndex;
    final first = s2.players[firstIdx];
    expect(first.left, isFalse);
    expect(
      s2.players
          .where((p) => !p.left)
          .every(
            (p) =>
                first.orderDice >= p.orderDice ||
                p.playerKey == first.playerKey,
          ),
      isTrue,
    );
    expect(first.position, 0);

    host.dispose();
    guest.dispose();
  });

  test('rollId tăng mỗi lần + lastRollerKey đúng người, chặn tung khi lượt '
      'đang bận (HTTP 409)', () async {
    if (!serverReady) {
      markTestSkipped('Server chưa chạy ở 127.0.0.1:8080.');
      return;
    }
    final createdResult = await roomApi.create('Host');
    if (createdResult is ApiFailure<GameRoom>) {
      markTestSkipped('Không tạo được phòng: ${createdResult.message}');
      return;
    }
    final created = (createdResult as ApiSuccess<GameRoom>).data;
    final code = created.code;
    final hostKey = created.players[0].playerKey;
    final joinedResult = await roomApi.join(code, 'Guest');
    if (joinedResult is ApiFailure<GameRoom>) {
      markTestSkipped('Không vào được phòng: ${joinedResult.message}');
      return;
    }
    final joined = (joinedResult as ApiSuccess<GameRoom>).data;
    final guestKey = joined.players[1].playerKey;
    await roomApi.start(code);
    Future<GameSnapshot> snap() async => (await roomApi.fetchGameSnapshot(
      code,
    ) as ApiSuccess<GameSnapshot>).data;

    // --- Giai đoạn quyết định thứ tự: mỗi lần tung tăng rollId đúng 1. ---
    final s0 = await snap();
    expect(s0.rollId, 0);
    expect(s0.lastRollerKey, isNull);

    await HttpGameApi().post('/api/game/roll-dice', {'playerId': hostKey});
    final s1 = await snap();
    expect(s1.rollId, s0.rollId + 1);
    expect(s1.lastRollerKey, hostKey);
    expect(s1.lastDice, inInclusiveRange(1, 6));

    await HttpGameApi().post('/api/game/roll-dice', {'playerId': guestKey});
    final s2 = await snap();
    expect(s2.rollId, s1.rollId + 1);
    expect(s2.lastRollerKey, guestKey);
    expect(s2.orderPhase, isFalse);

    // --- Lượt thật: tung khi server đang chờ trả lời ô → bị từ chối 409. ---
    final firstIdx = s2.activePlayerIndex;
    final firstKey = s2.players[firstIdx].playerKey;
    final secondKey = firstKey == hostKey ? guestKey : hostKey;
    final raw = HttpGameApi();

    final accepted = await raw.post('/api/game/roll-dice', {
      'playerId': firstKey,
    });
    expect(accepted, isA<ApiSuccess<Map<String, dynamic>>>());
    final s3 = await snap();
    expect(s3.rollId, s2.rollId + 1);
    expect(s3.lastRollerKey, firstKey);
    final landed = s3.players[firstIdx].position;

    // Chỉ khi dừng ở ô RIASEC mới chờ trả lời (pendingAnswer) thì lần tung
    // kế tiếp bị chặn; ô START/⭐ thì server tự đổi lượt nên không chặn.
    if (s3.pendingAnswer) {
      final blocked = await raw.post('/api/game/roll-dice', {
        'playerId': firstKey,
      });
      expect(
        blocked,
        isA<ApiFailure<Map<String, dynamic>>>(),
        reason: 'lượt đang chờ trả lời thì phải bị từ chối',
      );
      final s4 = await snap();
      expect(s4.rollId, s3.rollId, reason: 'lần tung bị từ chối không tăng mã');
      expect(s4.players[firstIdx].position, landed);

      await raw.post('/api/game/answer', {
        'playerId': firstKey,
        'questionId': 'live-sync-test',
        'selectedOption': 'A',
        'category': 'R',
        'targetCategory': 'R',
        'points': 2,
      });
      final s5 = await snap();
      expect(s5.activePlayerIndex, isNot(firstIdx));
      expect(s5.lastDice, isNull, reason: 'xử lý xong ô thì dọn kết quả chung');

      // Người kế tiếp được tung.
      expect(
        await raw.post('/api/game/roll-dice', {'playerId': secondKey}),
        isA<ApiSuccess<Map<String, dynamic>>>(),
      );
      final s6 = await snap();
      expect(s6.rollId, s5.rollId + 1);
      expect(s6.lastRollerKey, secondKey);
    } else {
      final s4 = await snap();
      expect(s4.activePlayerIndex, isNot(firstIdx));
      expect(
        await raw.post('/api/game/roll-dice', {'playerId': secondKey}),
        isA<ApiSuccess<Map<String, dynamic>>>(),
      );
      final s5 = await snap();
      expect(s5.rollId, s4.rollId + 1);
      expect(s5.lastRollerKey, secondKey);
    }
    raw.dispose();
  });

  test('đồng bộ lượt giữa 2 người chơi qua REST + WebSocket', () async {
    if (!serverReady) {
      markTestSkipped('Server chưa chạy ở 127.0.0.1:8080.');
      return;
    }

    final createdResult = await roomApi.create('Host');
    if (createdResult is ApiFailure<GameRoom>) {
      markTestSkipped('Không tạo được phòng: ${createdResult.message}');
      return;
    }
    final created = (createdResult as ApiSuccess<GameRoom>).data;
    final code = created.code;
    final hostKey = created.players[0].playerKey;
    final joinedResult = await roomApi.join(code, 'Guest');
    if (joinedResult is ApiFailure<GameRoom>) {
      markTestSkipped('Không vào được phòng: ${joinedResult.message}');
      return;
    }
    final joined = (joinedResult as ApiSuccess<GameRoom>).data;
    final guestKey = joined.players[1].playerKey;

    final host = _PlayerClient(roomApi: roomApi, playerKey: hostKey)
      ..controller = GameController(
        initialPlayers: gamePlayersFromRoom(joined),
        myPlayerKey: hostKey,
        online: true,
      );
    final guest = _PlayerClient(roomApi: roomApi, playerKey: guestKey)
      ..controller = GameController(
        initialPlayers: gamePlayersFromRoom(joined),
        myPlayerKey: guestKey,
        online: true,
      );
    host.connect(code);
    guest.connect(code);
    await host.waitFor(
      () => host.socket!.isConnected,
      what: 'host mở WebSocket',
    );
    await guest.waitFor(
      () => guest.socket!.isConnected,
      what: 'guest mở WebSocket',
    );

    await roomApi.start(code);
    await host.waitFor(
      () => host.started && guest.started,
      what: 'cả 2 nhận sự kiện started',
    );
    // GamePage thật tự fetch snapshot ngay sau start — test mirror y hệt.
    final startShot = (await roomApi.fetchGameSnapshot(
      code,
    ) as ApiSuccess<GameSnapshot>).data;
    host.controller.applyGameSnapshot(startShot);
    guest.controller.applyGameSnapshot(startShot);

    // Giai đoạn quyết định thứ tự: host rồi guest tung (thứ tự bất kỳ),
    // sau khép giai đoạn server báo ai đi trước trên cả 2 máy.
    await host.controller.rollDice(
      playerId: hostKey,
    ); // order roll — không di chuyển
    await guest.controller.rollDice(
      playerId: guestKey,
    ); // order roll → khép giai đoạn
    await host.waitFor(
      () => host.games.last.orderPhase == false,
      what: 'host thấy khép giai đoạn thứ tự',
    );
    await guest.waitFor(
      () => guest.games.last.orderPhase == false,
      what: 'guest thấy khép giai đoạn thứ tự',
    );

    expect(
      host.games.last.activePlayerIndex,
      guest.games.last.activePlayerIndex,
    );
    final startIdx = host.games.last.activePlayerIndex;
    expect(startIdx, anyOf(0, 1));

    expect(
      host.controller.players[startIdx].orderDice,
      guest.controller.players[startIdx].orderDice,
    );
    // Khép giai đoạn thứ tự, server SẮP XẾP LẠI danh sách người chơi theo
    // orderDice → chỉ số của host/guest trong snapshot KHÔNG còn là 0/1 như
    // lúc vào phòng. Luôn tra theo playerKey thay vì giả định vị trí.
    final hostIdx = _idxOf(host.games.last.players, hostKey);
    final guestIdx = _idxOf(host.games.last.players, guestKey);
    expect(hostIdx, isNot(-1));
    expect(guestIdx, isNot(-1));
    expect(hostIdx, isNot(guestIdx));
    expect(
      host.controller.isMyRollTurn,
      startIdx == hostIdx ? isTrue : isFalse,
    );
    expect(
      guest.controller.isMyRollTurn,
      startIdx == guestIdx ? isTrue : isFalse,
    );

    // Tung tới khi dừng ở ô BẮT BUỘC trả lời: 12/53 ô (11 ⭐ + START) không
    // cần trả lời, dừng ở đó server tự đổi lượt và pendingAnswer = false.
    // Tung đúng 1 lần là flaky nên phải lặp, và phải suy role SAU khi tung
    // vì người thực sự dừng ở ô trả lời có thể là bất kỳ ai.
    final answerer = await _rollUntilNeedsAnswer([host, guest]);
    final other = answerer == host ? guest : host;
    final firstIdx = answerer.controller.activeIndex;
    final secondIdx = 1 - firstIdx;

    expect(host.games.last.lastDice, guest.games.last.lastDice);
    expect(host.games.last.lastDice, isNotNull);
    expect(
      host.controller.players[firstIdx].position,
      guest.controller.players[firstIdx].position,
    );
    expect(other.controller.activeIndex, firstIdx);
    expect(other.controller.isMyRollTurn, isFalse);

    // Người đi trước trả lời → chuyển lượt cho người sau trên cả 2 máy.
    await answerer.controller.answerCurrentTile(selectedOption: 'A');
    await answerer.waitFor(
      () => answerer.games.last.activePlayerIndex == secondIdx,
      what: 'lượt sang người thứ hai sau khi người đầu trả lời',
    );
    await other.waitFor(
      () => other.games.last.activePlayerIndex == secondIdx,
      what: 'người thứ hai thấy lượt của mình',
    );

    expect(other.controller.activeIndex, secondIdx);
    expect(other.controller.isMyRollTurn, isTrue);
    expect(answerer.controller.activeIndex, secondIdx);

    // ---- VÒNG LẶP: mỗi vòng tung cho tới khi dừng ô trả lời, xác nhận cả hai
    // máy đồng bộ, trả lời, rồi đổi lượt. ----
    //
    // Tung tới lúc dừng ô trả lời (xem _rollUntilNeedsAnswer): nếu chỉ tung
    // đúng 1 lần thì 12/53 khả năng rơi vào ⭐/START → server advanceTurn
    // ngay, biến các khẳng định "người này phải dừng ô trả lời" thành vô
    // nghĩa. Người giữ lượt cuối cùng là ai cũng được — thứ cần chứng minh là
    // HAI MÁY THẤY CÙNG xúc xắc + cùng vị trí + cùng lượt, không phải là ai.
    for (var round = 0; round < 3; round++) {
      final holder = await _rollUntilNeedsAnswer([host, guest]);
      final looker = holder == host ? guest : host;
      final holderIdx = _idxOf(host.games.last.players, holder.playerKey);

      // Cả hai máy phải thấy cùng xúc xắc, cùng vị trí, cùng lượt.
      await looker.waitFor(
        () =>
            looker.games.last.lastDice == holder.games.last.lastDice &&
            looker.games.last.activePlayerIndex == holderIdx &&
            looker.games.last.pendingAnswer,
        what: 'máy còn lại thấy đồng bộ vòng #$round',
      );
      expect(host.games.last.lastDice, guest.games.last.lastDice);
      expect(host.games.last.activePlayerIndex, holderIdx);
      expect(
        host.controller.players[holderIdx].position,
        guest.controller.players[holderIdx].position,
      );

      // Trả lời → lượt phải nhảy sang máy còn lại trên CẢ HAI máy.
      final answered = holder.controller.players[holderIdx].totalAnswered;
      await holder.controller.answerCurrentTile(
        selectedOption: round.isEven ? 'A' : 'B',
      );
      for (final c in [host, guest]) {
        await c.waitFor(
          () =>
              c.games.last.activePlayerIndex != holderIdx &&
              c.games.last.players[holderIdx].totalAnswered > answered,
          what: 'lượt nhảy sang máy còn lại + đếm ô đã trả lời (vòng #$round)',
        );
      }
      expect(host.controller.activeIndex, guest.controller.activeIndex);
      expect(
        host.games.last.activePlayerIndex,
        guest.games.last.activePlayerIndex,
      );
    }

    host.dispose();
    guest.dispose();
  });

  test('người chơi thoát: avatar tối (left) và bỏ qua lượt của họ', () async {
    if (!serverReady) {
      markTestSkipped('Server chưa chạy ở 127.0.0.1:8080.');
      return;
    }

    final createdResult = await roomApi.create('Host');
    if (createdResult is ApiFailure<GameRoom>) {
      markTestSkipped('Không tạo được phòng: ${createdResult.message}');
      return;
    }
    final created = (createdResult as ApiSuccess<GameRoom>).data;
    final code = created.code;
    final hostKey = created.players[0].playerKey;
    final joinedResult = await roomApi.join(code, 'Guest');
    if (joinedResult is ApiFailure<GameRoom>) {
      markTestSkipped('Không vào được phòng: ${joinedResult.message}');
      return;
    }
    final joined = (joinedResult as ApiSuccess<GameRoom>).data;
    final guestKey = joined.players[1].playerKey;

    final host = _PlayerClient(roomApi: roomApi, playerKey: hostKey)
      ..controller = GameController(
        initialPlayers: gamePlayersFromRoom(joined),
        myPlayerKey: hostKey,
        online: true,
      );
    final guest = _PlayerClient(roomApi: roomApi, playerKey: guestKey)
      ..controller = GameController(
        initialPlayers: gamePlayersFromRoom(joined),
        myPlayerKey: guestKey,
        online: true,
      );
    host.connect(code);
    guest.connect(code);
    await host.waitFor(
      () => host.socket!.isConnected,
      what: 'host mở WebSocket',
    );
    await guest.waitFor(
      () => guest.socket!.isConnected,
      what: 'guest mở WebSocket',
    );

    await roomApi.start(code);
    await host.waitFor(
      () => host.started && guest.started,
      what: 'cả 2 nhận sự kiện started',
    );

    // Khép giai đoạn quyết định thứ tự (ai tới cũng tung được, không theo lượt).
    await host.controller.rollDice(playerId: hostKey);
    await guest.controller.rollDice(playerId: guestKey);
    await host.waitFor(
      () => host.games.last.orderPhase == false,
      what: 'khép giai đoạn quyết định thứ tự',
    );

    final guestIdx = _idxOf(guest.games.last.players, guestKey);

    // Người đang tới lượt tung để ván có lượt cụ thể rồi mới "host rời trận".
    // Phải dừng ở ô cần trả lời: rơi vào ⭐/START thì server tự đổi lượt.
    final stopped = await _rollUntilNeedsAnswer([host, guest]);
    expect(stopped.games.last.pendingAnswer, isTrue);

    // Nếu CHÍNH guest đang dừng ở ô trả lời thì phải trả lời trước: server
    // giữ pendingAnswer và chặn tung (409) cho tới khi ô được xử lý xong.
    if (stopped == guest) {
      await guest.controller.answerCurrentTile(selectedOption: 'A');
      await guest.waitFor(
        () => guest.games.last.pendingAnswer == false,
        what: 'guest xử lý xong ô đang chờ trả lời',
      );
    }

    await roomApi.leave(code, hostKey);

    // Host bị đánh dấu left + nhảy lượt sang guest (vì host rời/không ở lượt).
    final snapshot = await roomApi.fetch(code);
    expect(
      (snapshot as ApiSuccess<GameRoom>).data.players
          .firstWhere((p) => p.playerKey == hostKey)
          .left,
      isTrue,
    );

    await guest.waitFor(
      () =>
          guest
              .games
              .last
              .players[_idxOf(guest.games.last.players, hostKey)]
              .left &&
          guest.games.last.activePlayerIndex ==
              _idxOf(guest.games.last.players, guestKey),
      what: 'guest thấy host thoát + lượt nhảy sang mình',
    );

    expect(guest.games.last.activePlayerIndex, guestIdx);
    expect(guest.controller.activeIndex, guestIdx);
    expect(guest.games.last.pendingAnswer, isFalse);

    // Guest vẫn tung được lượt sau khi host thoát (không bị 409/treo):
    // host đã rời nên phải là guest giữ lượt và lần tung này phải thành công.
    final afterLeave = await _rollUntilNeedsAnswer([host, guest]);
    expect(
      afterLeave,
      guest,
      reason: 'lượt phải thuộc guest sau khi host rời trận',
    );

    host.dispose();
    guest.dispose();
  });

  test('đổi nhân vật (rename) ở phòng chờ cập nhật tên hiển thị', () async {
    if (!serverReady) {
      markTestSkipped('Server chưa chạy ở 127.0.0.1:8080.');
      return;
    }
    final createdResult = await roomApi.create('Host');
    if (createdResult is ApiFailure<GameRoom>) {
      markTestSkipped('Không tạo được phòng: ${createdResult.message}');
      return;
    }
    final created = (createdResult as ApiSuccess<GameRoom>).data;
    final code = created.code;
    final hostKey = created.players[0].playerKey;

    final renamed = await roomApi.rename(code, hostKey, 'Doanh nhân Hà');
    expect(renamed, isA<ApiSuccess<GameRoom>>());
    final room = (renamed as ApiSuccess<GameRoom>).data;
    expect(room.players.first.name, 'Doanh nhân Hà');

    final refetched = (await roomApi.fetch(code) as ApiSuccess<GameRoom>).data;
    expect(refetched.players.first.name, 'Doanh nhân Hà');
  });

  test('rời phòng CHƯA bắt đầu: xoá hẳn người chơi khỏi phòng', () async {
    if (!serverReady) {
      markTestSkipped('Server chưa chạy ở 127.0.0.1:8080.');
      return;
    }
    final createdResult = await roomApi.create('Host');
    if (createdResult is ApiFailure<GameRoom>) {
      markTestSkipped('Không tạo được phòng: ${createdResult.message}');
      return;
    }
    final created = (createdResult as ApiSuccess<GameRoom>).data;
    final code = created.code;
    final hostKey = created.players[0].playerKey;

    final joinedResult = await roomApi.join(code, 'Guest');
    if (joinedResult is ApiFailure<GameRoom>) {
      markTestSkipped('Không vào được phòng: ${joinedResult.message}');
      return;
    }
    final guestKey =
        (joinedResult as ApiSuccess<GameRoom>).data.players[1].playerKey;

    // Guest rời khi trận CHƯA bắt đầu → phải bị xoá hẳn, không để lại
    // avatar nền đen lúc vào trận.
    final afterResult = await roomApi.leave(code, guestKey);
    final after = (afterResult as ApiSuccess<GameRoom>).data;
    expect(after.active, isFalse);
    expect(after.players.length, 1);
    expect(after.players.any((p) => p.playerKey == guestKey), isFalse);
    expect(after.players.single.playerKey, hostKey);

    final refetched = (await roomApi.fetch(code) as ApiSuccess<GameRoom>).data;
    expect(refetched.players.length, 1);
    expect(refetched.players.single.left, isFalse);
  });

  test('endpoint snapshot trả state ván để client poll khớp lại', () async {
    if (!serverReady) {
      markTestSkipped('Server chưa chạy ở 127.0.0.1:8080.');
      return;
    }
    final createdResult = await roomApi.create('Host');
    if (createdResult is ApiFailure<GameRoom>) {
      markTestSkipped('Không tạo được phòng: ${createdResult.message}');
      return;
    }
    final created = (createdResult as ApiSuccess<GameRoom>).data;
    final code = created.code;
    final hostKey = created.players[0].playerKey;

    final joinedResult = await roomApi.join(code, 'Guest');
    if (joinedResult is ApiFailure<GameRoom>) {
      markTestSkipped('Không vào được phòng: ${joinedResult.message}');
      return;
    }
    final joined = (joinedResult as ApiSuccess<GameRoom>).data;

    final host = _PlayerClient(roomApi: roomApi, playerKey: hostKey)
      ..controller = GameController(
        initialPlayers: gamePlayersFromRoom(joined),
        myPlayerKey: hostKey,
        online: true,
      );
    host.connect(code);
    await host.waitFor(
      () => host.socket!.isConnected,
      what: 'host mở WebSocket',
    );

    await roomApi.start(code);
    await host.waitFor(() => host.started, what: 'host nhận sự kiện started');

    // Khép giai đoạn quyết định thứ tự rồi người đi trước tung di chuyển.
    await host.controller.rollDice(playerId: hostKey);
    await host.waitFor(
      () =>
          host.games.isNotEmpty &&
          host.games.last.players.any((p) => p.orderRolled),
      what: 'host thấy mình đã tung quyết định thứ tự',
    );
    final joinedGuest = joined.players[1].playerKey;
    final guestPlayer = GameController(
      initialPlayers: gamePlayersFromRoom(joined),
      myPlayerKey: joinedGuest,
      online: true,
    );
    guestPlayer.applyGameSnapshot(host.games.last);
    await guestPlayer.rollDice(playerId: joinedGuest);
    await host.waitFor(
      () => host.games.last.orderPhase == false,
      what: 'khép giai đoạn quyết định thứ tự',
    );

    final firstIdx = host.games.last.activePlayerIndex;
    final first = firstIdx == 0 ? host.controller : guestPlayer;
    await first.rollDice();

    final snapResult = await roomApi.fetchGameSnapshot(code);
    expect(snapResult, isA<ApiSuccess<GameSnapshot>>());
    final snapshot = (snapResult as ApiSuccess<GameSnapshot>).data;
    expect(snapshot.code.toUpperCase(), code.toUpperCase());
    expect(snapshot.active, isTrue);
    expect(snapshot.players.length, 2);
    expect(snapshot.players.any((p) => p.position > 0), isTrue);

    host.dispose();
    guestPlayer.dispose();
  });
}
