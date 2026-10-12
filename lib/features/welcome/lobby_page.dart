import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../../core/config/api_config.dart';
import '../../core/storage/session_store.dart';
import '../../core/theme/tokens.dart';
import '../../core/ui/components/app_button.dart';
import '../../core/ui/components/app_card.dart';
import '../../core/ui/components/app_empty_state.dart';
import '../../core/ui/components/app_input.dart';
import '../../core/ui/components/app_loading.dart';
import '../game/data/game_api.dart';
import '../game/data/room_api.dart';
import '../game/domain/game_room.dart';
import '../game/domain/player.dart';
import '../game/presentation/game_page.dart';
import '../game/presentation/room_players.dart';
import 'domain/sample_character.dart';
import 'waiting_room_page.dart';

/// Sảnh chờ: tạo phòng (công khai / riêng tư), vào phòng bằng mã, hoặc vào
/// thẳng một phòng công khai đang mở.
class LobbyPage extends StatefulWidget {
  const LobbyPage({super.key, this.session, this.roomApi});

  final SessionStore? session;
  final RoomApi? roomApi;

  @override
  State<LobbyPage> createState() => _LobbyPageState();
}

class _LobbyPageState extends State<LobbyPage> {
  SessionStore get _session => widget.session ?? SessionStore.memory();

  final _roomCode = TextEditingController();
  late final TextEditingController _name = TextEditingController(
    text: _session.playerName ?? 'Bạn',
  );
  final _formKey = GlobalKey<FormState>();
  late final RoomApi _roomApi = widget.roomApi ?? RoomApi();
  bool _busy = false;
  bool _isPrivate = false;
  HttpGameApi? _prewarm;
  List<OpenRoom>? _openRooms;
  bool _loadingRooms = false;
  String? _roomsError;

  @override
  void initState() {
    super.initState();
    if (!ApiConfig.isLocalhost) {
      final api = HttpGameApi();
      _prewarm = api;
      unawaited(api.warmUpServer());
    }
    unawaited(_loadOpenRooms());
  }

  @override
  void dispose() {
    final name = _name.text.trim();
    if (name.isNotEmpty) _session.savePlayerName(name);
    _roomCode.dispose();
    _name.dispose();
    _roomApi.dispose();
    _prewarm?.dispose();
    super.dispose();
  }

  String get _myName {
    final name = _name.text.trim();
    return name.isEmpty ? 'Bạn' : name;
  }

  Future<void> _loadOpenRooms() async {
    if (mounted) setState(() => _loadingRooms = true);
    final result = await _roomApi.listOpen();
    if (!mounted) return;
    setState(() {
      _loadingRooms = false;
      switch (result) {
        case ApiSuccess<List<OpenRoom>>(:final data):
          _openRooms = data;
          _roomsError = null;
        case ApiFailure<List<OpenRoom>>(:final message):
          _roomsError = message;
      }
    });
  }

  void _gotoWaiting(GameRoom room, {required bool isHost, required String myName}) {
    final myPlayerKey = room.players.isEmpty ? '' : room.players.last.playerKey;
    _session.savePlayerName(myName);
    if (myPlayerKey.isNotEmpty) {
      _session.saveActiveRoom(room.code, myPlayerKey);
    }
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => WaitingRoomPage(
          roomCode: room.code,
          isHost: isHost,
          myName: myName,
          myPlayerKey: myPlayerKey,
          initialRoom: room,
          session: widget.session,
        ),
      ),
    );
  }

  void _resumeGame(String roomCode, String playerKey) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => GamePage(roomCode: roomCode, myPlayerKey: playerKey, isOnline: true),
      ),
    );
  }

  Future<void> _joinOpenRoom(OpenRoom room) async {
    if (_busy) return;
    _hintSlowFirstConnect();
    final myName = _myName;
    setState(() => _busy = true);
    final result = await _roomApi.join(room.code, myName);
    if (!mounted) return;
    setState(() => _busy = false);
    switch (result) {
      case ApiSuccess<GameRoom>(:final data):
        _gotoWaiting(data, isHost: false, myName: myName);
      case ApiFailure<GameRoom>(:final message):
        _showServerError(message, onRetry: () => _joinOpenRoom(room));
        unawaited(_loadOpenRooms());
    }
  }

  void _enter(String code, [List<Player>? players]) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => GamePage(roomCode: code, initialPlayers: players, session: widget.session),
      ),
    );
  }

  void _showServerError(
    String message, {
    required VoidCallback onRetry,
    String? secondaryLabel,
    VoidCallback? onSecondary,
  }) {
    final webLocalhost = kIsWeb && ApiConfig.isLocalhost;
    final hint = webLocalhost
        ? '\n\n💡 Web này đang build THIẾU API_BASE_URL nên trình duyệt cố gọi '
            'http://localhost:8080 của chính máy người dùng (không có server).\n'
            'Build lại bằng:\nflutter build web --release '
            '--dart-define=API_BASE_URL=${ApiConfig.productionBaseUrl}'
        : '\n\n(Log đầy đủ phương thức + URL + status đã in ở console)';
    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Không kết nối được server'),
        content: Text('$message$hint', textAlign: TextAlign.start),
        actions: [
          if (secondaryLabel != null)
            TextButton(
              onPressed: () {
                Navigator.pop(dialogContext);
                onSecondary?.call();
              },
              child: Text(secondaryLabel),
            ),
          FilledButton(
            onPressed: () {
              Navigator.pop(dialogContext);
              onRetry();
            },
            child: const Text('Thử lại'),
          ),
        ],
      ),
    );
  }

  Future<void> _createRoom() async {
    if (_busy) return;
    setState(() => _busy = true);
    _hintSlowFirstConnect();
    final myName = _myName;
    final result = await _roomApi.create(myName, isPrivate: _isPrivate);
    if (!mounted) return;
    setState(() => _busy = false);
    switch (result) {
      case ApiSuccess<GameRoom>(:final data):
        _gotoWaiting(data, isHost: true, myName: myName);
      case ApiFailure<GameRoom>(:final message):
        _showServerError(
          message,
          onRetry: _createRoom,
          secondaryLabel: 'Chơi local',
          onSecondary: () => _enterLocal(myName),
        );
    }
  }

  void _hintSlowFirstConnect() {
    if (ApiConfig.isLocalhost || HttpGameApi.isConfiguredServerWarm) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        duration: Duration(seconds: 8),
        content: Text('Đang khởi động server lần đầu, có thể mất vài chục giây…'),
      ),
    );
  }

  void _enterLocal(String myName) {
    _enter('HOLLAND', buildLocalPlayers(selfName: myName, selfColor: _colorFor(myName)));
  }

  Color _colorFor(String name) {
    final character = CareerCharacter.byName(name);
    if (character != null) return character.color;
    final palette = careerCharacters.map((c) => c.color).toList();
    return palette[name.hashCode.abs() % palette.length];
  }

  Future<void> _joinByCode() async {
    if (_busy) return;
    if (!(_formKey.currentState?.validate() ?? false)) return;
    final code = _roomCode.text.trim().toUpperCase();
    final myName = _myName;
    setState(() => _busy = true);
    _hintSlowFirstConnect();
    final result = await _roomApi.join(code, myName);
    if (!mounted) return;
    setState(() => _busy = false);
    switch (result) {
      case ApiSuccess<GameRoom>(:final data):
        _gotoWaiting(data, isHost: false, myName: myName);
      case ApiFailure<GameRoom>(:final message):
        _showServerError(message, onRetry: _joinByCode);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isDark = theme.brightness == Brightness.dark;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Phòng chơi'),
        leading: kIsWeb && ApiConfig.isLocalhost
            ? const _WebLocalhostBanner()
            : null,
      ),
      body: ListView(
        padding: const EdgeInsets.all(Gap.xl),
        children: [
          // Resume card
          if (widget.session?.activeRoom case final saved?)
            Padding(
              padding: const EdgeInsets.only(bottom: Gap.xl),
              child: _ResumeCard(
                roomCode: saved.roomCode,
                onResume: () => _resumeGame(saved.roomCode, saved.playerKey),
                onForget: () => setState(() => _session.clearActiveRoom()),
              ),
            ),

          // Name input
          AppCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Tên của bạn',
                  style: theme.textTheme.titleMedium,
                ),
                const SizedBox(height: Gap.xs),
                Text(
                  'Vào phòng chờ rồi chọn nhân vật nghề nghiệp sau cũng được.',
                  style: TextStyle(
                    fontSize: 13,
                    color: isDark ? AppColors.darkTextSecondary : AppColors.textSecondary,
                  ),
                ),
                const SizedBox(height: Gap.lg),
                AppInput(
                  controller: _name,
                  hint: 'Tên hiển thị',
                  prefixIcon: Icons.badge_outlined,
                  textCapitalization: TextCapitalization.words,
                ),
              ],
            ),
          ),
          const SizedBox(height: Gap.xl),

          // Open rooms
          _OpenRoomsCard(
            rooms: _openRooms,
            loading: _loadingRooms,
            error: _roomsError,
            busy: _busy,
            onJoin: _joinOpenRoom,
            onRefresh: _loadOpenRooms,
          ),
          const SizedBox(height: Gap.xl),

          // Create room
          AppCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Tạo phòng mới',
                  style: theme.textTheme.titleMedium,
                ),
                const SizedBox(height: Gap.xs),
                Text(
                  'Mời tối đa 3 người bạn để cùng khám phá bản đồ nghề nghiệp.',
                  style: TextStyle(
                    fontSize: 13,
                    color: isDark ? AppColors.darkTextSecondary : AppColors.textSecondary,
                  ),
                ),
                const SizedBox(height: Gap.lg),
                Text(
                  'Chọn kiểu phòng',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: isDark ? AppColors.darkTextPrimary : AppColors.textPrimary,
                  ),
                ),
                const SizedBox(height: Gap.sm),
                Wrap(
                  spacing: Gap.sm,
                  children: [
                    ChoiceChip(
                      label: const Text('Công khai'),
                      avatar: const Icon(Icons.groups_outlined, size: 18),
                      selected: !_isPrivate,
                      onSelected: (_) => setState(() => _isPrivate = false),
                    ),
                    ChoiceChip(
                      label: const Text('Riêng tư'),
                      avatar: const Icon(Icons.lock_outline, size: 18),
                      selected: _isPrivate,
                      onSelected: (_) => setState(() => _isPrivate = true),
                    ),
                  ],
                ),
                const SizedBox(height: Gap.sm),
                Text(
                  _isPrivate
                      ? 'Phòng riêng tư: không hiện trong danh sách phòng công khai. '
                          'Chỉ vào được bằng mã phòng bạn chia sẻ.'
                      : 'Phòng công khai: xuất hiện trong danh sách phòng để người '
                          'khác tìm và vào bằng mã.',
                  style: TextStyle(
                    fontSize: 13,
                    color: isDark ? AppColors.darkTextSecondary : AppColors.textSecondary,
                  ),
                ),
                const SizedBox(height: Gap.lg),
                AppButton(
                  label: 'Tạo phòng mới',
                  onPressed: _busy ? null : _createRoom,
                  icon: Icons.add,
                  isLoading: _busy,
                  fullWidth: true,
                ),
              ],
            ),
          ),
          const SizedBox(height: Gap.xl),

          // Join by code
          AppCard(
            child: Form(
              key: _formKey,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Tham gia bằng mã',
                    style: theme.textTheme.titleMedium,
                  ),
                  const SizedBox(height: Gap.lg),
                  AppInput(
                    controller: _roomCode,
                    hint: 'Mã phòng, ví dụ HOLLAND',
                    prefixIcon: Icons.key_outlined,
                    textCapitalization: TextCapitalization.characters,
                    validator: (value) {
                      if (value == null || value.trim().isEmpty) {
                        return 'Nhập mã phòng';
                      }
                      return null;
                    },
                  ),
                  const SizedBox(height: Gap.lg),
                  AppButton(
                    label: 'Vào phòng',
                    onPressed: _busy ? null : _joinByCode,
                    icon: Icons.login,
                    isLoading: _busy,
                    fullWidth: true,
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ResumeCard extends StatelessWidget {
  const _ResumeCard({required this.roomCode, required this.onResume, required this.onForget});

  final String roomCode;
  final VoidCallback onResume;
  final VoidCallback onForget;

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Material(
      color: isDark ? const Color(0xFF1A3A2A) : const Color(0xFFE8F5E9),
      borderRadius: BorderRadius.circular(Rad.lg),
      child: Padding(
        padding: const EdgeInsets.all(Gap.lg),
        child: Row(
          children: [
            Icon(Icons.history_rounded, color: Colors.green.shade700),
            const SizedBox(width: Gap.md),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Bạn đang có ván ở phòng này',
                    style: TextStyle(
                      fontWeight: FontWeight.w600,
                      color: isDark ? AppColors.darkTextPrimary : AppColors.textPrimary,
                    ),
                  ),
                  Text(
                    'Mã phòng $roomCode',
                    style: TextStyle(
                      fontSize: 13,
                      color: isDark ? AppColors.darkTextSecondary : AppColors.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
            AppButton(
              label: 'Vào lại',
              onPressed: onResume,
              size: AppButtonSize.sm,
            ),
            IconButton(
              tooltip: 'Không phải, bỏ ván này',
              onPressed: onForget,
              icon: const Icon(Icons.close),
            ),
          ],
        ),
      ),
    );
  }
}

class _OpenRoomsCard extends StatelessWidget {
  const _OpenRoomsCard({
    required this.rooms,
    required this.loading,
    required this.error,
    required this.busy,
    required this.onJoin,
    required this.onRefresh,
  });

  final List<OpenRoom>? rooms;
  final bool loading;
  final String? error;
  final bool busy;
  final ValueChanged<OpenRoom> onJoin;
  final VoidCallback onRefresh;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isDark = theme.brightness == Brightness.dark;

    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  'Phòng công khai',
                  style: theme.textTheme.titleMedium,
                ),
              ),
              IconButton(
                tooltip: 'Tải lại',
                onPressed: loading ? null : onRefresh,
                icon: loading
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.refresh),
              ),
            ],
          ),
          Text(
            'Vào nhanh các phòng đang mở. Phòng riêng tư không có ở đây — '
            'vào bằng mã phòng.',
            style: TextStyle(
              fontSize: 13,
              color: isDark ? AppColors.darkTextSecondary : AppColors.textSecondary,
            ),
          ),
          const SizedBox(height: Gap.md),
          if (error != null)
            Row(
              children: [
                Expanded(
                  child: Text(
                    'Không tải được danh sách: $error',
                    style: const TextStyle(color: AppColors.error),
                  ),
                ),
                TextButton(onPressed: onRefresh, child: const Text('Thử lại')),
              ],
            )
          else if (rooms == null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: Gap.lg),
              child: Column(
                children: [
                  const AppSkeleton(height: 48),
                  const SizedBox(height: Gap.md),
                  const AppSkeleton(height: 48),
                ],
              ),
            )
          else if (rooms!.isEmpty)
            AppEmptyState(
              icon: Icons.search_off,
              title: 'Chưa có phòng công khai nào đang mở',
              description: 'Hãy tạo phòng rồi rủ người khác vào nhé!',
              actionLabel: 'Tạo phòng',
              onAction: onRefresh,
            )
          else
            for (final room in rooms!)
              ListTile(
                contentPadding: EdgeInsets.zero,
                dense: true,
                leading: CircleAvatar(
                  backgroundColor: (isDark ? AppColors.darkPrimary : AppColors.primary)
                      .withValues(alpha: 0.1),
                  child: Text(
                    room.hostName.isEmpty ? '?' : room.hostName[0].toUpperCase(),
                    style: TextStyle(
                      color: isDark ? AppColors.darkPrimary : AppColors.primary,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                title: Text(room.hostName),
                subtitle: Text('Mã ${room.code} · còn ${room.freeSlots} chỗ'),
                trailing: AppButton(
                  label: 'Vào',
                  onPressed: busy ? null : () => onJoin(room),
                  size: AppButtonSize.sm,
                ),
              ),
        ],
      ),
    );
  }
}

class _WebLocalhostBanner extends StatelessWidget {
  const _WebLocalhostBanner();

  @override
  Widget build(BuildContext context) {
    return Material(
      color: const Color(0xFFFFF3E0),
      borderRadius: BorderRadius.circular(Rad.md),
      child: Padding(
        padding: const EdgeInsets.all(Gap.md),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(Icons.warning_amber_rounded, color: Colors.deepOrange),
            const SizedBox(width: Gap.md),
            Expanded(
              child: Text(
                'Bản web này đang trỏ server tại localhost:8080 (chỉ chạy khi '
                'mở server ngay trên máy này). Khi deploy hosting, xây lại với '
                '--dart-define=API_BASE_URL=${ApiConfig.productionBaseUrl}',
                style: const TextStyle(fontSize: 12.5, height: 1.45),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
