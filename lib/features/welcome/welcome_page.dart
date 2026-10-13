import 'package:flutter/material.dart';

import '../../core/storage/session_store.dart';
import '../../core/theme/tokens.dart';
import '../../core/ui/components/app_button.dart';
import '../game/presentation/game_page.dart';
import 'lobby_page.dart';

/// Màn hình chào — hero animation, value props, CTA nổi bật.
class WelcomePage extends StatefulWidget {
  const WelcomePage({super.key, this.session});

  final SessionStore? session;

  @override
  State<WelcomePage> createState() => _WelcomePageState();
}

class _WelcomePageState extends State<WelcomePage> with TickerProviderStateMixin {
  late final AnimationController _heroController;
  late final AnimationController _staggerController;
  late final AnimationController _pulseController;

  static const _heroImage =
      'assets/1789902277973_6125801006513112120_g2379287457738176319_17d447179ad3c98e4ecbe1abab295b45.jpg';

  @override
  void initState() {
    super.initState();
    _heroController = AnimationController(
      vsync: this,
      duration: AppMotion.emphasized,
    )..forward();

    _staggerController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 800),
    )..forward();

    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 3),
    )..repeat(reverse: true);
  }

  @override
  void dispose() {
    _heroController.dispose();
    _staggerController.dispose();
    _pulseController.dispose();
    super.dispose();
  }

  void _start(BuildContext context) {
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => LobbyPage(session: widget.session)),
    );
  }

  void _resume(BuildContext context, String roomCode, String playerKey) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => GamePage(
          roomCode: roomCode,
          myPlayerKey: playerKey,
          isOnline: true,
          session: widget.session,
        ),
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
              constraints: const BoxConstraints(maxWidth: Breakpoints.contentMaxWidth),
              child: Column(
                children: [
                  // Hero image với fade + scale
                  _buildHeroImage(isDark),
                  const SizedBox(height: Gap.xxl),

                  // Title với fade
                  _buildTitle(isDark),
                  const SizedBox(height: Gap.lg),

                  // Value props với staggered reveal
                  _buildValueProps(isDark),
                  const SizedBox(height: Gap.xxxl),

                  // CTA với pulse
                  _buildCta(),

                  // Resume banner
                  if (widget.session?.activeRoom case final saved?) ...[
                    const SizedBox(height: Gap.xl),
                    _ResumeBanner(
                      roomCode: saved.roomCode,
                      onResume: () => _resume(context, saved.roomCode, saved.playerKey),
                      onForget: widget.session?.clearActiveRoom,
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

  Widget _buildHeroImage(bool isDark) {
    return FadeTransition(
      opacity: CurvedAnimation(parent: _heroController, curve: AppMotion.enter),
      child: ScaleTransition(
        scale: Tween<double>(begin: 0.96, end: 1.0).animate(
          CurvedAnimation(parent: _heroController, curve: AppMotion.enter),
        ),
        child: ClipRRect(
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
                  style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                    color: Colors.white,
                    fontWeight: FontWeight.w800,
                    height: 1.2,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildTitle(bool isDark) {
    return FadeTransition(
      opacity: CurvedAnimation(
        parent: _staggerController,
        curve: const Interval(0.0, 0.4, curve: AppMotion.enter),
      ),
      child: Text(
        'HOLLAND\nCAREER EXPLORER',
        textAlign: TextAlign.center,
        style: Theme.of(context).textTheme.displayMedium?.copyWith(
          color: isDark ? AppColors.darkPrimary : AppColors.primary,
          fontWeight: FontWeight.w800,
          height: 1.2,
        ),
      ),
    );
  }

  Widget _buildValueProps(bool isDark) {
    return Column(
      children: [
        _StaggeredItem(
          controller: _staggerController,
          index: 0,
          child: _ValuePropRow(
            icon: Icons.psychology_outlined,
            label: 'Khám phá tính cách',
            isDark: isDark,
          ),
        ),
        const SizedBox(height: Gap.md),
        _StaggeredItem(
          controller: _staggerController,
          index: 1,
          child: _ValuePropRow(
            icon: Icons.explore_outlined,
            label: 'Tìm định hướng nghề',
            isDark: isDark,
          ),
        ),
        const SizedBox(height: Gap.md),
        _StaggeredItem(
          controller: _staggerController,
          index: 2,
          child: _ValuePropRow(
            icon: Icons.groups_outlined,
            label: 'Chơi cùng bạn bè',
            isDark: isDark,
          ),
        ),
      ],
    );
  }

  Widget _buildCta() {
    return AnimatedBuilder(
      animation: _pulseController,
      builder: (context, child) {
        final scale = 1.0 + (_pulseController.value * 0.02);
        return Transform.scale(
          scale: scale,
          child: child,
        );
      },
      child: AppButton(
        label: 'Bắt đầu chơi',
        onPressed: () => _start(context),
        icon: Icons.play_arrow_rounded,
        size: AppButtonSize.lg,
        fullWidth: true,
      ),
    );
  }
}

/// Staggered reveal — trượt từ dưới lên 12px + fade, delay 80ms/dòng.
class _StaggeredItem extends StatelessWidget {
  const _StaggeredItem({
    required this.controller,
    required this.index,
    required this.child,
  });

  final AnimationController controller;
  final int index;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final delay = index * 0.15;
    final start = 0.2 + delay;
    final end = start + 0.3;

    return FadeTransition(
      opacity: CurvedAnimation(
        parent: controller,
        curve: Interval(start, end.clamp(0.0, 1.0), curve: AppMotion.enter),
      ),
      child: SlideTransition(
        position: Tween<Offset>(
          begin: const Offset(0, 0.1),
          end: Offset.zero,
        ).animate(
          CurvedAnimation(
            parent: controller,
            curve: Interval(start, end.clamp(0.0, 1.0), curve: AppMotion.enter),
          ),
        ),
        child: child,
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
      color: isDark ? AppColors.resumeBackgroundDark : AppColors.resumeBackground,
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
