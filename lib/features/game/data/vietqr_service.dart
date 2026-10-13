import 'dart:convert';
import 'dart:typed_data';

import 'package:http/http.dart' as http;

import '../../../core/config/api_config.dart';

/// Kết quả tạo QR code từ VietQR API.
class VietQrResult {
  const VietQrResult({required this.qrDataUrl, required this.qrCode});

  /// ảnh QR dạng base64 data URI (`data:image/png;base64,...`).
  final String qrDataUrl;

  /// Mã QR dạng text (dùng tra cứu).
  final String qrCode;

  /// Decode `qrDataUrl` thành bytes để hiển thị bằng `Image.memory`.
  Uint8List? get imageBytes {
    final commaIndex = qrDataUrl.indexOf(',');
    if (commaIndex < 0) return null;
    try {
      return base64Decode(qrDataUrl.substring(commaIndex + 1));
    } catch (_) {
      return null;
    }
  }
}

/// Service gọi VietQR API tạo mã QR thanh toán.
///
/// API: POST https://api.vietqr.io/v2/generate
/// Trả về ảnh QR dạng base64 data URI để hiển thị trực tiếp.
class VietQrService {
  VietQrService({http.Client? client}) : _client = client ?? http.Client();

  final http.Client _client;

  /// Tạo mã QR thanh toán.
  ///
  /// [amount] — số tiền VND (bắt buộc).
  /// [addInfo] — nội dung chuyển khoản (tối đa 25 ký tự, không dấu).
  Future<VietQrResult> generateQr({
    required int amount,
    String addInfo = 'Thanh toan Career Map',
  }) async {
    final response = await _client.post(
      Uri.parse('${ApiConfig.vietQrBaseUrl}/v2/generate'),
      headers: {
        'Content-Type': 'application/json',
        'x-client-id': ApiConfig.vietQrClientId,
        'x-api-key': ApiConfig.vietQrApiKey,
      },
      body: jsonEncode({
        'accountNo': ApiConfig.vietQrAccountNo,
        'accountName': ApiConfig.vietQrAccountName,
        'acqId': ApiConfig.vietQrAcqId,
        'amount': amount,
        'addInfo': addInfo,
        'template': 'compact',
      }),
    );

    if (response.statusCode != 200) {
      throw Exception('VietQR API error: ${response.statusCode}');
    }

    final json = jsonDecode(response.body) as Map<String, dynamic>;
    final data = json['data'] as Map<String, dynamic>?;
    if (data == null) {
      throw Exception('VietQR API: no data in response');
    }

    return VietQrResult(
      qrDataUrl: data['qrDataURL'] as String? ?? '',
      qrCode: data['qrCode'] as String? ?? '',
    );
  }

  void dispose() => _client.close();
}
