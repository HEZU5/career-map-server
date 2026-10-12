import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import '../../../core/config/api_config.dart';

/// Kết quả trả về dạng sealed — code cũ `return null` khi lỗi
/// nên UI không phân biệt được offline / server lỗi / sai dữ liệu.
sealed class ApiResult<T> {
  const ApiResult();
}

class ApiSuccess<T> extends ApiResult<T> {
  const ApiSuccess(this.data);
  final T data;
}

class ApiFailure<T> extends ApiResult<T> {
  const ApiFailure(this.message);
  final String message;
}

/// Repository gọi server. Inject `http.Client` để test được,
/// `close()` để tránh leak socket (code cũ không bao giờ close).
abstract class GameApi {
  Future<ApiResult<Map<String, dynamic>>> get(String path);

  /// GET trả về JSON **mảng** (vd danh sách phòng công khai). [get] chỉ nhận
  /// object nên cần hàm riêng thay vì ép kiểu.
  Future<ApiResult<List<Map<String, dynamic>>>> getList(String path);

  Future<ApiResult<Map<String, dynamic>>> post(String path, Map<String, dynamic> body);
  Future<ApiResult<Map<String, dynamic>>> put(String path, Map<String, dynamic> body);
  Future<ApiResult<Map<String, dynamic>>> delete(String path);
  void dispose();
}

class HttpGameApi implements GameApi {
  HttpGameApi({http.Client? client, String? baseUrl})
    : _client = client ?? http.Client(),
      _ownsClient = client == null,
      _baseUrl = baseUrl ?? defaultBaseUrl();

  final http.Client _client;
  final bool _ownsClient;
  final String _baseUrl;

  /// Base URL thực tế của các request (localhost hay địa chỉ deploy) — hiển
  /// thị lại cho người dùng để biết client đang gọi đi đâu.
  String get baseUrl => _baseUrl;

  /// Địa chỉ server do [ApiConfig] quyết định (một nguồn chân lý duy nhất,
  /// dùng chung với WebSocket). Có thể ghi đè bằng
  /// `--dart-define=API_BASE_URL=https://<domain server>`; web release thiếu
  /// define sẽ tự dùng URL production thay vì localhost.
  static String defaultBaseUrl() => ApiConfig.baseUrl;

  // ---------------------------------------------------------------------
  // Đánh thức server (Render free plan)
  // ---------------------------------------------------------------------

  /// Render bản MIỄN PHÍ ngủ sau ~15 phút không có request; lần khởi động đầu
  /// mất 30–90 giây, vượt xa timeout vài giây → sinh ra thông báo "Không kết
  /// nối được server" dù server hoàn toàn ổn.
  ///
  /// Gọi `GET /health` (không có tác dụng phụ) để đánh thức server TRƯỚC khi
  /// làm việc thật, và cache kết quả trong [warmUpTtl] để không đánh thức ở
  /// mọi request. Chỉ áp dụng cho server thật; `localhost` không ngủ nên bỏ
  /// qua để dev không bị chậm.
  static Future<void>? _warming;
  static DateTime? _warmedAt;
  static String? _warmedUrl;
  static DateTime? _warmUpFailedAt;
  static String? _warmUpFailedUrl;
  static const Duration warmUpTtl = Duration(minutes: 5);

  /// Lần thất bại gần nhất được bỏ qua trong khoảng thời gian này, để một
  /// `/health` hỏng (CORS, mạng, server chết) KHÔNG bị thử lại ở mọi request
  /// và không kéo dài mỗi lượt chơi.
  static const Duration warmUpBackoff = Duration(seconds: 30);
  static const Duration warmUpTimeout = Duration(seconds: 75);

  Future<void> _ensureServerAwake() {
    if (_baseUrl.contains('localhost') || _baseUrl.contains('127.0.0.1')) {
      return Future<void>.value();
    }
    final now = DateTime.now();
    // Cache gắn với đúng base URL: đổi server (test, multi-tenant) thì phải
    // đánh thức lại, không dựa vào kết quả của server khác.
    if (_warmedUrl == _baseUrl && _warmedAt != null && now.difference(_warmedAt!) < warmUpTtl) {
      return Future<void>.value();
    }
    if (_warmUpFailedUrl == _baseUrl &&
        _warmUpFailedAt != null &&
        now.difference(_warmUpFailedAt!) < warmUpBackoff) {
      return Future<void>.value();
    }
    return _warming ??= () async {
      final sw = Stopwatch()..start();
      try {
        await _client.get(Uri.parse('$_baseUrl/health')).timeout(warmUpTimeout);
        _warmedAt = DateTime.now();
        _warmedUrl = _baseUrl;
        debugPrint(
          '[GameApi] server đã sẵn sàng ($_baseUrl) '
          '(${sw.elapsedMilliseconds}ms)',
        );
      } catch (e) {
        // Không ném lỗi và KHÔNG chặn request thật: request sau vẫn đi bình
        // thường, chỉ là không được đánh thức trước. Lưu mốc để không thử lại
        // liên tục.
        _warmUpFailedAt = DateTime.now();
        _warmUpFailedUrl = _baseUrl;
        debugPrint(
          '[GameApi] đánh thức server thất bại ($_baseUrl): $e '
          '(${sw.elapsedMilliseconds}ms) — bỏ qua ${warmUpBackoff.inSeconds}s',
        );
      } finally {
        _warming = null;
      }
    }();
  }

  /// Server đã tỉnh chưa (cache trong [warmUpTtl]) — UI dùng để nhắc người
  /// chơi rằng lần kết nối đầu có thể phải chờ vài chục giây.
  bool get isServerWarm =>
      _warmedUrl == _baseUrl &&
      _warmedAt != null &&
      DateTime.now().difference(_warmedAt!) < warmUpTtl;

  /// Server đã cấu hình (`ApiConfig.baseUrl`) đã tỉnh chưa — màn lobby dùng
  /// để quyết định có nhắc "đang khởi động server" hay không.
  static bool get isConfiguredServerWarm =>
      _warmedUrl == ApiConfig.baseUrl &&
      _warmedAt != null &&
      DateTime.now().difference(_warmedAt!) < warmUpTtl;

  /// Đánh thức server ngay, không gọi API nghiệp vụ nào. Màn lobby gọi lúc
  /// mở để giấu chờ 60–90s của Render free plan trước khi người chơi bấm
  /// "Tạo phòng". Không ném lỗi, không chặn gì cả.
  Future<void> warmUpServer() => _ensureServerAwake();

  /// Timeout theo phương thức: GET vô hại nên chờ lâu hơn (poll snapshot lúc
  /// server vừa tỉnh), các lệnh ghi giữ timeout ngắn để lượt chơi không kẹt.
  Duration _timeoutFor(String method) => switch (method) {
    'GET' => const Duration(seconds: 20),
    _ => const Duration(seconds: 10),
  };

  /// Ép JSON thô của [_send] thành object; sai định dạng thì báo lỗi rõ ràng
  /// thay vì crash.
  ApiResult<Map<String, dynamic>> _obj(ApiResult<Object?> result) {
    if (result case ApiFailure(:final message)) return ApiFailure(message);
    final decoded = (result as ApiSuccess).data;
    if (decoded is! Map<String, dynamic>) {
      return const ApiFailure('Dữ liệu server không đúng định dạnh.');
    }
    return ApiSuccess(decoded);
  }

  @override
  Future<ApiResult<Map<String, dynamic>>> get(String path) async => _obj(
    await _send('GET', Uri.parse('$_baseUrl$path'), () => _client.get(Uri.parse('$_baseUrl$path'))),
  );

  @override
  Future<ApiResult<List<Map<String, dynamic>>>> getList(String path) async {
    final result = await _send(
      'GET',
      Uri.parse('$_baseUrl$path'),
      () => _client.get(Uri.parse('$_baseUrl$path')),
    );
    if (result case ApiFailure(:final message)) {
      return ApiFailure(message);
    }
    final decoded = (result as ApiSuccess).data;
    if (decoded is! List) {
      return const ApiFailure('Dữ liệu server không đúng định dạng.');
    }
    return ApiSuccess(decoded.whereType<Map<String, dynamic>>().toList());
  }

  @override
  Future<ApiResult<Map<String, dynamic>>> post(String path, Map<String, dynamic> body) async =>
      _obj(
        await _send(
          'POST',
          Uri.parse('$_baseUrl$path'),
          () => _client.post(
            Uri.parse('$_baseUrl$path'),
            headers: const {'Content-Type': 'application/json'},
            body: jsonEncode(body),
          ),
        ),
      );

  @override
  Future<ApiResult<Map<String, dynamic>>> delete(String path) async => _obj(
    await _send(
      'DELETE',
      Uri.parse('$_baseUrl$path'),
      () => _client.delete(
        Uri.parse('$_baseUrl$path'),
        headers: const {'Content-Type': 'application/json'},
      ),
    ),
  );

  @override
  Future<ApiResult<Map<String, dynamic>>> put(String path, Map<String, dynamic> body) async => _obj(
    await _send(
      'PUT',
      Uri.parse('$_baseUrl$path'),
      () => _client.put(
        Uri.parse('$_baseUrl$path'),
        headers: const {'Content-Type': 'application/json'},
        body: jsonEncode(body),
      ),
    ),
  );

  /// Gửi request + LOG ĐẦY ĐỦ ra console (đúng yêu cầu chẩn đoán): phương
  /// thức, URL thật, status code, thời gian, và nội dung lỗi nếu có. Khi web
  /// build thiếu `API_BASE_URL` thì log sẽ bộc lộ ngay URL localhost.
  ///
  /// Trả về JSON **thô** (`Object?`) vì có endpoint trả mảng; các hàm public
  /// ([get]/[getList]/[post]…) ép về kiểu cần dùng và báo lỗi nếu sai định dạng.
  Future<ApiResult<Object?>> _send(
    String method,
    Uri uri,
    Future<http.Response> Function() request,
  ) async {
    final stopwatch = Stopwatch()..start();
    // Đánh thức server trước (bỏ qua nếu vừa kiểm tra) để không dính timeout
    // lúc Render đang khởi động lại từ chế độ ngủ.
    await _ensureServerAwake();
    stopwatch.reset();
    try {
      final response = await request().timeout(_timeoutFor(method));
      stopwatch.stop();
      final ok = response.statusCode >= 200 && response.statusCode < 300;
      debugPrint(
        '[GameApi] $method $uri -> ${response.statusCode} '
        '(${stopwatch.elapsedMilliseconds}ms)',
      );
      if (!ok) {
        debugPrint('[GameApi] $method $uri body: ${response.body}');
        return ApiFailure('Server trả ${response.statusCode}.');
      }
      final decoded = jsonDecode(response.body);
      // Trả JSON thô; `get`/`getList` ép về object hoặc mảng tuỳ endpoint.
      return ApiSuccess(decoded);
    } on TimeoutException {
      stopwatch.stop();
      debugPrint(
        '[GameApi] $method $uri HET THOI GIAN CHO '
        '(${stopwatch.elapsedMilliseconds}ms)',
      );
      return const ApiFailure('Hết thời gian chờ server.');
    } on FormatException {
      debugPrint('[GameApi] $method $uri JSON lỗi.');
      return const ApiFailure('Không đọc được JSON từ server.');
    } catch (e) {
      stopwatch.stop();
      debugPrint(
        '[GameApi] $method $uri LOI: $e '
        '(${stopwatch.elapsedMilliseconds}ms)',
      );
      return const ApiFailure(
        'Không kết nối được server. Nếu server vừa được khởi động lại, hãy thử lại sau 30 giây.',
      );
    }
  }

  @override
  void dispose() {
    if (_ownsClient) _client.close();
  }
}
