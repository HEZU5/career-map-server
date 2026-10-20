import 'package:flutter/material.dart';

import '../../../core/storage/session_store.dart';
import '../../../core/theme/tokens.dart';
import '../../../core/ui/components/app_button.dart';
import '../../../core/ui/components/app_input.dart';
import 'auth_state_provider.dart';

/// Màn hình đăng ký tài khoản mới.
class RegisterPage extends StatefulWidget {
  const RegisterPage({super.key, required this.session});

  final SessionStore session;

  @override
  State<RegisterPage> createState() => _RegisterPageState();
}

class _RegisterPageState extends State<RegisterPage> {
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();
  final _displayNameController = TextEditingController();
  final _formKey = GlobalKey<FormState>();

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    _confirmPasswordController.dispose();
    _displayNameController.dispose();
    super.dispose();
  }

  Future<void> _handleRegister() async {
    if (!_formKey.currentState!.validate()) return;
    final auth = AuthStateProvider.of(context, listen: false);
    await auth.register(
      email: _emailController.text.trim(),
      password: _passwordController.text,
      displayName: _displayNameController.text.trim(),
    );
    if (mounted && auth.error == null && auth.isAuthenticated) {
      Navigator.of(context).pushReplacementNamed('/');
    }
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(Gap.xxl),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: Breakpoints.contentMaxWidth),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const SizedBox(height: Gap.xxl),
                  Icon(Icons.person_add_outlined, size: 80, color: Theme.of(context).colorScheme.primary),
                  const SizedBox(height: Gap.lg),
                  Text(
                    'Đăng ký',
                    style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: Gap.sm),
                  Text(
                    'Tạo tài khoản để chơi trên nhiều thiết bị',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: Theme.of(context).brightness == Brightness.dark
                          ? AppColors.darkTextSecondary
                          : AppColors.textSecondary,
                    ),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: Gap.xxxl),
                  Form(
                    key: _formKey,
                    child: Column(
                      children: [
                        AppInput(
                          controller: _displayNameController,
                          label: 'Tên hiển thị',
                          hint: 'Ví dụ: Nguyễn Văn A',
                          prefixIcon: Icons.badge_outlined,
                          textCapitalization: TextCapitalization.words,
                          validator: (v) {
                            if (v == null || v.isEmpty) return 'Vui lòng nhập tên hiển thị';
                            if (v.length < 2) return 'Tên quá ngắn';
                            return null;
                          },
                        ),
                        const SizedBox(height: Gap.lg),
                        AppInput(
                          controller: _emailController,
                          label: 'Email',
                          hint: 'name@example.com',
                          prefixIcon: Icons.email_outlined,
                          keyboardType: TextInputType.emailAddress,
                          validator: (v) {
                            if (v == null || v.isEmpty) return 'Vui lòng nhập email';
                            if (!RegExp(r'^[\w-\.]+@([\w-]+\.)+[\w-]{2,4}$').hasMatch(v)) {
                              return 'Email không hợp lệ';
                            }
                            return null;
                          },
                        ),
                        const SizedBox(height: Gap.lg),
                        AppInput(
                          controller: _passwordController,
                          label: 'Mật khẩu',
                          hint: '••••••••',
                          prefixIcon: Icons.lock_outline,
                          obscureText: true,
                          validator: (v) {
                            if (v == null || v.isEmpty) return 'Vui lòng nhập mật khẩu';
                            if (v.length < 6) return 'Mật khẩu tối thiểu 6 ký tự';
                            return null;
                          },
                        ),
                        const SizedBox(height: Gap.lg),
                        AppInput(
                          controller: _confirmPasswordController,
                          label: 'Nhập lại mật khẩu',
                          hint: '••••••••',
                          prefixIcon: Icons.lock_outline,
                          obscureText: true,
                          validator: (v) {
                            if (v == null || v.isEmpty) return 'Vui lòng nhập lại mật khẩu';
                            if (v != _passwordController.text) return 'Mật khẩu không khớp';
                            return null;
                          },
                        ),
                        const SizedBox(height: Gap.xl),
                        AppButton(
                          label: 'Đăng ký',
                          onPressed: _handleRegister,
                          size: AppButtonSize.lg,
                          fullWidth: true,
                          isLoading: AuthStateProvider.of(context, listen: false).isLoading,
                        ),
                        const SizedBox(height: Gap.md),
                        TextButton(
                          onPressed: () => Navigator.of(context).pushNamed('/dang-nhap'),
                          child: const Text('Đã có tài khoản? Đăng nhập'),
                        ),
                        if (AuthStateProvider.of(context, listen: false).error != null) ...[
                          const SizedBox(height: Gap.lg),
                          Container(
                            padding: const EdgeInsets.all(Gap.md),
                            decoration: BoxDecoration(
                              color: AppColors.error.withValues(alpha: 0.1),
                              borderRadius: BorderRadius.circular(Rad.md),
                              border: Border.all(color: AppColors.error.withValues(alpha: 0.3)),
                            ),
                            child: Row(
                              children: [
                                Icon(Icons.error_outline, color: AppColors.error, size: 20),
                                const SizedBox(width: Gap.sm),
                                Expanded(
                                  child: Text(
                                    AuthStateProvider.of(context, listen: false).error!,
                                    style: TextStyle(
                                      color: isDark ? AppColors.darkTextPrimary : AppColors.textPrimary,
                                      fontSize: 13,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
