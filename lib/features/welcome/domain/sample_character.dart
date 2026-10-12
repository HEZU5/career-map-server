import 'package:flutter/material.dart';

/// Nhân vật để người chơi nhập vai — hiện có 4 lựa chọn (không phân
/// nam/nữ), mỗi nhân vật là một ảnh riêng. Tên dùng để resolve ảnh
/// theo người chơi (`byName`) nên phải khác nhau.
class CareerCharacter {
  const CareerCharacter({
    required this.name,
    required this.profession,
    required this.color,
    required this.icon,
    required this.assetPath,
  });

  /// Tên hiển thị trên server — được dùng làm khoá để `byName` resolve
  /// đúng ảnh nhân vật của từng người chơi.
  final String name;

  /// Nhãn phụ hiện bên dưới tên khi chọn nhân vật.
  final String profession;

  /// Màu nền dự phòng khi tải ảnh (avatar chưa có ảnh).
  final Color color;

  /// Biểu tượng dự phòng khi chưa dùng ảnh.
  final IconData icon;

  /// Đường dẫn asset tới ảnh nhân vật (đã đăng ký trong pubspec).
  final String assetPath;

  /// Nhân vật theo tên. Chấp nhận null vì `characterName` có thể chưa chọn —
  /// khi đó không có nhân vật nào khớp, caller tự dùng nhân vật dự phòng.
  static CareerCharacter? byName(String? name) {
    if (name == null) return null;
    for (final c in careerCharacters) {
      if (c.name == name) return c;
    }
    return null;
  }

  static Color fallbackColor() => const Color(0xFF8E44AD);
  static IconData fallbackIcon() => Icons.person;
}

/// 4 nhân vật, theo thứ tự ảnh `Asset 3/5/6/7.png`. Nhân vật không có giới tính.
/// Tên ngắn (MIKA…) là khoá `byName`; câu bám theo là nhãn phụ.
const List<CareerCharacter> careerCharacters = [
  CareerCharacter(
    name: 'MIKA',
    profession: 'The Explorer',
    color: Color(0xFF8E44AD),
    icon: Icons.person,
    assetPath: 'assets/Asset 3.png',
  ),
  CareerCharacter(
    name: 'PIP',
    profession: 'The Wonderer',
    color: Color(0xFF5DADE2),
    icon: Icons.face,
    assetPath: 'assets/Asset 5.png',
  ),
  CareerCharacter(
    name: 'BUBU',
    profession: 'The Challenger',
    color: Color(0xFFF5A623),
    icon: Icons.emoji_people,
    assetPath: 'assets/Asset 6.png',
  ),
  CareerCharacter(
    name: 'KIKI',
    profession: 'The Idea Maker',
    color: Color(0xFF58D68D),
    icon: Icons.face_retouching_natural,
    assetPath: 'assets/Asset 7.png',
  ),
];
