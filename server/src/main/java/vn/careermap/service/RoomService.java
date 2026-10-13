package vn.careermap.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.careermap.config.GameRulesConfig;
import vn.careermap.domain.Player;
import vn.careermap.domain.PresenceStatus;
import vn.careermap.domain.Room;
import vn.careermap.domain.RoomStatus;
import vn.careermap.domain.TurnPhase;
import vn.careermap.exception.RoomAlreadyStartedException;
import vn.careermap.exception.RoomClosedException;
import vn.careermap.exception.RoomFullException;
import vn.careermap.exception.RoomNotFoundException;
import vn.careermap.repo.PlayerRepository;
import vn.careermap.repo.RoomRepository;
import vn.careermap.web.dto.CreateRoomRequest;
import vn.careermap.web.dto.GameSnapshot;
import vn.careermap.web.dto.HeartbeatResponse;
import vn.careermap.web.dto.JoinRoomRequest;
import vn.careermap.web.dto.OpenRoomResponse;
import vn.careermap.web.dto.PlayerInfo;
import vn.careermap.web.dto.RoomResponse;

@Service
public class RoomService {
  private static final Logger log = LoggerFactory.getLogger(RoomService.class);
  private static final int MAX_PLAYERS = 4;

  /** Số dòng lấy từ DB rồi mới lọc tiếp (phòng đã đủ người/để lâu bị loại
   *  sau khi đã vào tận SQL). */
  private static final int OPEN_ROOM_SCAN = 50;

  /** Số phòng tối đa trả về cho client. */
  private static final int OPEN_ROOM_LIMIT = 20;

  private final RoomRepository roomRepository;
  private final PlayerRepository playerRepository;
  private final GameBroadcaster broadcaster;
  private final GameSnapshotBuilder snapshotBuilder;
  private final GameService gameService;
  private final TurnCoordinator turns;
  private final PresenceService presence;
  private final GameRulesConfig rules;
  private final SecureRandom random = new SecureRandom();

  public RoomService(
      RoomRepository roomRepository,
      PlayerRepository playerRepository,
      GameBroadcaster broadcaster,
      GameSnapshotBuilder snapshotBuilder,
      GameService gameService,
      TurnCoordinator turns,
      PresenceService presence,
      GameRulesConfig rules) {
    this.roomRepository = roomRepository;
    this.playerRepository = playerRepository;
    this.broadcaster = broadcaster;
    this.snapshotBuilder = snapshotBuilder;
    this.gameService = gameService;
    this.turns = turns;
    this.presence = presence;
    this.rules = rules;
  }

  @Transactional
  public RoomResponse create(CreateRoomRequest request) {
    String hostName = request.hostName() == null || request.hostName().isBlank()
        ? "Người chơi 1"
        : request.hostName().trim();
    String code = generateUniqueCode();
    // ownerKey = playerKey của chủ phòng, khai báo NGAY LÚC TẠO để mọi endpoint
    // sau này đều xác định được chủ phòng mà không phải suy đoán từ danh sách.
    String ownerKey = "host-" + code;
    Room room = Room.create(code, hostName, Boolean.TRUE.equals(request.isPrivate()), ownerKey);
    room.addPlayer(Player.create(ownerKey, hostName));
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
   * tránh kéo cả bảng phòng (tích luỹ hàng nghìn dòng) lên app. Chỉ giữ phòng
   * còn ít nhất một người ONLINE, vì phòng toàn offline thì vào cũng chơi không
   * được và chỉ làm rối danh sách.
   */
  @Transactional
  public List<OpenRoomResponse> listOpen() {
    Instant now = Instant.now();
    return roomRepository
        .findByPrivateRoomFalseAndActiveFalseOrderByCreatedAtDesc(PageRequest.of(0, OPEN_ROOM_SCAN))
        .stream()
        .filter(room -> room.canJoin())
        .filter(room -> !room.isFinished())
        // Không còn lọc theo tuổi phòng: dọn phòng đã lo phần việc này theo
        // luật "không còn ai online". Lọc thêm ở đây sẽ ẩn mất những phòng
        // còn người thật đang ngồi chờ — đúng loại phòng người chơi cần vào.
        .filter(room -> !room.getPlayers().isEmpty())
        .filter(room -> room.getPlayers().stream().noneMatch(Player::isLeft))
        .filter(room -> presence.onlineCount(room, now) > 0)
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

  /** Người chơi chọn nhân vật ở phòng chờ. KHÔNG đụng tới tên người chơi —
   *  trước đây chọn nhân vật gọi hàm này nên tên bị ghi đè. */
  @Transactional
  public RoomResponse pickCharacter(
      String rawCode, String rawPlayerKey, String rawCharacterName) {
    Room room = findOrThrow(rawCode);
    if (room.isActive()) {
      throw new RoomAlreadyStartedException(room.getCode());
    }
    String playerKey = rawPlayerKey == null || rawPlayerKey.isBlank() ? "" : rawPlayerKey.trim();
    String characterName =
        rawCharacterName == null || rawCharacterName.isBlank() ? null : rawCharacterName.trim();
    Player player = room.getPlayers().stream()
        .filter(p -> p.getPlayerKey().equals(playerKey))
        .findFirst()
        .orElseThrow(() -> new RoomNotFoundException(playerKey));
    player.setCharacterName(characterName);
    RoomResponse response = toResponse(room);
    broadcaster.roomUpdated(room.getCode(), response);
    return response;
  }

  /**
   * Người chơi rời phòng/trận.
   * - Phòng CHƯA bắt đầu: xoá hẳn người chơi (không để avatar đen vào trận).
   * - Trận ĐANG diễn ra: đánh dấu `left` (avatar tối + bỏ qua lượt họ).
   */
  /**
 * Rời phòng/trận — ĐÂY LÀ ĐƯỜNG THOÁT CHỦ ĐỘNG DUY NHẤT.
 *
 * <p>Phân biệt rõ với mất kết nối: hàm này chỉ chạy khi người chơi đã bấm xác
 * nhận trong hộp thoát. Đóng tab / mất mạng không gọi được đây, nên không bao
 * giờ bị tính nhầm là "chủ động rời" và mất ghế.
 *
 * <ul>
 *   <li>Chủ phòng rời → xóa hẳn phòng ngay (đã xong, không ai chờ vô nghĩa).
 *   <li>Phòng chưa bắt đầu → xóa người chơi (không để avatar đen vào trận).
 *   <li>Trận đang diễn ra → đánh dấu {@code left}, giữ chỗ để có thể quay lại.
 *   <li>Chỉ còn 1 người trong trận → hỏi người đó chơi tiếp hay kết thúc.
 * </ul>
 */
  @Transactional
  public RoomResponse leave(String rawCode, String rawPlayerKey) {
    Room room = findOrThrow(rawCode);
    String playerKey = rawPlayerKey == null || rawPlayerKey.isBlank() ? "" : rawPlayerKey.trim();
    Player player = room.findPlayer(playerKey);
    if (player == null) {
      throw new RoomNotFoundException(playerKey);
    }

    // Chủ phòng thoát chủ động → xóa phòng, báo các máy còn lại phải về lobby.
    if (room.isOwner(playerKey)) {
      log.info("[LIFE] chủ phòng {} đóng phòng {}", player.getName(), room.getCode());
      deleteRoom(room, "owner-quit");
      throw new RoomClosedException(room.getCode(), "Chủ phòng đã đóng phòng");
    }

    if (!room.isActive()) {
      room.getPlayers().remove(player);
      playerRepository.delete(player);
      RoomResponse response = toResponse(room);
      broadcaster.roomUpdated(room.getCode(), response);
      return response;
    }

    if (player.isLeft()) {
      return toResponse(room);
    }
    player.setLeft(true);

    int index = room.indexOf(player);
    if (room.isOrderPhase()) {
      // Giai đoạn quyết định thứ tự đi LẦN LƯỢT: nếu mọi người còn lại đã tung
      // đủ thì khép giai đoạn; nếu chưa và người rời đang giữ lượt thì nhường
      // lượt cho người kế tiếp chưa tung.
      if (!room.finalizeIfOrderComplete() && room.getActivePlayerIndex() == index) {
        room.advanceToNextOrderRoller();
      }
} else if (room.getActivePlayerIndex() == index) {
      // Nguoi roi dang giu luot: dong luot treo va chuyen cho nguoi ke tiep, co
      // dat han moi - khong de ai phai cho 15s va an. closeAnswerTurn CHINH no
      // da clear + advance + startNextTurn, vi the khong duoc goi nua.
      gameService.closeAnswerTurn(room);
    }

    broadcaster.gameUpdated(room.getCode(), snapshotBuilder.build(room));
    RoomResponse response = toResponse(room);
    broadcaster.roomUpdated(room.getCode(), response);

    // Chỉ còn đúng một người trong trận → đề nghị chọn tiếp hay dừng.
    long remaining = room.getPlayers().stream().filter(p -> !p.isLeft()).count();
    room.getPlayers().stream()
        .filter(p -> !p.isLeft())
        .findFirst()
        .ifPresent(solo -> broadcaster.soloPlayer(room.getCode(), solo.getPlayerKey()));
    return response;
  }

  /** Xóa phòng và đẩy ROOM_CLOSED cho mọi máy còn theo dõi. */
  private void deleteRoom(Room room, String reason) {
    log.info("[LIFE] xoá phòng {} — lý do: {}", room.getCode(), reason);
    broadcaster.roomClosed(room.getCode(), reason);
    room.getPlayers().forEach(p -> p.setLeft(true));
    roomRepository.delete(room);
  }

  /** Ảnh chụp state ván hiện tại — client poll mỗi vài giây để khớp lại
   *  ngay cả khi WebSocket rớt (mạng yếu / bản web). */
  @Transactional(readOnly = true)
  public GameSnapshot snapshot(String rawCode) {
    return snapshotBuilder.build(findOrThrow(rawCode));
  }

  /**
   * Nhịp sống từ máy người chơi.
   *
   * <p>Hạ tầng đẩy một chiều nên không có sự kiện "mất kết nối"; heartbeat là tín
   * hiệu duy nhất. Trả kèm {@code serverTime} để client hiệu chỉnh đồng hồ so
   * với deadline — nếu không, máy có đồng hồ lệch sẽ thấy vòng đếm sai.
   *
   * <p>Phòng đã bị xóa trả về {@code roomClosed=true} thay vì lỗi 404, để client
   * phân biệt "phòng bị xóa" với "mạng hỏng" và về đúng màn hình.
   */
  @Transactional
  public HeartbeatResponse heartbeat(String rawCode, String rawPlayerKey) {
    String code = rawCode == null ? "" : rawCode.trim().toUpperCase();
    String key = rawPlayerKey == null ? "" : rawPlayerKey.trim();
    Room room = roomRepository.findByCode(code).orElse(null);
    if (room == null) {
      return new HeartbeatResponse("left", rules.heartbeatS, Instant.now(), true);
    }
    Player player = room.findPlayer(key);
    if (player == null) {
      return new HeartbeatResponse("left", rules.heartbeatS, Instant.now(), false);
    }
    PresenceStatus status = presence.heartbeat(room.getCode(), key);
    return new HeartbeatResponse(
        status.name().toLowerCase(), rules.heartbeatS, Instant.now(), false);
  }

  /**
   * Chủ phòng (hoặc người đang giữ quyền tạm) đóng phòng → xóa ngay.
   *
   * <p>Kiểm tra quyền ở service, không tin client: {@code effectiveHostKey} trả về
   * {@code actingHostKey} khi chủ phòng đang vắng mặt.
   */
  @Transactional
  public void closeRoom(String rawCode, String rawPlayerKey) {
    Room room = findOrThrow(rawCode);
    String key = rawPlayerKey == null ? "" : rawPlayerKey.trim();
    if (!key.equals(room.effectiveHostKey())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Chỉ chủ phòng mới đóng được phòng");
    }
    deleteRoom(room, "owner-closed");
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
                player.getCharacterName(),
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