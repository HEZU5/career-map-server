import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

import 'tokens.dart';
export 'app_motion.dart';

/// Theme tập trung duy nhất tại đây.
/// Light + Dark mode, Be Vietnam Pro, Material 3.
ThemeData buildAppTheme({Brightness brightness = Brightness.light}) {
  final isDark = brightness == Brightness.dark;
  final colorScheme = ColorScheme.fromSeed(
    seedColor: AppColors.violet,
    brightness: brightness,
    primary: isDark ? AppColors.darkPrimary : AppColors.primary,
    onPrimary: isDark ? AppColors.darkOnPrimary : AppColors.onPrimary,
    surface: isDark ? AppColors.darkSurface : AppColors.surface,
    surfaceContainerHighest: isDark ? AppColors.darkSurfaceVariant : AppColors.surfaceVariant,
  );

  final baseTextTheme = GoogleFonts.beVietnamProTextTheme();

  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: colorScheme,
    scaffoldBackgroundColor: isDark ? AppColors.darkBackground : AppColors.background,
    textTheme: _buildTextTheme(baseTextTheme, isDark),
    appBarTheme: AppBarTheme(
      centerTitle: true,
      backgroundColor: isDark ? AppColors.darkSurface : AppColors.surface,
      foregroundColor: isDark ? AppColors.darkTextPrimary : AppColors.textPrimary,
      elevation: 0,
      scrolledUnderElevation: 2,
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(64, 48),
        textStyle: GoogleFonts.beVietnamPro(
          fontSize: 15,
          fontWeight: FontWeight.w700,
        ),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(Rad.lg),
        ),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(64, 48),
        textStyle: GoogleFonts.beVietnamPro(
          fontSize: 15,
          fontWeight: FontWeight.w600,
        ),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(Rad.lg),
        ),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        textStyle: GoogleFonts.beVietnamPro(
          fontSize: 14,
          fontWeight: FontWeight.w600,
        ),
      ),
    ),
    cardTheme: CardThemeData(
      elevation: 0,
      color: isDark ? AppColors.darkSurface : AppColors.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(Rad.lg),
        side: BorderSide(
          color: isDark ? AppColors.darkBorder : AppColors.border,
        ),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: isDark ? AppColors.darkSurfaceVariant : AppColors.surfaceVariant,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(Rad.md),
        borderSide: BorderSide(
          color: isDark ? AppColors.darkBorder : AppColors.border,
        ),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(Rad.md),
        borderSide: BorderSide(
          color: isDark ? AppColors.darkBorder : AppColors.border,
        ),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(Rad.md),
        borderSide: BorderSide(
          color: isDark ? AppColors.darkPrimary : AppColors.primary,
          width: 2,
        ),
      ),
      contentPadding: const EdgeInsets.symmetric(
        horizontal: Gap.lg,
        vertical: Gap.lg,
      ),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: isDark ? AppColors.darkSurface : AppColors.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(Rad.xl),
      ),
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(Rad.md),
      ),
    ),
    dividerTheme: DividerThemeData(
      color: isDark ? AppColors.darkBorder : AppColors.border,
      thickness: 1,
      space: 1,
    ),
  );
}

TextTheme _buildTextTheme(TextTheme base, bool isDark) {
  final primary = isDark ? AppColors.darkTextPrimary : AppColors.textPrimary;
  final secondary = isDark ? AppColors.darkTextSecondary : AppColors.textSecondary;

  return base.copyWith(
    displayLarge: base.displayLarge?.copyWith(
      fontSize: 40,
      fontWeight: FontWeight.w800,
      color: primary,
      letterSpacing: -0.5,
    ),
    displayMedium: base.displayMedium?.copyWith(
      fontSize: 32,
      fontWeight: FontWeight.w800,
      color: primary,
      letterSpacing: -0.5,
    ),
    headlineLarge: base.headlineLarge?.copyWith(
      fontSize: 28,
      fontWeight: FontWeight.w700,
      color: primary,
    ),
    headlineMedium: base.headlineMedium?.copyWith(
      fontSize: 24,
      fontWeight: FontWeight.w700,
      color: primary,
    ),
    headlineSmall: base.headlineSmall?.copyWith(
      fontSize: 20,
      fontWeight: FontWeight.w600,
      color: primary,
    ),
    titleLarge: base.titleLarge?.copyWith(
      fontSize: 18,
      fontWeight: FontWeight.w600,
      color: primary,
    ),
    titleMedium: base.titleMedium?.copyWith(
      fontSize: 16,
      fontWeight: FontWeight.w600,
      color: primary,
    ),
    bodyLarge: base.bodyLarge?.copyWith(
      fontSize: 16,
      fontWeight: FontWeight.w400,
      color: primary,
      height: 1.5,
    ),
    bodyMedium: base.bodyMedium?.copyWith(
      fontSize: 14,
      fontWeight: FontWeight.w400,
      color: primary,
      height: 1.5,
    ),
    bodySmall: base.bodySmall?.copyWith(
      fontSize: 12,
      fontWeight: FontWeight.w400,
      color: secondary,
      height: 1.4,
    ),
    labelLarge: base.labelLarge?.copyWith(
      fontSize: 14,
      fontWeight: FontWeight.w500,
      color: primary,
    ),
    labelMedium: base.labelMedium?.copyWith(
      fontSize: 13,
      fontWeight: FontWeight.w500,
      color: primary,
    ),
    labelSmall: base.labelSmall?.copyWith(
      fontSize: 11,
      fontWeight: FontWeight.w500,
      color: secondary,
    ),
  );
}
