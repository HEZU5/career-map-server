import 'package:career_map/features/game/data/game_api.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('đánh thức server bằng /health trước request đầu, rồi cache lại', () async {
    var healthCalls = 0;
    var apiCalls = 0;
    final client = MockClient((request) async {
      if (request.url.path == '/health') {
        healthCalls++;
        return http.Response('{"status":"ok"}', 200);
      }
      apiCalls++;
      return http.Response('{"code":"A1B2"}', 200);
    });
    // URL KHÔNG phải localhost để đúng nhánh "server thật" (Render ngủ rồi
    // tỉnh dậy cần vài chục giây).
    final api = HttpGameApi(client: client, baseUrl: 'https://example.test');
    addTearDown(api.dispose);

    await api.get('/api/rooms/A1B2');
    expect(healthCalls, 1, reason: 'phải gọi /health trước request thật');
    expect(apiCalls, 1);

    await api.get('/api/rooms/A1B2');
    expect(
      healthCalls,
      1,
      reason:
          'trong thời gian cache thì không đánh thức lại (tránh trễ mỗi call)',
    );
    expect(apiCalls, 2);
  });

  test('localhost thì không gọi /health (server local không ngủ)', () async {
    var healthCalls = 0;
    final client = MockClient((request) async {
      if (request.url.path == '/health') healthCalls++;
      return http.Response('{"code":"A1B2"}', 200);
    });
    final api = HttpGameApi(client: client, baseUrl: 'http://localhost:8080');
    addTearDown(api.dispose);

    await api.get('/api/rooms/A1B2');
    expect(healthCalls, 0);
  });

  test(
    '/health hỏng thì request thật vẫn chạy, không thử lại liên tục',
    () async {
      var healthCalls = 0;
      var apiCalls = 0;
      final client = MockClient((request) async {
        if (request.url.path == '/health') {
          healthCalls++;
          throw http.ClientException('CORS /health bị chặn');
        }
        apiCalls++;
        return http.Response('{"code":"A1B2"}', 200);
      });
      final api = HttpGameApi(
        client: client,
        baseUrl: 'https://broken.example',
      );
      addTearDown(api.dispose);

      await api.get('/api/rooms/A1B2');
      await api.get('/api/rooms/A1B2');
      await api.get('/api/rooms/A1B2');

      // Request thật không bị chặn bởi việc đánh thức hỏng.
      expect(apiCalls, 3);
      // Chỉ thử /health MỘT lần rồi backoff, không thử lại ở mỗi request.
      expect(healthCalls, 1);
    },
  );

  test('warmUpServer() chỉ gọi /health, không gọi API nghiệp vụ', () async {
    final paths = <String>[];
    final client = MockClient((request) async {
      paths.add(request.url.path);
      return http.Response('{"status":"ok"}', 200);
    });
    final api = HttpGameApi(client: client, baseUrl: 'https://prewarm.example');
    addTearDown(api.dispose);

    await api.warmUpServer();

    expect(paths, ['/health']);
  });
}
