import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'core/storage/session_store.dart';
import 'core/theme/app_theme.dart';
import 'features/auth/data/auth_repository.dart';
import 'features/auth/presentation/auth_state_provider.dart';
import 'features/auth/presentation/login_page.dart';
import 'features/auth/presentation/profile_page.dart';
import 'features/auth/presentation/register_page.dart';
import 'features/game/presentation/payment_page.dart';
import 'features/shop/presentation/shop_page.dart';
import 'features/welcome/welcome_page.dart';

/// Theme mode provider — lưu preference và rebuild MaterialApp khi đổi.
class ThemeModeProvider extends ChangeNotifier {
  ThemeModeProvider(this._themeMode);

  ThemeMode _themeMode;

  ThemeMode get themeMode => _themeMode;

  void setThemeMode(ThemeMode mode) {
    if (_themeMode == mode) return;
    _themeMode = mode;
    notifyListeners();
  }

  void toggle() {
    setThemeMode(
      _themeMode == ThemeMode.dark ? ThemeMode.light : ThemeMode.dark,
    );
  }
}

/// Wrapper chọn màn hình theo trạng thái đăng nhập.
///
/// - Chưa login → LoginPage
/// - Đã login → WelcomePage
class AuthWrapper extends StatelessWidget {
  const AuthWrapper({super.key, required this.session});

  final SessionStore session;

  @override
  Widget build(BuildContext context) {
    final user = AuthStateProvider.of(context, listen: true).user;
    if (user == null) return LoginPage(session: session);
    return WelcomePage(session: session);
  }
}

/// Root widget — `main.dart` chỉ còn `runApp`, toàn bộ cấu hình
/// MaterialApp nằm ở đây để test được mà không cần chạy main().
class CareerMapApp extends StatelessWidget {
  CareerMapApp({
    super.key,
    SessionStore? session,
    this.themeMode = ThemeMode.light,
    this.initialRoute,
  }) : session = session ?? SessionStore.memory();

  final SessionStore session;
  final ThemeMode themeMode;
  final String? initialRoute;

  /// Route trả kết quả thanh toán — VNPay đưa người chơi về đây.
  static const String paymentResultRoute = '/ket-qua-thanh-toan';

  /// Bỏ query string + fragment khỏi tên route, chỉ giữ lại PATH.
  ///
  /// Vì sao cần: VNPay **luôn** append query vào `vnp_ReturnUrl`
  /// (`vnp_Amount`, `vnp_TxnRef`, `vnp_BankCode`, `vnp_SecureHash`…). Mà
  /// `main.dart` gọi `usePathUrlStrategy()` nên `defaultRouteName` của Flutter
  /// = **path + query** ⇒ tên route là
  /// `/ket-qua-thanh-toan?vnp_Amount=5000000&...` ⇒ KHÔNG khớp key
  /// `/ket-qua-thanh-toan` trong `routes` ⇒ Navigator fallback về `/` ⇒
  /// **sau khi trả tiền người chơi bị đưa về màn hình chính, không thấy báo
  /// cáo**. Bóc path ra là đủ để deep-link luôn tới đúng trang, kể cả khi
  /// gateway (hoặc proxy) tự thêm query.
  ///
  /// Dấu `/` CUỐI cũng bị bỏ: `/ket-qua-thanh-toan/` phải khớp key route
  /// `/ket-qua-thanh-toan` — nếu không thì deep-link có dấu "/" cuối rơi về
  /// màn hình chính, tức lại mất báo cáo sau khi thanh toán.
  static String normalizeRouteName(String raw) {
    if (raw.isEmpty) return raw;
    try {
      final uri = Uri.parse(raw);
      if (uri.path.isEmpty) return raw;
      final path = uri.path;
      // Bỏ các dấu "/" ở CUỐI, nhưng giữ nguyên "/" (route gốc).
      final trimmed = path.length > 1
          ? path.replaceFirst(RegExp(r'/+$'), '')
          : path;
      return trimmed.isEmpty ? '/' : trimmed;
    } on FormatException {
      // Không parse được URI ⇒ giữ nguyên tên route cũ.
      return raw;
    }
  }

  /// Dựng route khi tên route có query string (deep-link từ VNPay).
  ///
  /// Trả `null` khi route lạ ⇒ `MaterialApp` xử lý tiếp như trước (fallback
  /// về màn hình chính).
  Route<dynamic>? _generateRoute(
    RouteSettings settings,
    Map<String, WidgetBuilder> routes,
  ) {
    final name = settings.name ?? '/';
    final normalized = normalizeRouteName(name);
    final builder = routes[normalized];
    if (builder == null) return null;
    return MaterialPageRoute<dynamic>(builder: builder, settings: settings);
  }

  @override
  Widget build(BuildContext context) {
    final routes = <String, WidgetBuilder>{
      paymentResultRoute: (_) => PaymentPage(session: session),
      '/dang-nhap': (_) => LoginPage(session: session),
      '/dang-ky': (_) => RegisterPage(session: session),
      '/ho-so': (_) => ProfilePage(session: session),
      '/cua-hang': (_) => const ShopPage(),
    };
    return ChangeNotifierProvider<ThemeModeProvider>(
      create: (_) => ThemeModeProvider(themeMode),
      child: ChangeNotifierProvider<AuthStateProvider>(
        create: (_) {
          final provider = AuthStateProvider(FirebaseAuthRepository(), session);
          provider.init();
          return provider;
        },
        child: Consumer<ThemeModeProvider>(
          builder: (context, themeProvider, _) {
            return MaterialApp(
              debugShowCheckedModeBanner: false,
              title: 'Holland Career Explorer',
              theme: buildAppTheme(brightness: Brightness.light),
              darkTheme: buildAppTheme(brightness: Brightness.dark),
              themeMode: themeProvider.themeMode,
              home: AuthWrapper(session: session),
              initialRoute: initialRoute,
              routes: routes,
              // Deep-link từ VNPay có query string → bóc path rồi khớp route.
              onGenerateRoute: (settings) => _generateRoute(settings, routes),
            );
          },
        ),
      ),
    );
  }
}
