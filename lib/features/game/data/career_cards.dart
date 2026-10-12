import 'package:flutter/foundation.dart';

import '../domain/riasec.dart';

/// Một đáp án của lá Holland: cộng [points] vào nhóm [category].
/// Theo quy tắc chấm điểm trong tài liệu:
/// 1 đáp án thể hiện rõ nhất nhóm của lá → +2; 2 đáp án còn lại → +1
/// cho hai nhóm liên quan.
@immutable
class HollandOption {
  const HollandOption(this.label, this.text, this.category, this.points);

  /// 'A' | 'B' | 'C'
  final String label;
  final String text;
  final Riasec category;
  final int points;
}

/// Lá bài Holland (R/I/A/S/E/C). Mỗi nhóm có 12 lá, mỗi lá 3 đáp án.
@immutable
class HollandCard {
  const HollandCard({
    required this.id,
    required this.group,
    required this.question,
    required this.options,
  });

  /// Mã lá, ví dụ 'R01'.
  final String id;

  /// Nhóm Holland của lá (nhóm mà đáp án +2 hướng tới).
  final Riasec group;

  final String question;

  final List<HollandOption> options;

  HollandOption? optionOf(String label) {
    for (final option in options) {
      if (option.label == label) return option;
    }
    return null;
  }
}

/// Lá Cơ hội: phát hiện vận may, nhận [winPoints] điểm chiến thắng.
@immutable
class OpportunityCard {
  const OpportunityCard({
    required this.id,
    required this.title,
    required this.effect,
    this.winPoints = 2,
  });

  final String id;
  final String title;
  final String effect;

  /// Điểm chiến thắng (tài liệu ghi "+N điểm chiến thắng"). Với các lá
  /// có hiệu ứng di chuyển / chơi thêm lượt, quy nhẹ về +2 để không phải
  /// triển khai di chuyển phức tạp — văn bản hiệu ứng vẫn đúng theo tài liệu.
  final int winPoints;
}

/// Lá Thử thách: hoàn thành nhiệm vụ để nhận điểm chiến thắng.
@immutable
class ChallengeCard {
  const ChallengeCard({
    required this.id,
    required this.title,
    required this.task,
    this.winPoints = 2,
  });

  final String id;
  final String title;
  final String task;
  final int winPoints;
}

/// 72 lá Holland (R/I/A/S/E/C × 12) sao chép đúng nội dung tài liệu
/// "Career Map (2).docx", kèm quy tắc chấm +2/+1 cho từng đáp án.
const List<HollandCard> kHollandCards = [
  // ── R Realistic ────────────────────────────────────────────────
  HollandCard(
    id: 'R01',
    group: Riasec.realistic,
    question: 'Trường tổ chức Ngày hội trải nghiệm nghề nghiệp. Bạn sẽ chọn hoạt động nào?',
    options: [
      HollandOption('A', 'Lắp ráp mô hình kỹ thuật', Riasec.realistic, 2),
      HollandOption('B', 'Quan sát cách mô hình hoạt động trước khi thử', Riasec.investigative, 1),
      HollandOption('C', 'Hỏi người hướng dẫn để cùng thực hiện', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'R02',
    group: Riasec.realistic,
    question: 'Quạt điện trong lớp bị lỏng chân đế. Bạn sẽ làm gì?',
    options: [
      HollandOption('A', 'Kiểm tra và tìm cách sửa', Riasec.realistic, 2),
      HollandOption('B', 'Tìm hiểu nguyên nhân quạt bị hỏng', Riasec.investigative, 1),
      HollandOption('C', 'Báo giáo viên hoặc người phụ trách', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'R03',
    group: Riasec.realistic,
    question: 'Trong buổi cắm trại, nhóm cần dựng lều. Bạn muốn nhận nhiệm vụ nào?',
    options: [
      HollandOption('A', 'Dựng khung và cố định lều', Riasec.realistic, 2),
      HollandOption('B', 'Đọc hướng dẫn trước khi dựng', Riasec.investigative, 1),
      HollandOption('C', 'Phân công mọi người hỗ trợ nhau', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'R04',
    group: Riasec.realistic,
    question: 'Đến giờ thực hành, bạn thích nhất việc gì?',
    options: [
      HollandOption('A', 'Trực tiếp thao tác với dụng cụ', Riasec.realistic, 2),
      HollandOption('B', 'Quan sát quy trình rồi mới làm', Riasec.investigative, 1),
      HollandOption('C', 'Hướng dẫn bạn khác cùng thực hiện', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'R05',
    group: Riasec.realistic,
    question: 'Lớp tổ chức trồng cây xanh. Bạn sẽ:',
    options: [
      HollandOption('A', 'Đào đất và trồng cây', Riasec.realistic, 2),
      HollandOption('B', 'Tìm hiểu loại đất phù hợp', Riasec.investigative, 1),
      HollandOption('C', 'Hướng dẫn các bạn thực hiện đúng cách', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'R06',
    group: Riasec.realistic,
    question: 'Cuộc thi yêu cầu làm một sản phẩm tái chế. Bạn muốn:',
    options: [
      HollandOption('A', 'Trực tiếp cắt, ghép và lắp ráp', Riasec.realistic, 2),
      HollandOption('B', 'Nghiên cứu cách làm hiệu quả nhất', Riasec.investigative, 1),
      HollandOption('C', 'Hỗ trợ các thành viên khi cần', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'R07',
    group: Riasec.realistic,
    question: 'Bạn nhìn thấy xe đạp bị tuột xích. Bạn sẽ:',
    options: [
      HollandOption('A', 'Tự lắp lại xích', Riasec.realistic, 2),
      HollandOption('B', 'Quan sát xem vì sao xích bị tuột', Riasec.investigative, 1),
      HollandOption('C', 'Nhờ người có kinh nghiệm hỗ trợ', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'R08',
    group: Riasec.realistic,
    question: 'Nhóm được giao làm mô hình cho môn học. Bạn thích:',
    options: [
      HollandOption('A', 'Cắt, dán và lắp ráp mô hình', Riasec.realistic, 2),
      HollandOption('B', 'Thiết kế cấu trúc trước khi làm', Riasec.investigative, 1),
      HollandOption('C', 'Điều phối các thành viên', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'R09',
    group: Riasec.realistic,
    question: 'Nếu được chọn một hoạt động ngoại khóa, bạn sẽ chọn:',
    options: [
      HollandOption('A', 'Leo núi hoặc vượt chướng ngại vật', Riasec.realistic, 2),
      HollandOption('B', 'Khám phá thiên nhiên và ghi chép', Riasec.investigative, 1),
      HollandOption('C', 'Tổ chức trò chơi cho cả nhóm', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'R10',
    group: Riasec.realistic,
    question: 'Trong cuộc thi Robot, bạn muốn đảm nhận phần nào?',
    options: [
      HollandOption('A', 'Lắp ráp robot', Riasec.realistic, 2),
      HollandOption('B', 'Phân tích nguyên lý hoạt động', Riasec.investigative, 1),
      HollandOption('C', 'Hỗ trợ nhóm phối hợp làm việc', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'R11',
    group: Riasec.realistic,
    question: 'Một chiếc bàn học bị lung lay. Bạn sẽ:',
    options: [
      HollandOption('A', 'Kiểm tra và siết lại các ốc vít', Riasec.realistic, 2),
      HollandOption('B', 'Xem nguyên nhân khiến bàn bị hỏng', Riasec.investigative, 1),
      HollandOption('C', 'Báo nhân viên bảo trì', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'R12',
    group: Riasec.realistic,
    question: 'Cuối tuần bạn được tham gia làm vườn. Bạn thích:',
    options: [
      HollandOption('A', 'Trồng cây, chăm sóc và tưới nước', Riasec.realistic, 2),
      HollandOption('B', 'Tìm hiểu đặc điểm từng loại cây', Riasec.investigative, 1),
      HollandOption('C', 'Hướng dẫn mọi người cùng thực hiện', Riasec.social, 1),
    ],
  ),

  // ── I Investigative ────────────────────────────────────────────
  HollandCard(
    id: 'I01',
    group: Riasec.investigative,
    question: 'Bạn gặp một bài toán rất khó. Bạn sẽ:',
    options: [
      HollandOption('A', 'Phân tích từng dữ kiện để tìm lời giải', Riasec.investigative, 2),
      HollandOption('B', 'Thử nhiều cách giải khác nhau', Riasec.realistic, 1),
      HollandOption('C', 'Hỏi bạn bè để trao đổi ý tưởng', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'I02',
    group: Riasec.investigative,
    question: 'Trong giờ thí nghiệm, bạn thích nhất việc gì?',
    options: [
      HollandOption('A', 'Quan sát kết quả và giải thích nguyên nhân', Riasec.investigative, 2),
      HollandOption('B', 'Tự tay thực hiện thí nghiệm', Riasec.realistic, 1),
      HollandOption('C', 'Hướng dẫn bạn cùng nhóm làm đúng quy trình', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'I03',
    group: Riasec.investigative,
    question: 'Bạn phát hiện cây trong lớp bị héo. Bạn sẽ:',
    options: [
      HollandOption('A', 'Tìm hiểu nguyên nhân khiến cây bị héo', Riasec.investigative, 2),
      HollandOption('B', 'Tưới nước và chăm sóc ngay', Riasec.realistic, 1),
      HollandOption('C', 'Trao đổi với mọi người để tìm cách giải quyết', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'I04',
    group: Riasec.investigative,
    question: 'Khi đọc một thông tin mới trên mạng, bạn thường:',
    options: [
      HollandOption('A', 'Kiểm tra nguồn và xác minh thông tin', Riasec.investigative, 2),
      HollandOption('B', 'Thử áp dụng nếu thấy hữu ích', Riasec.realistic, 1),
      HollandOption('C', 'Chia sẻ để hỏi ý kiến người khác', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'I05',
    group: Riasec.investigative,
    question: 'Nếu tham gia cuộc thi khoa học, bạn muốn:',
    options: [
      HollandOption('A', 'Nghiên cứu và tìm lời giải cho vấn đề', Riasec.investigative, 2),
      HollandOption('B', 'Chế tạo mô hình minh họa', Riasec.realistic, 1),
      HollandOption('C', 'Trình bày kết quả trước hội đồng', Riasec.enterprising, 1),
    ],
  ),
  HollandCard(
    id: 'I06',
    group: Riasec.investigative,
    question: 'Bạn thấy một hiện tượng thiên nhiên thú vị. Bạn sẽ:',
    options: [
      HollandOption('A', 'Tìm hiểu vì sao hiện tượng đó xảy ra', Riasec.investigative, 2),
      HollandOption('B', 'Ghi hình và thử quan sát thực tế', Riasec.realistic, 1),
      HollandOption('C', 'Kể cho bạn bè nghe để cùng thảo luận', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'I07',
    group: Riasec.investigative,
    question: 'Bạn thích đọc loại sách nào nhất?',
    options: [
      HollandOption('A', 'Sách khoa học, khám phá, kiến thức mới', Riasec.investigative, 2),
      HollandOption('B', 'Sách hướng dẫn thực hành', Riasec.realistic, 1),
      HollandOption('C', 'Sách về kỹ năng giao tiếp hoặc tâm lý', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'I08',
    group: Riasec.investigative,
    question: 'Trong một trò chơi giải mật mã, bạn sẽ:',
    options: [
      HollandOption('A', 'Phân tích các manh mối để tìm đáp án', Riasec.investigative, 2),
      HollandOption('B', 'Thử từng cách để kiểm chứng', Riasec.realistic, 1),
      HollandOption('C', 'Hợp tác với mọi người để cùng giải', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'I09',
    group: Riasec.investigative,
    question: 'Nhóm được giao thực hiện một dự án nghiên cứu. Bạn muốn đảm nhận:',
    options: [
      HollandOption('A', 'Thu thập và phân tích dữ liệu', Riasec.investigative, 2),
      HollandOption('B', 'Chuẩn bị dụng cụ và thực hiện khảo sát', Riasec.realistic, 1),
      HollandOption('C', 'Điều phối công việc của cả nhóm', Riasec.enterprising, 1),
    ],
  ),
  HollandCard(
    id: 'I10',
    group: Riasec.investigative,
    question: 'Bạn cài một phần mềm mới nhưng chưa biết sử dụng. Bạn sẽ:',
    options: [
      HollandOption('A', 'Đọc hướng dẫn và khám phá từng chức năng', Riasec.investigative, 2),
      HollandOption('B', 'Thử từng tính năng để học cách dùng', Riasec.realistic, 1),
      HollandOption('C', 'Nhờ người đã biết hướng dẫn', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'I11',
    group: Riasec.investigative,
    question: 'Bạn được giao giải một câu đố logic. Bạn sẽ:',
    options: [
      HollandOption('A', 'Phân tích từng dữ kiện trước khi trả lời', Riasec.investigative, 2),
      HollandOption('B', 'Thử nhiều giả thuyết khác nhau', Riasec.realistic, 1),
      HollandOption('C', 'Thảo luận với nhóm để tìm đáp án', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'I12',
    group: Riasec.investigative,
    question: 'Khi muốn tìm hiểu một chủ đề mới, bạn thường:',
    options: [
      HollandOption('A', 'Đọc tài liệu và tự nghiên cứu trước', Riasec.investigative, 2),
      HollandOption('B', 'Trải nghiệm thực tế để hiểu hơn', Riasec.realistic, 1),
      HollandOption('C', 'Tham gia câu lạc bộ hoặc nhóm cùng sở thích', Riasec.social, 1),
    ],
  ),

  // ── A Artistic ─────────────────────────────────────────────────
  HollandCard(
    id: 'A01',
    group: Riasec.artistic,
    question: 'Trường sắp tổ chức lễ hội. Bạn muốn đảm nhận công việc nào?',
    options: [
      HollandOption('A', 'Thiết kế poster và trang trí sân khấu', Riasec.artistic, 2),
      HollandOption('B', 'Lập kế hoạch chương trình', Riasec.enterprising, 1),
      HollandOption('C', 'Chuẩn bị dụng cụ theo danh sách', Riasec.conventional, 1),
    ],
  ),
  HollandCard(
    id: 'A02',
    group: Riasec.artistic,
    question: 'Nếu tham gia cuộc thi vẽ tranh, bạn sẽ:',
    options: [
      HollandOption('A', 'Vẽ theo ý tưởng sáng tạo của riêng mình', Riasec.artistic, 2),
      HollandOption('B', 'Tìm hiểu chủ đề thật kỹ trước khi vẽ', Riasec.investigative, 1),
      HollandOption('C', 'Trình bày ý tưởng với ban giám khảo', Riasec.enterprising, 1),
    ],
  ),
  HollandCard(
    id: 'A03',
    group: Riasec.artistic,
    question: 'Nhóm được giao làm một video ngắn. Bạn muốn:',
    options: [
      HollandOption('A', 'Viết kịch bản và xây dựng nội dung', Riasec.artistic, 2),
      HollandOption('B', 'Sắp xếp công việc cho cả nhóm', Riasec.enterprising, 1),
      HollandOption('C', 'Quay và chỉnh sửa theo hướng dẫn', Riasec.realistic, 1),
    ],
  ),
  HollandCard(
    id: 'A04',
    group: Riasec.artistic,
    question: 'Lớp cần trang trí bảng tin. Bạn sẽ:',
    options: [
      HollandOption('A', 'Thiết kế bố cục và màu sắc', Riasec.artistic, 2),
      HollandOption('B', 'Cắt dán và lắp đặt', Riasec.realistic, 1),
      HollandOption('C', 'Phân công công việc cho mọi người', Riasec.enterprising, 1),
    ],
  ),
  HollandCard(
    id: 'A05',
    group: Riasec.artistic,
    question: 'Bạn thích cách thể hiện nào nhất?',
    options: [
      HollandOption('A', 'Kể chuyện bằng phong cách sáng tạo', Riasec.artistic, 2),
      HollandOption('B', 'Nghiên cứu nội dung trước khi kể', Riasec.investigative, 1),
      HollandOption('C', 'Truyền cảm hứng cho khán giả', Riasec.enterprising, 1),
    ],
  ),
  HollandCard(
    id: 'A06',
    group: Riasec.artistic,
    question: 'Nếu được tạo một sản phẩm mới, bạn sẽ ưu tiên:',
    options: [
      HollandOption('A', 'Thiết kế độc đáo và đẹp mắt', Riasec.artistic, 2),
      HollandOption('B', 'Tính tiện dụng của sản phẩm', Riasec.realistic, 1),
      HollandOption('C', 'Nhu cầu của người sử dụng', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'A07',
    group: Riasec.artistic,
    question: 'Bạn muốn tham gia câu lạc bộ nào nhất?',
    options: [
      HollandOption('A', 'Mỹ thuật hoặc Âm nhạc', Riasec.artistic, 2),
      HollandOption('B', 'Khoa học', Riasec.investigative, 1),
      HollandOption('C', 'Kinh doanh khởi nghiệp', Riasec.enterprising, 1),
    ],
  ),
  HollandCard(
    id: 'A08',
    group: Riasec.artistic,
    question: 'Bạn sẽ nhận nhiệm vụ nào?',
    options: [
      HollandOption('A', 'Thiết kế trang bìa', Riasec.artistic, 2),
      HollandOption('B', 'Thu thập thông tin cho nội dung', Riasec.investigative, 1),
      HollandOption('C', 'Sắp xếp nhóm hoàn thành đúng tiến độ', Riasec.conventional, 1),
    ],
  ),
  HollandCard(
    id: 'A09',
    group: Riasec.artistic,
    question: 'Nhóm cần một khẩu hiệu cho sự kiện. Bạn sẽ:',
    options: [
      HollandOption('A', 'Nghĩ ra một câu khẩu hiệu mới và ấn tượng', Riasec.artistic, 2),
      HollandOption('B', 'Phân tích thông điệp muốn truyền tải', Riasec.investigative, 1),
      HollandOption('C', 'Thuyết trình ý tưởng với mọi người', Riasec.enterprising, 1),
    ],
  ),
  HollandCard(
    id: 'A10',
    group: Riasec.artistic,
    question: 'Nếu được trang trí một góc học tập, bạn sẽ:',
    options: [
      HollandOption('A', 'Chọn màu sắc và phong cách theo ý tưởng riêng', Riasec.artistic, 2),
      HollandOption('B', 'Bố trí sao cho thuận tiện sử dụng', Riasec.conventional, 1),
      HollandOption('C', 'Tự tay lắp ráp và sắp xếp đồ vật', Riasec.realistic, 1),
    ],
  ),
  HollandCard(
    id: 'A11',
    group: Riasec.artistic,
    question: 'Bạn được giao viết một câu chuyện ngắn. Bạn sẽ:',
    options: [
      HollandOption('A', 'Sáng tạo cốt truyện độc đáo', Riasec.artistic, 2),
      HollandOption('B', 'Tìm hiểu thông tin để câu chuyện hợp lý', Riasec.investigative, 1),
      HollandOption('C', 'Viết theo yêu cầu đã được hướng dẫn', Riasec.conventional, 1),
    ],
  ),
  HollandCard(
    id: 'A12',
    group: Riasec.artistic,
    question: 'Bạn muốn thể hiện tài năng nào nhất?',
    options: [
      HollandOption('A', 'Hát, nhảy, diễn kịch hoặc biểu diễn nghệ thuật', Riasec.artistic, 2),
      HollandOption('B', 'Thuyết trình về một ý tưởng mới', Riasec.enterprising, 1),
      HollandOption('C', 'Biểu diễn mô hình hoặc sản phẩm do mình tự làm', Riasec.realistic, 1),
    ],
  ),

  // ── S Social ───────────────────────────────────────────────────
  HollandCard(
    id: 'S01',
    group: Riasec.social,
    question: 'Lớp có một bạn mới còn bỡ ngỡ. Bạn sẽ:',
    options: [
      HollandOption(
        'A',
        'Giới thiệu các khu vực trong trường và làm quen với bạn',
        Riasec.social,
        2,
      ),
      HollandOption(
        'B',
        'Giao cho bạn một nhiệm vụ trong nhóm để hòa nhập nhanh hơn',
        Riasec.enterprising,
        1,
      ),
      HollandOption('C', 'Đưa cho bạn nội quy và thời khóa biểu', Riasec.conventional, 1),
    ],
  ),
  HollandCard(
    id: 'S02',
    group: Riasec.social,
    question: 'Một thành viên gặp khó khăn khi làm bài. Bạn sẽ:',
    options: [
      HollandOption('A', 'Làm luôn phần việc của bạn ấy', Riasec.realistic, 1),
      HollandOption('B', 'Hướng dẫn bạn ấy cách làm để tự hoàn thành', Riasec.social, 2),
      HollandOption('C', 'Kiểm tra lại đáp án sau khi bạn ấy làm xong', Riasec.investigative, 1),
    ],
  ),
  HollandCard(
    id: 'S03',
    group: Riasec.social,
    question: 'Bạn thích đảm nhận vai trò nào?',
    options: [
      HollandOption('A', 'Chuẩn bị vật dụng', Riasec.conventional, 1),
      HollandOption('B', 'Phụ trách vận chuyển quà', Riasec.realistic, 1),
      HollandOption('C', 'Trò chuyện và hỗ trợ người nhận quà', Riasec.social, 2),
    ],
  ),
  HollandCard(
    id: 'S04',
    group: Riasec.social,
    question: 'Bạn thân đang buồn vì điểm kiểm tra thấp. Bạn sẽ:',
    options: [
      HollandOption('A', 'Lắng nghe và động viên bạn', Riasec.social, 2),
      HollandOption('B', 'Phân tích nguyên nhân khiến bạn bị điểm thấp', Riasec.investigative, 1),
      HollandOption('C', 'Lập kế hoạch ôn tập cho bạn', Riasec.conventional, 1),
    ],
  ),
  HollandCard(
    id: 'S05',
    group: Riasec.social,
    question: 'Nếu tham gia câu lạc bộ, bạn thích nhất việc gì?',
    options: [
      HollandOption('A', 'Quản lý danh sách thành viên', Riasec.conventional, 1),
      HollandOption('B', 'Kết nối và hỗ trợ các thành viên', Riasec.social, 2),
      HollandOption('C', 'Lập kế hoạch phát triển câu lạc bộ', Riasec.enterprising, 1),
    ],
  ),
  HollandCard(
    id: 'S06',
    group: Riasec.social,
    question: 'Trong một buổi sinh hoạt, bạn sẽ:',
    options: [
      HollandOption('A', 'Chuẩn bị đạo cụ', Riasec.realistic, 1),
      HollandOption('B', 'Ghi điểm các đội chơi', Riasec.conventional, 1),
      HollandOption('C', 'Hướng dẫn và cổ vũ mọi người tham gia', Riasec.social, 2),
    ],
  ),
  HollandCard(
    id: 'S07',
    group: Riasec.social,
    question: 'Một bạn chưa hiểu bài đến nhờ bạn giúp. Bạn sẽ:',
    options: [
      HollandOption('A', 'Giải thích từng bước để bạn hiểu', Riasec.social, 2),
      HollandOption('B', 'Đưa đáp án cho bạn chép', Riasec.conventional, 1),
      HollandOption('C', 'Bảo bạn tự tìm hiểu thêm', Riasec.investigative, 1),
    ],
  ),
  HollandCard(
    id: 'S08',
    group: Riasec.social,
    question: 'Hai bạn trong nhóm xảy ra tranh cãi. Bạn sẽ:',
    options: [
      HollandOption('A', 'Để giáo viên giải quyết', Riasec.conventional, 1),
      HollandOption('B', 'Đứng ra hòa giải và lắng nghe cả hai bên', Riasec.social, 2),
      HollandOption('C', 'Phân tích xem ai đúng ai sai', Riasec.investigative, 1),
    ],
  ),
  HollandCard(
    id: 'S09',
    group: Riasec.social,
    question: 'Trường đón học sinh mới tham quan. Bạn muốn:',
    options: [
      HollandOption('A', 'Làm hướng dẫn viên', Riasec.social, 2),
      HollandOption('B', 'Chuẩn bị sơ đồ trường', Riasec.conventional, 1),
      HollandOption('C', 'Thiết kế bảng chỉ dẫn đẹp mắt', Riasec.artistic, 1),
    ],
  ),
  HollandCard(
    id: 'S10',
    group: Riasec.social,
    question: 'Lớp phân công chăm sóc góc xanh. Bạn sẽ:',
    options: [
      HollandOption('A', 'Trực tiếp tưới cây mỗi ngày', Riasec.realistic, 1),
      HollandOption('B', 'Lập lịch chăm sóc', Riasec.conventional, 1),
      HollandOption('C', 'Nhắc nhở và hướng dẫn các bạn cùng thực hiện', Riasec.social, 2),
    ],
  ),
  HollandCard(
    id: 'S11',
    group: Riasec.social,
    question: 'Bạn muốn tham gia hoạt động nào?',
    options: [
      HollandOption('A', 'Phân loại tài liệu', Riasec.conventional, 1),
      HollandOption('B', 'Hỗ trợ trẻ em học tập', Riasec.social, 2),
      HollandOption('C', 'Tìm hiểu nhu cầu của chương trình', Riasec.investigative, 1),
    ],
  ),
  HollandCard(
    id: 'S12',
    group: Riasec.social,
    question: 'Lớp tổ chức một sự kiện. Bạn muốn:',
    options: [
      HollandOption('A', 'Dẫn chương trình và giao lưu với khán giả', Riasec.social, 2),
      HollandOption('B', 'Viết kịch bản chương trình', Riasec.artistic, 1),
      HollandOption('C', 'Điều phối toàn bộ sự kiện', Riasec.enterprising, 1),
    ],
  ),

  // ── E Enterprising ─────────────────────────────────────────────
  HollandCard(
    id: 'E01',
    group: Riasec.enterprising,
    question: 'Lớp cần chọn trưởng nhóm cho một dự án. Bạn sẽ:',
    options: [
      HollandOption('A', 'Đề cử một bạn phù hợp', Riasec.social, 1),
      HollandOption(
        'B',
        'Xung phong làm trưởng nhóm và phân công công việc',
        Riasec.enterprising,
        2,
      ),
      HollandOption('C', 'Ghi chép tiến độ công việc', Riasec.conventional, 1),
    ],
  ),
  HollandCard(
    id: 'E02',
    group: Riasec.enterprising,
    question: 'Lớp tổ chức bán hàng gây quỹ. Bạn muốn làm gì?',
    options: [
      HollandOption('A', 'Chuẩn bị sản phẩm để bán', Riasec.realistic, 1),
      HollandOption('B', 'Ghi chép doanh thu', Riasec.conventional, 1),
      HollandOption('C', 'Giới thiệu và thuyết phục khách mua hàng', Riasec.enterprising, 2),
    ],
  ),
  HollandCard(
    id: 'E03',
    group: Riasec.enterprising,
    question: 'Nhóm cần chọn một ý tưởng để tham gia cuộc thi. Bạn sẽ:',
    options: [
      HollandOption('A', 'Trình bày và bảo vệ ý tưởng của nhóm', Riasec.enterprising, 2),
      HollandOption('B', 'Phân tích ưu và nhược điểm của từng ý tưởng', Riasec.investigative, 1),
      HollandOption('C', 'Thiết kế hình ảnh minh họa', Riasec.artistic, 1),
    ],
  ),
  HollandCard(
    id: 'E04',
    group: Riasec.enterprising,
    question: 'Trường sắp tổ chức hội chợ. Bạn muốn đảm nhận:',
    options: [
      HollandOption('A', 'Trang trí gian hàng', Riasec.artistic, 1),
      HollandOption('B', 'Điều phối và quản lý hoạt động của cả nhóm', Riasec.enterprising, 2),
      HollandOption('C', 'Chuẩn bị danh sách vật dụng', Riasec.conventional, 1),
    ],
  ),
  HollandCard(
    id: 'E05',
    group: Riasec.enterprising,
    question: 'Trong một buổi tranh luận, bạn thường:',
    options: [
      HollandOption('A', 'Lắng nghe ý kiến của mọi người', Riasec.social, 1),
      HollandOption('B', 'Tự tin trình bày quan điểm và bảo vệ lập luận', Riasec.enterprising, 2),
      HollandOption('C', 'Tìm thêm bằng chứng để chứng minh', Riasec.investigative, 1),
    ],
  ),
  HollandCard(
    id: 'E06',
    group: Riasec.enterprising,
    question: 'Nếu tham gia cuộc thi khởi nghiệp, bạn muốn:',
    options: [
      HollandOption('A', 'Thiết kế logo và hình ảnh thương hiệu', Riasec.artistic, 1),
      HollandOption('B', 'Tính toán chi phí và ngân sách', Riasec.conventional, 1),
      HollandOption('C', 'Thuyết trình ý tưởng trước ban giám khảo', Riasec.enterprising, 2),
    ],
  ),
  HollandCard(
    id: 'E07',
    group: Riasec.enterprising,
    question: 'Khi nhóm chưa biết bắt đầu từ đâu, bạn sẽ:',
    options: [
      HollandOption('A', 'Đề xuất kế hoạch và phân chia nhiệm vụ', Riasec.enterprising, 2),
      HollandOption('B', 'Thực hiện phần việc được giao', Riasec.realistic, 1),
      HollandOption('C', 'Thu thập thêm thông tin trước khi làm', Riasec.investigative, 1),
    ],
  ),
  HollandCard(
    id: 'E08',
    group: Riasec.enterprising,
    question: 'Trong cuộc họp nhóm, bạn thường:',
    options: [
      HollandOption('A', 'Ghi biên bản cuộc họp', Riasec.conventional, 1),
      HollandOption('B', 'Đóng góp ý tưởng khi được hỏi', Riasec.artistic, 1),
      HollandOption('C', 'Điều hành cuộc họp và tổng hợp ý kiến', Riasec.enterprising, 2),
    ],
  ),
  HollandCard(
    id: 'E09',
    group: Riasec.enterprising,
    question: 'Để gây quỹ cho lớp, bạn sẽ chọn cách nào?',
    options: [
      HollandOption('A', 'Làm sản phẩm handmade', Riasec.artistic, 1),
      HollandOption('B', 'Lập kế hoạch bán hàng và tìm khách hàng', Riasec.enterprising, 2),
      HollandOption('C', 'Kiểm kê số lượng sản phẩm', Riasec.conventional, 1),
    ],
  ),
  HollandCard(
    id: 'E10',
    group: Riasec.enterprising,
    question: 'Nếu làm Chủ nhiệm câu lạc bộ, bạn sẽ ưu tiên:',
    options: [
      HollandOption('A', 'Chăm sóc các thành viên', Riasec.social, 1),
      HollandOption('B', 'Sắp xếp hồ sơ và tài liệu', Riasec.conventional, 1),
      HollandOption('C', 'Đưa ra mục tiêu và phát triển câu lạc bộ', Riasec.enterprising, 2),
    ],
  ),
  HollandCard(
    id: 'E11',
    group: Riasec.enterprising,
    question: 'Bạn được giao thuyết trình trước toàn trường. Bạn sẽ:',
    options: [
      HollandOption('A', 'Chuẩn bị hình ảnh thật đẹp', Riasec.artistic, 1),
      HollandOption('B', 'Tự tin đứng trước đám đông và trình bày', Riasec.enterprising, 2),
      HollandOption('C', 'Kiểm tra nội dung thật kỹ trước khi nói', Riasec.investigative, 1),
    ],
  ),
  HollandCard(
    id: 'E12',
    group: Riasec.enterprising,
    question: 'Trong một buổi sinh hoạt tập thể, bạn muốn:',
    options: [
      HollandOption('A', 'Chuẩn bị dụng cụ', Riasec.realistic, 1),
      HollandOption('B', 'Làm quản trò và điều khiển hoạt động', Riasec.enterprising, 2),
      HollandOption('C', 'Ghi nhận kết quả của từng đội', Riasec.conventional, 1),
    ],
  ),

  // ── C Conventional ─────────────────────────────────────────────
  HollandCard(
    id: 'C01',
    group: Riasec.conventional,
    question: 'Lớp sắp tổ chức một sự kiện. Bạn muốn nhận nhiệm vụ nào?',
    options: [
      HollandOption('A', 'Trang trí sân khấu', Riasec.artistic, 1),
      HollandOption('B', 'Lập danh sách công việc và kiểm tra tiến độ', Riasec.conventional, 2),
      HollandOption('C', 'Điều hành chương trình', Riasec.enterprising, 1),
    ],
  ),
  HollandCard(
    id: 'C02',
    group: Riasec.conventional,
    question: 'Giáo viên nhờ lớp sắp xếp tài liệu. Bạn sẽ:',
    options: [
      HollandOption('A', 'Phân loại và sắp xếp theo từng mục', Riasec.conventional, 2),
      HollandOption('B', 'Thiết kế bìa tài liệu đẹp hơn', Riasec.artistic, 1),
      HollandOption('C', 'Mang tài liệu đến từng nhóm', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'C03',
    group: Riasec.conventional,
    question: 'Sau hoạt động ngoại khóa, lớp cần kiểm kê dụng cụ. Bạn sẽ:',
    options: [
      HollandOption('A', 'Kiểm tra số lượng theo danh sách', Riasec.conventional, 2),
      HollandOption('B', 'Sửa chữa những dụng cụ bị hỏng', Riasec.realistic, 1),
      HollandOption('C', 'Điều phối mọi người cùng dọn dẹp', Riasec.enterprising, 1),
    ],
  ),
  HollandCard(
    id: 'C04',
    group: Riasec.conventional,
    question: 'Nếu được giao quản lý quỹ lớp, bạn sẽ:',
    options: [
      HollandOption('A', 'Ghi chép thu chi đầy đủ và chính xác', Riasec.conventional, 2),
      HollandOption('B', 'Đề xuất cách gây quỹ mới', Riasec.enterprising, 1),
      HollandOption('C', 'Thiết kế sổ quỹ đẹp mắt', Riasec.artistic, 1),
    ],
  ),
  HollandCard(
    id: 'C05',
    group: Riasec.conventional,
    question: 'Nhóm cần nộp báo cáo cuối kỳ. Bạn muốn làm phần nào?',
    options: [
      HollandOption('A', 'Trình bày báo cáo đúng mẫu và kiểm tra lỗi', Riasec.conventional, 2),
      HollandOption('B', 'Phân tích kết quả khảo sát', Riasec.investigative, 1),
      HollandOption('C', 'Thuyết trình báo cáo', Riasec.enterprising, 1),
    ],
  ),
  HollandCard(
    id: 'C06',
    group: Riasec.conventional,
    question: 'Đầu học kỳ mới, bạn thường:',
    options: [
      HollandOption('A', 'Lập thời khóa biểu và kế hoạch học tập', Riasec.conventional, 2),
      HollandOption('B', 'Tìm hiểu nội dung các môn học', Riasec.investigative, 1),
      HollandOption('C', 'Đăng ký thêm nhiều hoạt động ngoại khóa', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'C07',
    group: Riasec.conventional,
    question: 'Nhóm chuẩn bị đi tham quan. Bạn sẽ:',
    options: [
      HollandOption('A', 'Lập danh sách đồ cần mang', Riasec.conventional, 2),
      HollandOption('B', 'Tìm hiểu địa điểm sẽ đến', Riasec.investigative, 1),
      HollandOption('C', 'Làm trưởng nhóm chuyến đi', Riasec.enterprising, 1),
    ],
  ),
  HollandCard(
    id: 'C08',
    group: Riasec.conventional,
    question: 'Trước khi nộp bài dự thi, bạn sẽ:',
    options: [
      HollandOption('A', 'Kiểm tra lại toàn bộ nội dung và hình thức', Riasec.conventional, 2),
      HollandOption('B', 'Chỉnh sửa thiết kế cho đẹp hơn', Riasec.artistic, 1),
      HollandOption('C', 'Thử nghiệm sản phẩm thêm một lần', Riasec.realistic, 1),
    ],
  ),
  HollandCard(
    id: 'C09',
    group: Riasec.conventional,
    question: 'Bạn được giao quản lý hồ sơ câu lạc bộ. Bạn sẽ:',
    options: [
      HollandOption('A', 'Sắp xếp theo từng loại và cập nhật thường xuyên', Riasec.conventional, 2),
      HollandOption('B', 'Thiết kế mẫu hồ sơ mới', Riasec.artistic, 1),
      HollandOption('C', 'Giới thiệu câu lạc bộ với học sinh mới', Riasec.enterprising, 1),
    ],
  ),
  HollandCard(
    id: 'C10',
    group: Riasec.conventional,
    question: 'Khi nhận một nhiệm vụ, bạn thường:',
    options: [
      HollandOption('A', 'Làm theo từng bước đã lên kế hoạch', Riasec.conventional, 2),
      HollandOption('B', 'Tìm cách làm nhanh hơn', Riasec.realistic, 1),
      HollandOption('C', 'Thảo luận với mọi người trước khi làm', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'C11',
    group: Riasec.conventional,
    question: 'Trong nhóm, bạn thường là người:',
    options: [
      HollandOption(
        'A',
        'Theo dõi tiến độ và nhắc mọi người hoàn thành đúng hạn',
        Riasec.conventional,
        2,
      ),
      HollandOption('B', 'Đưa ra ý tưởng mới', Riasec.artistic, 1),
      HollandOption('C', 'Động viên các thành viên', Riasec.social, 1),
    ],
  ),
  HollandCard(
    id: 'C12',
    group: Riasec.conventional,
    question: 'Một tuần trước kỳ thi, bạn sẽ:',
    options: [
      HollandOption('A', 'Lập kế hoạch ôn tập theo từng ngày', Riasec.conventional, 2),
      HollandOption('B', 'Tìm hiểu thêm tài liệu nâng cao', Riasec.investigative, 1),
      HollandOption('C', 'Học nhóm cùng bạn bè', Riasec.social, 1),
    ],
  ),
];

/// 10 lá Cơ hội trong tài liệu.
const List<OpportunityCard> kOpportunityCards = [
  OpportunityCard(
    id: 'CƠ HỘI 01',
    title: 'May mắn bất ngờ',
    effect: 'Bạn nhận được học bổng khuyến khích! (+2 Điểm Chiến thắng)',
    winPoints: 2,
  ),
  OpportunityCard(
    id: 'CƠ HỘI 02',
    title: 'Gặp người cố vấn',
    effect: 'Bạn được một chuyên gia hướng nghiệp chia sẻ kinh nghiệm. (Được chơi thêm 1 lượt ngay lập tức)',
    winPoints: 2,
  ),
  OpportunityCard(
    id: 'CƠ HỘI 03',
    title: 'Hoạt động ngoại khóa',
    effect: 'Bạn tích cực tham gia hoạt động ngoại khóa và tích lũy nhiều kỹ năng. (Tiến thêm 2 ô)',
    winPoints: 2,
  ),
  OpportunityCard(
    id: 'CƠ HỘI 04',
    title: 'Người bạn đồng hành',
    effect: 'Một người bạn hỗ trợ bạn hoàn thành nhiệm vụ. (Chọn 1 người chơi bất kỳ. Hai người đều nhận +1 Điểm Chiến thắng)',
    winPoints: 1,
  ),
  OpportunityCard(
    id: 'CƠ HỘI 05',
    title: 'Hoàn thành mục tiêu',
    effect: 'Bạn hoàn thành xuất sắc nhiệm vụ được giao. (+3 Điểm Chiến thắng)',
    winPoints: 3,
  ),
  OpportunityCard(
    id: 'CƠ HỘI 06',
    title: 'Quay lại mạnh mẽ',
    effect: 'Bạn có thêm động lực để tiếp tục hành trình. (Được tung xúc xắc thêm 1 lần và di chuyển tiếp)',
    winPoints: 2,
  ),
  OpportunityCard(
    id: 'CƠ HỘI 07',
    title: 'Cơ hội học tập',
    effect: 'Bạn tham gia một khóa học ngắn và tích lũy thêm kinh nghiệm. (+2 Điểm Chiến thắng)',
    winPoints: 2,
  ),
  OpportunityCard(
    id: 'CƠ HỘI 08',
    title: 'Làm việc nhóm hiệu quả',
    effect: 'Nhóm của bạn phối hợp rất ăn ý. (Tất cả người chơi đều nhận +1 Điểm Chiến thắng)',
    winPoints: 1,
  ),
  OpportunityCard(
    id: 'CƠ HỘI 09',
    title: 'Cơ hội nghề nghiệp',
    effect: 'Bạn được mời tham gia một dự án thực tế. (Tiến thêm 3 ô)',
    winPoints: 2,
  ),
  OpportunityCard(
    id: 'CƠ HỘI 10',
    title: 'Bứt phá',
    effect: 'Bạn đã nỗ lực không ngừng và được ghi nhận. (Chọn một trong hai: +3 Điểm Chiến thắng hoặc đi thêm 2 ô)',
    winPoints: 3,
  ),
];

/// 10 lá Thử thách trong tài liệu.
const List<ChallengeCard> kChallengeCards = [
  ChallengeCard(
    id: 'THỬ THÁCH 01',
    title: 'Thuyết trình 30 giây',
    task: 'Hãy giới thiệu bản thân như đang tham gia phỏng vấn xin việc trong 30 giây.',
    winPoints: 2,
  ),
  ChallengeCard(
    id: 'THỬ THÁCH 02',
    title: 'Đoán nghề nghiệp',
    task:
        'Diễn tả một nghề nghiệp bằng hành động, không được nói. Các người chơi '
        'còn lại có 30 giây để đoán.',
    winPoints: 2,
  ),
  ChallengeCard(
    id: 'THỬ THÁCH 03',
    title: 'Kể tên nghề',
    task:
        'Trong 30 giây, kể tên ít nhất 5 nghề thuộc cùng một lĩnh vực '
        '(Y tế, Công nghệ, Giáo dục, Du lịch...).',
    winPoints: 2,
  ),
  ChallengeCard(
    id: 'THỬ THÁCH 04',
    title: 'Xử lý tình huống',
    task:
        'Một thành viên trong nhóm không hoàn thành nhiệm vụ, bạn sẽ xử lý như '
        'thế nào? Trả lời trong 30 giây.',
    winPoints: 3,
  ),
  ChallengeCard(
    id: 'THỬ THÁCH 05',
    title: 'Đoán tính cách Holland',
    task:
        'Bốc ngẫu nhiên một mã Holland (ví dụ: R, A hoặc E). Trong 20 giây, hãy '
        'nêu 2 đặc điểm của nhóm đó.',
    winPoints: 2,
  ),
  ChallengeCard(
    id: 'THỬ THÁCH 06',
    title: 'Kỹ năng nghề nghiệp',
    task:
        'Kể tên 3 kỹ năng cần có của một nghề bất kỳ '
        '(Giáo viên, Bác sĩ, Lập trình viên, Kế toán...).',
    winPoints: 2,
  ),
  ChallengeCard(
    id: 'THỬ THÁCH 07',
    title: 'Giải quyết vấn đề',
    task: 'Nếu bạn là trưởng nhóm và nhóm bị trễ tiến độ, bạn sẽ làm gì? Trình bày trong 30 giây.',
    winPoints: 3,
  ),
  ChallengeCard(
    id: 'THỬ THÁCH 08',
    title: 'Kể điểm mạnh',
    task: 'Hãy nêu 3 điểm mạnh của bản thân.',
    winPoints: 2,
  ),
  ChallengeCard(
    id: 'THỬ THÁCH 09',
    title: 'Ghép nghề với kỹ năng',
    task:
        'Người chơi bên trái chọn 1 nghề (ví dụ: Kiến trúc sư). Bạn phải nói 3 kỹ '
        'năng phù hợp.',
    winPoints: 2,
  ),
  ChallengeCard(
    id: 'THỬ THÁCH 10',
    title: 'Ước mơ nghề nghiệp',
    task:
        'Nếu được chọn một nghề trong tương lai, bạn sẽ chọn nghề gì? Vì sao? '
        'Trình bày trong 30 giây.',
    winPoints: 3,
  ),
];
