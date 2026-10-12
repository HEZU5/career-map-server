import 'package:flutter/material.dart';

import '../../core/theme/tokens.dart';
import '../../core/ui/components/app_button.dart';
import 'domain/sample_character.dart';

/// Màn hình chọn nhân vật — 4 nhân vật (không phân giới tính), mỗi nhân
/// vật là một ảnh riêng. Trả về nhân vật đã chọn qua
/// `Navigator.pop(context, CareerCharacter)`, hoặc `null` nếu hủy.
class CharacterSelectPage extends StatefulWidget {
  const CharacterSelectPage({super.key});

  @override
  State<CharacterSelectPage> createState() => _CharacterSelectPageState();
}

class _CharacterSelectPageState extends State<CharacterSelectPage> {
  CareerCharacter? _selected;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isDark = theme.brightness == Brightness.dark;

    return Scaffold(
      appBar: AppBar(title: const Text('Chọn nhân vật')),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(Gap.lg),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 560),
            child: Column(
              children: [
                Text(
                  'Chọn 1 trong 4 nhân vật để nhập vai',
                  style: TextStyle(
                    fontSize: 15,
                    color: isDark ? AppColors.darkTextSecondary : AppColors.textSecondary,
                  ),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: Gap.xl),
                GridView.count(
                  crossAxisCount: 2,
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  mainAxisSpacing: Gap.md,
                  crossAxisSpacing: Gap.md,
                  childAspectRatio: 0.82,
                  children: [
                    for (final c in careerCharacters)
                      _CharacterCard(
                        character: c,
                        selected: c == _selected,
                        onTap: () => setState(() => _selected = c),
                      ),
                  ],
                ),
                const SizedBox(height: Gap.xxl),
                AppButton(
                  label: 'Xác nhận nhân vật',
                  onPressed: _selected == null ? null : () => Navigator.pop(context, _selected),
                  icon: Icons.check_circle,
                  size: AppButtonSize.lg,
                  fullWidth: true,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _CharacterCard extends StatelessWidget {
  const _CharacterCard({required this.character, required this.selected, required this.onTap});

  final CareerCharacter character;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return InkWell(
      borderRadius: BorderRadius.circular(Rad.xl),
      onTap: onTap,
      child: Stack(
        children: [
          AnimatedContainer(
            duration: Motion.fast,
            width: double.infinity,
            decoration: BoxDecoration(
              color: isDark ? AppColors.darkSurface : AppColors.surface,
              borderRadius: BorderRadius.circular(Rad.xl),
              border: Border.all(
                color: selected ? AppColors.primaryButton : (isDark ? AppColors.darkBorder : AppColors.border),
                width: selected ? 3 : 1,
              ),
              boxShadow: selected
                  ? [
                      BoxShadow(
                        color: AppColors.primaryButton.withValues(alpha: 0.3),
                        blurRadius: 12,
                      ),
                    ]
                  : Elev.sm,
            ),
            child: Padding(
              padding: const EdgeInsets.all(Gap.md),
              child: Column(
                children: [
                  Expanded(
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(Rad.lg),
                      child: Image.asset(
                        character.assetPath,
                        fit: BoxFit.contain,
                        width: double.infinity,
                        errorBuilder: (_, _, _) => ColoredBox(
                          color: character.color.withValues(alpha: 0.25),
                          child: Center(
                            child: Icon(character.icon, size: 48, color: character.color),
                          ),
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: Gap.sm),
                  Text(
                    character.name,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w900,
                      color: isDark ? AppColors.darkPrimary : AppColors.primary,
                    ),
                  ),
                  const SizedBox(height: Gap.xs),
                  Text(
                    character.profession,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 11,
                      color: isDark ? AppColors.darkTextSecondary : AppColors.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
          ),
          if (selected)
            Positioned(
              top: Gap.sm,
              right: Gap.sm,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: Gap.sm, vertical: Gap.xs),
                decoration: BoxDecoration(
                  color: AppColors.primaryButton,
                  borderRadius: BorderRadius.circular(Rad.sm),
                ),
                child: const Text(
                  'ĐÃ CHỌN',
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 9,
                    fontWeight: FontWeight.w900,
                    letterSpacing: 0.5,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
