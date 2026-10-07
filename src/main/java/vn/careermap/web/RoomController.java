package vn.careermap.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.careermap.service.RoomService;
import vn.careermap.web.dto.CharacterRequest;
import vn.careermap.web.dto.CreateRoomRequest;
import vn.careermap.web.dto.GameSnapshot;
import vn.careermap.web.dto.HeartbeatResponse;
import vn.careermap.web.dto.JoinRoomRequest;
import vn.careermap.web.dto.OpenRoomResponse;
import vn.careermap.web.dto.RenameRequest;
import vn.careermap.web.dto.RoomResponse;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {
  private final RoomService roomService;

  public RoomController(RoomService roomService) {
    this.roomService = roomService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RoomResponse create(@RequestBody CreateRoomRequest request) {
    return roomService.create(request);
  }

  @GetMapping("/{code}")
  public RoomResponse get(@PathVariable String code) {
    return roomService.get(code);
  }

  @PostMapping("/{code}/players")
  public RoomResponse join(@PathVariable String code, @RequestBody JoinRoomRequest request) {
    return roomService.join(code, request);
  }

  @PostMapping("/{code}/start")
  public RoomResponse start(@PathVariable String code) {
    return roomService.start(code);
  }

  @PutMapping("/{code}/players/{playerKey}")
  public RoomResponse rename(
      @PathVariable String code,
      @PathVariable String playerKey,
      @RequestBody RenameRequest request) {
    return roomService.rename(code, playerKey, request.name());
  }

  /** Chọn nhân vật: lưu riêng, KHÔNG ghi đè tên người chơi. */
  @PutMapping("/{code}/players/{playerKey}/character")
  public RoomResponse pickCharacter(
      @PathVariable String code,
      @PathVariable String playerKey,
      @RequestBody CharacterRequest request) {
    return roomService.pickCharacter(code, playerKey, request.characterName());
  }

  @GetMapping("/{code}/snapshot")
  public GameSnapshot snapshot(@PathVariable String code) {
    return roomService.snapshot(code);
  }

  /**
   * Chủ phòng đóng phòng — xóa hẳn, báo các máy còn lại qua {@code ROOM_CLOSED}.
   *
   * <p>Quyền được kiểm tra ở service (so với {@code ownerKey} lẫn
   * {@code actingHostKey} khi chủ phòng đang vắng mặt), không tin client.
   */
  @DeleteMapping("/{code}")
  @ResponseStatus(HttpStatus.GONE)
  public Map<String, String> closeRoom(
      @PathVariable String code, @RequestParam String playerKey) {
    roomService.closeRoom(code, playerKey);
    return Map.of("code", code.toUpperCase().trim(), "closed", "true");
  }

  /**
   * Heartbeat: máy người chơi báo còn sống mỗi vài giây.
   *
   * <p>Đây là nguồn nhận biết online/offline của hệ thống, vì hạ tầng STOMP ở đây
   * chỉ đẩy một chiều nên không có sự kiện {@code onDisconnect}. Client gửi mỗi
   * {@code HEARTBEAT_S}; quá {@code OFFLINE_DETECT_S} không thấy là offline.
   */
  @PostMapping("/{code}/heartbeat")
  public HeartbeatResponse heartbeat(
      @PathVariable String code, @RequestParam String playerKey) {
    return roomService.heartbeat(code, playerKey);
  }

  /** Chủ phòng thoát chủ động = đóng phòng. */
  @DeleteMapping("/{code}/players/{playerKey}")
  public RoomResponse leave(
      @PathVariable String code, @PathVariable String playerKey) {
    return roomService.leave(code, playerKey);
  }

  @GetMapping
  public List<RoomResponse> list() {
    return roomService.list();
  }

  /** Phòng công khai còn mở (chưa bắt đầu, chưa đủ người, mới tạo) — danh
   *  sách để người chơi tìm và vào. Khác `GET /api/rooms` là bản đầy đủ mọi
   *  phòng công khai. */
  @GetMapping("/open")
  public List<OpenRoomResponse> listOpen() {
    return roomService.listOpen();
  }
}