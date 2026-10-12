import 'package:flutter/material.dart';

/// Nhóm Holland RIASEC — thay cho `Map<String, GroupMeta>` + string `'R'`
/// rời rạc trong code cũ (dễ gõ sai, không được analyzer kiểm tra).
enum Riasec {
  realistic(
    'R',
    'Realistic',
    'Thực hành, thao tác và làm việc với công cụ.',
    Icons.settings,
    Color(0xFFF5A623),
  ),
  investigative(
    'I',
    'Investigative',
    'Tìm hiểu, phân tích và nghiên cứu.',
    Icons.query_stats,
    Color(0xFFE74C3C),
  ),
  artistic(
    'A',
    'Artistic',
    'Sáng tạo, tưởng tượng và thể hiện ý tưởng.',
    Icons.brush,
    Color(0xFFF4D03F),
  ),
  social(
    'S',
    'Social',
    'Kết nối, hỗ trợ và làm việc với con người.',
    Icons.handshake,
    Color(0xFF9B59B6),
  ),
  enterprising(
    'E',
    'Enterprising',
    'Lãnh đạo, thuyết phục và tạo ảnh hưởng.',
    Icons.flag,
    Color(0xFF5DADE2),
  ),
  conventional(
    'C',
    'Conventional',
    'Tổ chức, quản lý và làm việc theo quy trình.',
    Icons.extension,
    Color(0xFF58D68D),
  );

  const Riasec(this.code, this.displayName, this.description, this.icon, this.color);

  final String code;
  final String displayName;
  final String description;
  final IconData icon;
  final Color color;

  static Riasec? fromCode(String code) {
    for (final value in Riasec.values) {
      if (value.code == code) return value;
    }
    return null;
  }
}
