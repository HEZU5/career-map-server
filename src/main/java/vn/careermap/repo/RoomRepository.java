package vn.careermap.repo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.careermap.domain.Room;

public interface RoomRepository extends JpaRepository<Room, Long> {
  Optional<Room> findByCode(String code);

  /** Danh sách phòng CÔNG KHAI — phòng riêng tư không xuất hiện ở đây. */
  List<Room> findByPrivateRoomFalse();

  /** Phòng công khai CHƯA bắt đầu, mới nhất trước — nguồn cho danh sách
   *  "phòng còn mở". Phân trang + sắp xếp do SQL đảm nhiệm (bảng phòng tích
   *  luỹ rất nhiều dòng vì phòng cũ không bị xoá). */
  List<Room> findByPrivateRoomFalseAndActiveFalseOrderByCreatedAtDesc(Pageable pageable);
}