import 'package:flutter/material.dart';

import '../../../core/storage/session_store.dart';
import '../../../core/theme/tokens.dart';
import '../../../core/ui/components/app_button.dart';
import '../../../core/ui/components/app_input.dart';
import 'auth_state_provider.dart';

/// Màn hình đăng nhập bằng email + mật khẩu.
class LoginPage extends StatefulWidget {
  const LoginPage({super.key, required this.session});

  final SessionStore session;

  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  final _formKey = GlobalKey<FormState>();

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _handleLogin() async {
    if (!_formKey.currentState!.validate()) return;
    final auth = AuthStateProvider.of(context, listen: false);
    await auth.login(
      email: _emailController.text.trim(),
      password: _passwordController.text,
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
                  Icon(Icons.person_outline, size: 80, color: Theme.of(context).colorScheme.primary),
                  const SizedBox(height: Gap.lg),
                  Text(
                    'Đăng nhập',
                    style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: Gap.sm),
                  Text(
                    'Chào mừng bạn quay lại Holland Career Explorer',
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
                        const SizedBox(height: Gap.xl),
                        AppButton(
                          label: 'Đăng nhập',
                          onPressed: _handleLogin,
                          size: AppButtonSize.lg,
                          fullWidth: true,
                          isLoading: AuthStateProvider.of(context, listen: false).isLoading,
                        ),
                        const SizedBox(height: Gap.md),
                        TextButton(
                          onPressed: () => Navigator.of(context).pushNamed('/dang-ky'),
                          child: const Text('Chưa có tài khoản? Đăng ký'),
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
