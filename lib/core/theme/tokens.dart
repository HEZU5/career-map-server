/// Design tokens cho toàn app — nguồn chân lý duy nhất cho màu sắc,
/// khoảng cách, border radius và đổ bóng.
library;

import 'package:flutter/material.dart';


// ─────────────────────────────────────────────────────────────────────────────
// SPACING — base 4px
// ─────────────────────────────────────────────────────────────────────────────

/// Khoảng cách giữ các element theo thang 4px.
abstract final class Gap {
  static const double xs = 4;
  static const double sm = 8;
  static const double md = 12;
  static const double lg = 16;
  static const double xl = 20;
  static const double xxl = 24;
  static const double xxxl = 32;
  static const double huge = 40;
  static const double massive = 48;
  static const double giant = 64;
  static const double colossal = 80;
}

// ─────────────────────────────────────────────────────────────────────────────
// BORDER RADIUS — 5 cấp
// ─────────────────────────────────────────────────────────────────────────────

abstract final class Rad {
  static const double sm = 8;
  static const double md = 12;
  static const double lg = 16;
  static const double xl = 20;
  static const double full = 999;
}

// ─────────────────────────────────────────────────────────────────────────────
// ELEVATION — 4 cấp shadow
// ─────────────────────────────────────────────────────────────────────────────

abstract final class Elev {
  static const List<BoxShadow> sm = [
    BoxShadow(color: Color(0x0D000000), blurRadius: 2, offset: Offset(0, 1)),
  ];
  static const List<BoxShadow> md = [
    BoxShadow(color: Color(0x12000000), blurRadius: 6, offset: Offset(0, 4)),
  ];
  static const List<BoxShadow> lg = [
    BoxShadow(color: Color(0x14000000), blurRadius: 15, offset: Offset(0, 10)),
  ];
  static const List<BoxShadow> xl = [
    BoxShadow(color: Color(0x1A000000), blurRadius: 25, offset: Offset(0, 20)),
  ];
}

// ─────────────────────────────────────────────────────────────────────────────
// ANIMATION DURATIONS
// ─────────────────────────────────────────────────────────────────────────────

abstract final class Motion {
  static const Duration fast = Duration(milliseconds: 150);
  static const Duration normal = Duration(milliseconds: 250);
  static const Duration slow = Duration(milliseconds: 400);
  static const Curve bounce = Curves.easeOutBack;
  static const Curve smooth = Curves.easeInOutCubic;
}

// ─────────────────────────────────────────────────────────────────────────────
// COLORS
// ─────────────────────────────────────────────────────────────────────────────

abstract final class AppColors {
  // ── Brand — Violet ─────────────────────────────────────────────────
  static const Color primary = Color(0xFF6C4AB6);
  static const Color primaryLight = Color(0xFF9B7ECD);
  static const Color primaryDark = Color(0xFF4B2F87);
  static const Color onPrimary = Color(0xFFFFFFFF);

  // Brand — Dark mode
  static const Color darkPrimary = Color(0xFFA78BFA);
  static const Color darkOnPrimary = Color(0xFF1A1A2E);

  // ── Accent ──────────────────────────────────────────────────────────
  static const Color accent = Color(0xFFF2994A);
  static const Color success = Color(0xFF4ECDC4);
  static const Color error = Color(0xFFEB5757);
  static const Color warning = Color(0xFFF4D03F);

  // ── Neutrals (light) ────────────────────────────────────────────────
  static const Color background = Color(0xFFFAFBFC);
  static const Color surface = Color(0xFFFFFFFF);
  static const Color surfaceVariant = Color(0xFFF0F2F5);
  static const Color border = Color(0xFFE5E7EB);

  // ── Neutrals (dark) ─────────────────────────────────────────────────
  static const Color darkBackground = Color(0xFF0F172A);
  static const Color darkSurface = Color(0xFF1E293B);
  static const Color darkSurfaceVariant = Color(0xFF334155);
  static const Color darkBorder = Color(0xFF475569);

  // ── Text ────────────────────────────────────────────────────────────
  static const Color textPrimary = Color(0xFF1A1A2E);
  static const Color textSecondary = Color(0xFF6B7280);
  static const Color darkTextPrimary = Color(0xFFF1F5F9);
  static const Color darkTextSecondary = Color(0xFF94A3B8);

  // ── Board ───────────────────────────────────────────────────────────
  static const Color boardBackground = Color(0xFFF8EFF6);
  static const Color boardFrame = Color(0xFFEADCEB);

  // ── Players ─────────────────────────────────────────────────────────
  static const Color player1 = Color(0xFFE74C3C);
  static const Color player2 = Color(0xFF5DADE2);
  static const Color player3 = Color(0xFF58D68D);
  static const Color player4 = Color(0xFF9B59B6);
  static const Color leftOverlay = Color(0xFF4A4A4A);

  // ── Semantic ────────────────────────────────────────────────────────
  static const Color primaryButton = Color(0xFFB94A89);
  static const Color secondaryButton = Color(0xFFE9A8C8);
  static const Color gold = Color(0xFFF4D03F);

  // ── Legacy (backward compat) ────────────────────────────────────────
  static const Color violet = primaryDark;
  static const Color pink = Color(0xFFF5E1EC);
  static const Color barrier = Color(0x80000000);

  static const List<Color> heroGradient = [Color(0xFF6C4AB6), Color(0xFF9B7ECD)];
  static const List<Color> darkHeroGradient = [Color(0xFF1E1B4B), Color(0xFF312E81)];
}

// ─────────────────────────────────────────────────────────────────────────────
// SEMANTIC HELPERS
// ─────────────────────────────────────────────────────────────────────────────

/// Trả về màu neutral phù hợp với brightness hiện tại.
Color neutralSurface(BuildContext context) =>
    Theme.of(context).brightness == Brightness.dark
        ? AppColors.darkSurface
        : AppColors.surface;

Color neutralBackground(BuildContext context) =>
    Theme.of(context).brightness == Brightness.dark
        ? AppColors.darkBackground
        : AppColors.background;

Color neutralBorder(BuildContext context) =>
    Theme.of(context).brightness == Brightness.dark
        ? AppColors.darkBorder
        : AppColors.border;

Color textPrimary(BuildContext context) =>
    Theme.of(context).brightness == Brightness.dark
        ? AppColors.darkTextPrimary
        : AppColors.textPrimary;

Color textSecondary(BuildContext context) =>
    Theme.of(context).brightness == Brightness.dark
        ? AppColors.darkTextSecondary
        : AppColors.textSecondary;
