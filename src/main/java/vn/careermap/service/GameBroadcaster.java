package vn.careermap.service;

import java.util.Map;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import vn.careermap.web.dto.GameSnapshot;
import vn.careermap.web.dto.RoomResponse;

/** Phát thời điểm thật cho mọi client đang "trong phòng" qua WebSocket. */
@Service
public class GameBroadcaster {
  private final SimpMessagingTemplate template;

  public GameBroadcaster(SimpMessagingTemplate template) {
    this.template = template;
  }

  /** Người chơi mới vào phòng — phòng chờ cập nhật danh sách tức thì. */
  public void roomUpdated(String code, RoomResponse room) {
    template.convertAndSend(topic(code), Map.of("type", "ROOM", "room", room));
  }

  /** Chủ phòng bấm bắt đầu — mọi client trong phòng cùng sang bàn cờ. */
  public void gameStarted(String code, RoomResponse room) {
    template.convertAndSend(topic(code), Map.of("type", "STARTED", "room", room));
  }

  /** Có nước đi / trả lời / đổi lượt — đồng bộ toàn bộ state ván. */
  public void gameUpdated(String code, GameSnapshot snapshot) {
    template.convertAndSend(topic(code), Map.of("type", "GAME", "game", snapshot));
  }

  private static String topic(String code) {
    return "/topic/rooms/" + code.toUpperCase();
  }
}