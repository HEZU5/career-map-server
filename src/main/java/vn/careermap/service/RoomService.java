package vn.careermap.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.careermap.domain.Player;
import vn.careermap.domain.Room;
import vn.careermap.exception.RoomAlreadyStartedException;
import vn.careermap.exception.RoomFullException;
import vn.careermap.exception.RoomNotFoundException;
import vn.careermap.repo.PlayerRepository;
import vn.careermap.repo.RoomRepository;
import vn.careermap.web.dto.CreateRoomRequest;
import vn.careermap.web.dto.GameSnapshot;
import vn.careermap.web.dto.JoinRoomRequest;
import vn.careermap.web.dto.OpenRoomResponse;
import vn.careermap.web.dto.PlayerInfo;
import vn.careermap.web.dto.RoomResponse;

@Service
public class RoomService {
  private static final int MAX_PLAYERS = 4;

  /** Phòng cũ hơn thời gian này coi như bỏ rơi — không hiện trong danh sách
   *  phòng mở nữa (người tạo phòng thường quên, không ai vào). */
  private static final Duration OPEN_ROOM_MAX_AGE = Duration.ofHours(2);

  /** Số dòng lấy từ DB rồi mới lọc tiếp (phòng đã đủ người/để lâu bị loại
   *  sau khi đã vào tận SQL). */
  private static final int OPEN_ROOM_SCAN = 50;

  /** Số phòng tối đa trả về cho client. */
  private static final int OPEN_ROOM_LIMIT = 20;

  private final RoomRepository roomRepository;
  private final PlayerRepository playerRepository;
  private final GameBroadcaster broadcaster;
  private final GameSnapshotBuilder snapshotBuilder;
  private final SecureRandom random = new SecureRandom();

  public RoomService(
      RoomRepository roomRepository,
      PlayerRepository playerRepository,
      GameBroadcaster broadcaster,
      GameSnapshotBuilder snapshotBuilder) {
    this.roomRepository = roomRepository;
    this.playerRepository = playerRepository;
    this.broadcaster = broadcaster;
    this.snapshotBuilder = snapshotBuilder;
  }

  @Transactional
  public RoomResponse create(CreateRoomRequest request) {
    String hostName = request.hostName() == null || request.hostName().isBlank()
        ? "Người chơi 1"
        : request.hostName().trim();
    String code = generateUniqueCode();
    Room room = Room.create(code, hostName, Boolean.TRUE.equals(request.isPrivate()));
    room.addPlayer(Player.create("host-" + code, hostName));
    RoomResponse response = toResponse(roomRepository.save(room));
    broadcaster.roomUpdated(code, response);
    return response;
  }

  @Transactional
  public RoomResponse join(String rawCode, JoinRoomRequest request) {
    Room room = findOrThrow(rawCode);
    if (room.isActive()) {
      throw new RoomAlreadyStartedException(room.getCode());
    }
    if (!room.canJoin()) {
      throw new RoomFullException(room.getCode());
    }
    String displayName = request.displayName() == null || request.displayName().isBlank()
        ? "Khách"
        : request.displayName().trim();
    room.addPlayer(Player.create("guest-" + room.getCode() + "-" + room.getPlayers().size(), displayName));
    RoomResponse response = toResponse(room);
    broadcaster.roomUpdated(room.getCode(), response);
    return response;
  }

  /** Chỉ trả về phòng CÔNG KHAI. Phòng riêng tư vẫn vào được bằng mã phòng
   *  nhưng không lộ ra danh sách. */
  @Transactional
  public List<RoomResponse> list() {
    return roomRepository.findByPrivateRoomFalse().stream().map(this::toResponse).toList();
  }

  /**
   * Danh sách phòng công khai CÒN MỞ để người chơi tìm và vào: bỏ phòng
   * riêng tư, bỏ phòng đã bắt đầu, bỏ phòng đã đủ người, và bỏ phòng cũ quá
   * {@link #OPEN_ROOM_MAX_AGE} (phòng bỏ rơi sẽ không bao giờ vào nữa).
   *
   * <p>Chỉ lấy {@link #OPEN_ROOM_SCAN} dòng mới nhất rồi lọc tiếp trong bộ nhớ —
   * tránh kéo cả bảng phòng (tích luỹ hàng nghìn dòng) lên app.
   */
  @Transactional
  public List<OpenRoomResponse> listOpen() {
    return roomRepository
        .findByPrivateRoomFalseAndActiveFalseOrderByCreatedAtDesc(PageRequest.of(0, OPEN_ROOM_SCAN))
        .stream()
        .filter(room -> room.canJoin())
        .filter(room -> room.getCreatedAt().isAfter(Instant.now().minus(OPEN_ROOM_MAX_AGE)))
        .limit(OPEN_ROOM_LIMIT)
        .map(
            room ->
                new OpenRoomResponse(
                    room.getCode(),
                    room.getHostName(),
                    room.getPlayers().size(),
                    MAX_PLAYERS,
                    room.getCreatedAt()))
        .toList();
  }

  @Transactional(readOnly = true)
  public RoomResponse get(String rawCode) {
    return toResponse(findOrThrow(rawCode));
  }

  /** Chủ phòng bấm bắt đầu: mở giai đoạn tung xúc xắc quyết định thứ tự lượt
 *  ngay trong trận (ai tung cao nhất đi trước), rồi đẩy mọi người sang bàn cờ. */
  @Transactional
  public RoomResponse start(String rawCode) {
    Room room = findOrThrow(rawCode);
    if (room.isActive()) {
      throw new RoomAlreadyStartedException(room.getCode());
    }
    if (room.getPlayers().size() < 2) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cần ít nhất 2 người chơi để bắt đầu.");
    }
    for (Player p : room.getPlayers()) {
      p.setOrderRolled(false);
      p.setOrderDice(0);
    }
    room.setActive(true);
    room.setOrderPhase(true);
    room.setActivePlayerIndex(0);
    RoomResponse response = toResponse(room);
    broadcaster.gameStarted(room.getCode(), response);
    return response;
  }

  /** Người chơi đổi nhân vật (đổi tên hiển thị) ở phòng chờ. */
  @Transactional
  public RoomResponse rename(String rawCode, String rawPlayerKey, String rawName) {
    Room room = findOrThrow(rawCode);
    if (room.isActive()) {
      throw new RoomAlreadyStartedException(room.getCode());
    }
    String playerKey = rawPlayerKey == null || rawPlayerKey.isBlank() ? "" : rawPlayerKey.trim();
    String name = rawName == null || rawName.isBlank() ? "Khách" : rawName.trim();
    Player player = room.getPlayers().stream()
        .filter(p -> p.getPlayerKey().equals(playerKey))
        .findFirst()
        .orElseThrow(() -> new RoomNotFoundException(playerKey));
    player.setName(name);
    RoomResponse response = toResponse(room);
    broadcaster.roomUpdated(room.getCode(), response);
    return response;
  }

  /**
   * Người chơi rời phòng/trận.
   * - Phòng CHƯA bắt đầu: xoá hẳn người chơi (không để avatar đen vào trận).
   * - Trận ĐANG diễn ra: đánh dấu `left` (avatar tối + bỏ qua lượt họ).
   */
  @Transactional
  public RoomResponse leave(String rawCode, String rawPlayerKey) {
    Room room = findOrThrow(rawCode);
    String playerKey = rawPlayerKey == null || rawPlayerKey.isBlank() ? "" : rawPlayerKey.trim();
    List<Player> players = room.getPlayers();
    Player player = null;
    for (Player p : players) {
      if (p.getPlayerKey().equals(playerKey)) {
        player = p;
        break;
      }
    }
    if (player == null) {
      throw new RoomNotFoundException(playerKey);
    }

    if (!room.isActive()) {
      players.remove(player);
      playerRepository.delete(player);
      RoomResponse response = toResponse(room);
      broadcaster.roomUpdated(room.getCode(), response);
      return response;
    }

    if (player.isLeft()) {
      return toResponse(room);
    }
    player.setLeft(true);

    int index = players.indexOf(player);
    if (room.isOrderPhase()) {
      // Giai đoạn quyết định thứ tự đi LẦN LƯỢT: nếu mọi người còn lại đã
      // tung đủ thì khép giai đoạn; nếu chưa và người rời đang giữ lượt tung
      // thì nhường lượt cho người kế tiếp chưa tung.
      if (!room.finalizeIfOrderComplete() && room.getActivePlayerIndex() == index) {
        room.advanceToNextOrderRoller();
      }
    } else if (room.getActivePlayerIndex() == index) {
      room.setPendingAnswer(false);
      room.setLastDice(null);
      room.advanceTurn();
    }
    broadcaster.gameUpdated(room.getCode(), snapshotBuilder.build(room));
    RoomResponse response = toResponse(room);
    broadcaster.roomUpdated(room.getCode(), response);
    return response;
  }

  /** Ảnh chụp state ván hiện tại — client poll mỗi vài giây để khớp lại
   *  ngay cả khi WebSocket rớt (mạng yếu / bản web). */
  @Transactional(readOnly = true)
  public GameSnapshot snapshot(String rawCode) {
    return snapshotBuilder.build(findOrThrow(rawCode));
  }

  private Room findOrThrow(String rawCode) {
    return roomRepository
        .findByCode(rawCode.toUpperCase().trim())
        .orElseThrow(() -> new RoomNotFoundException(rawCode));
  }

  private String generateUniqueCode() {
    while (true) {
      String candidate = "H" + random.nextInt(90000, 100000);
      if (roomRepository.findByCode(candidate).isEmpty()) {
        return candidate;
      }
    }
  }

  private RoomResponse toResponse(Room room) {
    List<PlayerInfo> players =
        room.getPlayers().stream()
            .map(player -> new PlayerInfo(
                player.getPlayerKey(),
                player.getName(),
                player.isLeft(),
                player.isOrderRolled(),
                player.getOrderDice()))
            .toList();
    return new RoomResponse(
        room.getCode(),
        room.getHostName(),
        MAX_PLAYERS,
        players,
        room.isActive(),
        room.getCreatedAt(),
        room.isPrivateRoom());
  }
}