import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'package:flutter/cupertino.dart';

import 'core/storage/session_store.dart';
import 'core/theme/app_theme.dart';
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

/// Root widget — `main.dart` chỉ còn `runApp`, toàn bộ cấu hình
/// MaterialApp nằm ở đây để test được mà không cần chạy main().
class CareerMapApp extends StatelessWidget {
  CareerMapApp({super.key, SessionStore? session, this.themeMode = ThemeMode.light})
    : session = session ?? SessionStore.memory();

  final SessionStore session;
  final ThemeMode themeMode;

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider<ThemeModeProvider>(
      create: (_) => ThemeModeProvider(themeMode),
      child: Consumer<ThemeModeProvider>(
        builder: (context, themeProvider, _) {
          return MaterialApp(
            debugShowCheckedModeBanner: false,
            title: 'Holland Career Explorer',
            theme: buildAppTheme(brightness: Brightness.light),
            darkTheme: buildAppTheme(brightness: Brightness.dark),
            themeMode: themeProvider.themeMode,
            home: WelcomePage(session: session),
            pageTransitionsTheme: PageTransitionsTheme(
              builders: {
                TargetPlatform.android: const CupertinoPageTransitionsBuilder(),
                TargetPlatform.iOS: const CupertinoPageTransitionsBuilder(),
                TargetPlatform.macOS: const CupertinoPageTransitionsBuilder(),
                TargetPlatform.windows: const FadeUpwardsPageTransitionsBuilder(),
                TargetPlatform.linux: const FadeUpwardsPageTransitionsBuilder(),
                TargetPlatform.fuchsia: const FadeUpwardsPageTransitionsBuilder(),
              },
            ),
          );
        },
      ),
    );
  }
}
