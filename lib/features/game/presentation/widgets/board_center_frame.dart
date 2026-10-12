import 'package:flutter/material.dart';

import '../../../../core/theme/tokens.dart';

/// Khung GIỮA của bàn cờ — vùng artwork "Khám phá bản thân…", nằm trong
/// 8%–92% chiều ngang và 17%–83% chiều cao của bàn (không chạm ô cờ).
///
/// Dùng cho: pill kết quả tung, popup Cơ hội/Thử thách/câu hỏi. Nội dung
/// cuộn được bên trong; các lựa chọn giữ ngang. Trên màn rất nhỏ cho phép
/// bung ra toàn màn hình (nút thu gọn để quay lại).
class BoardCenterFrame extends StatefulWidget {
  const BoardCenterFrame({
    super.key,
    required this.child,
    this.scrollable = false,
    this.padding = const EdgeInsets.all(Gap.xl),
    this.background,
    this.borderRadius = Rad.xl,
    this.decorated = true,
  });

  /// Nội dung hiển thị trong khung.
  final Widget child;

  /// Bọc nội dung trong `SingleChildScrollView` (dùng cho popup dài).
  final bool scrollable;

  final EdgeInsets padding;
  final Color? background;
  final double borderRadius;

  /// `false` → không vẽ nền (pill trong suốt), `true` → card trắng đục.
  final bool decorated;

  @override
  State<BoardCenterFrame> createState() => _BoardCenterFrameState();
}

class _BoardCenterFrameState extends State<BoardCenterFrame> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    // Khung chiếm 8–92% x, 17–83% y của bàn (khớp artwork giữa bàn).
    return LayoutBuilder(
      builder: (context, c) {
        final boardW = c.maxWidth;
        final boardH = c.maxHeight;
        final tooSmall = boardW < 420 || boardH < 300;
        // Đã bung: chiếm gần hết bàn (chỉ chừa 2%) để nội dung không bị bóp.
        final frame = Padding(
          padding: EdgeInsets.symmetric(
            horizontal: boardW * (_expanded ? 0.02 : 0.08),
            vertical: boardH * (_expanded ? 0.02 : 0.17),
          ),
          child: Center(child: _content(context)),
        );
        // Màn quá nhỏ: cho bung toàn màn hình để không bị bóp méo nội dung.
        if (!tooSmall) return frame;
        return Stack(
          children: [
            frame,
            Positioned(
              right: 8,
              bottom: 8,
              child: _tinyButton(
                icon: _expanded ? Icons.close_fullscreen : Icons.open_in_full,
                tooltip: _expanded ? 'Thu gọn' : 'Mở rộng',
                onTap: () => setState(() => _expanded = !_expanded),
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _content(BuildContext context) {
    final body = widget.scrollable ? SingleChildScrollView(child: widget.child) : widget.child;
    if (!widget.decorated) return body;
    return Material(
      color: widget.background ?? Colors.white.withValues(alpha: 0.97),
      borderRadius: BorderRadius.circular(widget.borderRadius),
      elevation: 4,
      shadowColor: Colors.black26,
      child: Padding(padding: widget.padding * (_expanded ? 0.6 : 1), child: body),
    );
  }
}

Widget _tinyButton({required IconData icon, required String tooltip, required VoidCallback onTap}) {
  return Tooltip(
    message: tooltip,
    child: Material(
      color: AppColors.violet,
      shape: const CircleBorder(),
      child: InkWell(
        customBorder: const CircleBorder(),
        onTap: onTap,
        child: SizedBox(width: 44, height: 44, child: Icon(icon, color: Colors.white, size: 18)),
      ),
    ),
  );
}

/// Pill thông báo kết quả tung ở GIỮA khung bàn: "Gia Huy tung được 4" kèm
/// avatar người tung. Giữ nguyên cho tới khi quân đi xong rồi mờ dần (điều
/// khiển ẩn hiện từ [GamePage]).
class RollResultPill extends StatelessWidget {
  const RollResultPill({
    super.key,
    required this.playerName,
    required this.value,
    this.avatarAsset,
    this.avatarColor,
    this.fade = 1,
  });

  final String playerName;
  final int value;
  final String? avatarAsset;
  final Color? avatarColor;

  /// 0 → 1: độ mờ (dùng để fade out sau khi quân đi xong).
  final double fade;

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: Center(
        child: Opacity(
          opacity: fade.clamp(0.0, 1.0),
          child: Material(
            color: Colors.white.withValues(alpha: 0.95),
            borderRadius: BorderRadius.circular(999),
            elevation: 4,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  CircleAvatar(
                    radius: 16,
                    backgroundColor: avatarColor ?? AppColors.violet,
                    backgroundImage: avatarAsset == null ? null : AssetImage(avatarAsset!),
                    child: avatarAsset != null
                        ? null
                        : Text(
                            playerName.isNotEmpty ? playerName[0] : '?',
                            style: const TextStyle(
                              color: Colors.white,
                              fontWeight: FontWeight.w900,
                              fontSize: 14,
                            ),
                          ),
                  ),
                  const SizedBox(width: 10),
                  Text(
                    '$playerName tung được $value',
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w900,
                      color: AppColors.violet,
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
