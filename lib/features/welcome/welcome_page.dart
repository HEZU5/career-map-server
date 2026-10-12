import 'package:flutter/material.dart';

import '../../core/storage/session_store.dart';
import '../../core/theme/tokens.dart';
import '../../core/ui/components/app_button.dart';
import '../game/presentation/game_page.dart';
import 'lobby_page.dart';

/// Màn hình chào — hero animation, value props, CTA nổi bật.
class WelcomePage extends StatelessWidget {
  const WelcomePage({super.key, this.session});

  final SessionStore? session;

  static const _heroImage =
      'assets/1789902277973_6125801006513112120_g2379287457738176319_17d447179ad3c98e4ecbe1abab295b45.jpg';

  void _start(BuildContext context) {
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => LobbyPage(session: session)));
  }

  void _resume(BuildContext context, String roomCode, String playerKey) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) =>
            GamePage(roomCode: roomCode, myPlayerKey: playerKey, isOnline: true, session: session),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isDark = theme.brightness == Brightness.dark;

    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(Gap.xxl),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 520),
              child: Column(
                children: [
                  // Hero image với gradient overlay
                  ClipRRect(
                    borderRadius: BorderRadius.circular(Rad.xl),
                    child: Stack(
                      children: [
                        Image.asset(_heroImage),
                        Positioned(
                          bottom: 0,
                          left: 0,
                          right: 0,
                          child: Container(
                            height: 120,
                            decoration: BoxDecoration(
                              gradient: LinearGradient(
                                begin: Alignment.topCenter,
                                end: Alignment.bottomCenter,
                                colors: [
                                  Colors.transparent,
                                  (isDark ? AppColors.darkPrimary : AppColors.primary)
                                      .withValues(alpha: 0.8),
                                ],
                              ),
                            ),
                          ),
                        ),
                        Positioned(
                          bottom: Gap.lg,
                          left: Gap.xl,
                          right: Gap.xl,
                          child: Text(
                            'Khám phá bản thân\nĐịnh hướng tương lai',
                            style: theme.textTheme.headlineMedium?.copyWith(
                              color: Colors.white,
                              fontWeight: FontWeight.w800,
                              height: 1.2,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: Gap.xxl),

                  // Title
                  Text(
                    'HOLLAND\nCAREER EXPLORER',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.displayMedium?.copyWith(
                      color: isDark ? AppColors.darkPrimary : AppColors.primary,
                      fontWeight: FontWeight.w800,
                      height: 1.2,
                    ),
                  ),
                  const SizedBox(height: Gap.lg),

                  // Value props
                  _ValuePropRow(
                    icon: Icons.psychology_outlined,
                    label: 'Khám phá tính cách',
                    isDark: isDark,
                  ),
                  const SizedBox(height: Gap.md),
                  _ValuePropRow(
                    icon: Icons.explore_outlined,
                    label: 'Tìm định hướng nghề',
                    isDark: isDark,
                  ),
                  const SizedBox(height: Gap.md),
                  _ValuePropRow(
                    icon: Icons.groups_outlined,
                    label: 'Chơi cùng bạn bè',
                    isDark: isDark,
                  ),
                  const SizedBox(height: Gap.xxxl),

                  // CTA
                  AppButton(
                    label: 'Bắt đầu chơi',
                    onPressed: () => _start(context),
                    icon: Icons.play_arrow_rounded,
                    size: AppButtonSize.lg,
                    fullWidth: true,
                  ),

                  // Resume banner
                  if (session?.activeRoom case final saved?) ...[
                    const SizedBox(height: Gap.xl),
                    _ResumeBanner(
                      roomCode: saved.roomCode,
                      onResume: () => _resume(context, saved.roomCode, saved.playerKey),
                      onForget: session?.clearActiveRoom,
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ValuePropRow extends StatelessWidget {
  const _ValuePropRow({
    required this.icon,
    required this.label,
    required this.isDark,
  });

  final IconData icon;
  final String label;
  final bool isDark;

  @override
  Widget build(BuildContext context) {
    final primary = isDark ? AppColors.darkPrimary : AppColors.primary;

    return Row(
      children: [
        Container(
          width: 36,
          height: 36,
          decoration: BoxDecoration(
            color: primary.withValues(alpha: 0.1),
            borderRadius: BorderRadius.circular(Rad.sm),
          ),
          child: Icon(icon, size: 18, color: primary),
        ),
        const SizedBox(width: Gap.md),
        Text(
          label,
          style: TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w500,
            color: isDark ? AppColors.darkTextPrimary : AppColors.textPrimary,
          ),
        ),
      ],
    );
  }
}

/// Nhắc "bạn đang chơi ván dở" ngay màn chào, sau khi F5.
class _ResumeBanner extends StatefulWidget {
  const _ResumeBanner({required this.roomCode, required this.onResume, required this.onForget});

  final String roomCode;
  final VoidCallback onResume;
  final VoidCallback? onForget;

  @override
  State<_ResumeBanner> createState() => _ResumeBannerState();
}

class _ResumeBannerState extends State<_ResumeBanner> {
  bool _dismissed = false;

  @override
  Widget build(BuildContext context) {
    if (_dismissed) return const SizedBox.shrink();
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Material(
      color: isDark ? const Color(0xFF1A3A2A) : const Color(0xFFE8F5E9),
      borderRadius: BorderRadius.circular(Rad.lg),
      child: Padding(
        padding: const EdgeInsets.all(Gap.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.history_rounded, color: Colors.green.shade700),
                const SizedBox(width: Gap.sm),
                Expanded(
                  child: Text(
                    'Bạn đang chơi ván ở phòng này',
                    style: TextStyle(
                      fontWeight: FontWeight.w600,
                      color: isDark ? AppColors.darkTextPrimary : AppColors.textPrimary,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: Gap.xs),
            Text(
              'Mã phòng ${widget.roomCode}',
              style: TextStyle(
                fontSize: 13,
                color: isDark ? AppColors.darkTextSecondary : AppColors.textSecondary,
              ),
            ),
            const SizedBox(height: Gap.md),
            Row(
              children: [
                Expanded(
                  child: AppButton(
                    label: 'Vào lại trận',
                    onPressed: widget.onResume,
                    icon: Icons.play_circle_outline,
                    size: AppButtonSize.sm,
                  ),
                ),
                const SizedBox(width: Gap.sm),
                AppButton(
                  label: 'Bỏ ván này',
                  onPressed: () {
                    widget.onForget?.call();
                    setState(() => _dismissed = true);
                  },
                  variant: AppButtonVariant.ghost,
                  size: AppButtonSize.sm,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
