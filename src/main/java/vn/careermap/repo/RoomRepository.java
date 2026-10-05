package vn.careermap.repo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.careermap.domain.Room;

public interface RoomRepository extends JpaRepository<Room, Long> {
  Optional<Room> findByCode(String code);

  /** Danh sách phòng CÔNG KHAI — phòng riêng tư không xuất hiện ở đây. */
  List<Room> findByPrivateRoomFalse();
}