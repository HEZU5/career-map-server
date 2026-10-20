import 'package:flutter/material.dart';

import '../../../core/storage/session_store.dart';
import '../../../core/theme/tokens.dart';
import '../../../core/ui/components/app_avatar.dart';
import '../../../core/ui/components/app_button.dart';
import '../../../core/ui/components/app_input.dart';
import 'auth_state_provider.dart';

/// Màn hình hồ sơ cá nhân — xem/sửa thông tin và đăng xuất.
class ProfilePage extends StatefulWidget {
  const ProfilePage({super.key, required this.session});

  final SessionStore session;

  @override
  State<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends State<ProfilePage> {
  late final TextEditingController _nameController;
  bool _isEditing = false;

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(
      text: AuthStateProvider.of(context, listen: false).user?.displayName ?? '',
    );
  }

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  Future<void> _handleUpdate() async {
    final auth = AuthStateProvider.of(context, listen: false);
    await auth.updateProfile(displayName: _nameController.text.trim());
    if (mounted && auth.error == null) {
      setState(() => _isEditing = false);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Cập nhật hồ sơ thành công')),
      );
    }
  }

  Future<void> _handleLogout() async {
    final auth = AuthStateProvider.of(context, listen: false);
    await auth.logout();
    if (mounted) Navigator.of(context).pushReplacementNamed('/');
  }

  @override
  Widget build(BuildContext context) {
    final auth = AuthStateProvider.of(context, listen: true);
    final user = auth.user;
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      body: SafeArea(
        child: CustomScrollView(
          slivers: [
            SliverAppBar(
              pinned: true,
              expandedHeight: 220,
              flexibleSpace: FlexibleSpaceBar(
                titlePadding: const EdgeInsets.only(left: Gap.xxl, bottom: Gap.xxl),
                title: const Text('Hồ sơ cá nhân'),
                background: Container(
                  color: Theme.of(context).colorScheme.primaryContainer,
                  child: Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        AppAvatar(
                          name: user?.displayNameOrEmail ?? 'Người dùng',
                          size: AppAvatarSize.xl,
                        ),
                        const SizedBox(height: Gap.md),
                        Text(
                          user?.displayNameOrEmail ?? 'Chưa đăng nhập',
                          style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                        const SizedBox(height: Gap.xs),
                        Text(
                          user?.email ?? '',
                          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                            color: isDark ? AppColors.darkTextSecondary : AppColors.textSecondary,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
            SliverPadding(
              padding: const EdgeInsets.all(Gap.xxl),
              sliver: SliverList(
                delegate: SliverChildListDelegate([
                  const SizedBox(height: Gap.lg),
                  // Chỉ cho phép sửa tên khi bấm "Sửa hồ sơ"
                  if (!_isEditing) ...[
                    AppCardProfileField(
                      label: 'Tên hiển thị',
                      value: user?.displayName ?? '(chưa có)',
                      onTap: () => setState(() {
                        _nameController.text = user?.displayName ?? '';
                        _isEditing = true;
                      }),
                    ),
                    const SizedBox(height: Gap.lg),
                    AppCardProfileField(
                      label: 'Email',
                      value: user?.email ?? '',
                      onTap: null,
                    ),
                    const SizedBox(height: Gap.xxl),
                    AppButton(
                      label: 'Sửa hồ sơ',
                      onPressed: () => setState(() {
                        _nameController.text = user?.displayName ?? '';
                        _isEditing = true;
                      }),
                      variant: AppButtonVariant.outline,
                      fullWidth: true,
                    ),
                  ] else ...[
                    AppInput(
                      controller: _nameController,
                      label: 'Tên hiển thị',
                      prefixIcon: Icons.badge_outlined,
                      autofocus: true,
                    ),
                    const SizedBox(height: Gap.lg),
                    Row(
                      children: [
                        Expanded(
                          child: AppButton(
                            label: 'Lưu',
                            onPressed: _handleUpdate,
                            size: AppButtonSize.lg,
                            isLoading: auth.isLoading,
                          ),
                        ),
                        const SizedBox(width: Gap.sm),
                        Expanded(
                          child: AppButton(
                            label: 'Hủy',
                            onPressed: () => setState(() => _isEditing = false),
                            variant: AppButtonVariant.ghost,
                          ),
                        ),
                      ],
                    ),
                  ],
                  const SizedBox(height: Gap.xxxl),
                  AppButton(
                    label: 'Đăng xuất',
                    onPressed: _handleLogout,
                    variant: AppButtonVariant.danger,
                    fullWidth: true,
                    size: AppButtonSize.lg,
                    isLoading: auth.isLoading,
                  ),
                ]),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Trường thông tin trong hồ sơ (chỉ đọc, bấm để sửa nếu có onTap).
class AppCardProfileField extends StatelessWidget {
  const AppCardProfileField({
    super.key,
    required this.label,
    required this.value,
    this.onTap,
  });

  final String label;
  final String value;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(Rad.lg),
      child: Padding(
        padding: const EdgeInsets.all(Gap.xl),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              label,
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: isDark ? AppColors.darkTextSecondary : AppColors.textSecondary,
              ),
            ),
            const SizedBox(height: Gap.xs),
            Text(
              value,
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: isDark ? AppColors.darkTextPrimary : AppColors.textPrimary,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
