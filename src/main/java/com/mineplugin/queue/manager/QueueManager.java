package com.mineplugin.queue.manager;

import com.mineplugin.queue.StandaloneQueuePlugin;
import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.model.QueuePosition;
import com.mineplugin.queue.model.QueuedPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Bộ quản lý hàng chờ trung tâm (Queue Engine).
 *
 * @author BlastSama
 */
public class QueueManager {

    private static final int MAX_TRACKED_IDENTITIES = 4096;
    private static final long IN_FLIGHT_TIMEOUT_MS = 15_000L;

    private final StandaloneQueuePlugin plugin;
    private final QueueConfig config;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Map lưu thông tin người chơi trong hàng chờ: UUID -> QueuedPlayer
    private final Map<UUID, QueuedPlayer> queueMap = new HashMap<>();
    private List<QueuedPlayer> sortedSnapshot = List.of();
    private boolean queueOrderDirty = true;

    // Theo dõi người chơi đang trong quá trình chuyển giao kết nối (đã qua PreLogin nhưng chưa vào JoinEvent)
    // UUID -> Timestamp lúc được duyệt qua PreLogin
    private final Map<UUID, Long> inFlightLogins = new ConcurrentHashMap<>();

    // Theo dõi thời điểm kết nối gần nhất để chống spam (UUID -> Timestamp)
    private final Map<UUID, Long> rateLimitMap = new LinkedHashMap<>(256, 0.75f, true);
    private final Map<String, ArrayDeque<Long>> ipAttemptWindows = new HashMap<>();
    private final ArrayDeque<Long> globalAttemptWindow = new ArrayDeque<>();

    // Cache quyền hạn của người chơi (UUID -> Set<Permission>)
    private final Map<UUID, Set<String>> permissionCache = new LinkedHashMap<>(256, 0.75f, true);
    private final AtomicInteger onlinePlayers = new AtomicInteger();
    private final AtomicInteger serverMaxPlayers = new AtomicInteger(20);

    public QueueManager(StandaloneQueuePlugin plugin, QueueConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Kết quả xử lý khi người chơi cố gắng kết nối.
     */
    public enum LoginResultType {
        ALLOWED_DIRECT,       // Còn slot, vào thẳng
        ALLOWED_RESERVED,     // Đã có slot giữ chỗ sẵn, vào thành công
        ALLOWED_BYPASS,       // Có quyền bypass hàng chờ
        RATE_LIMITED,         // Kết nối quá nhanh (dưới rate limit)
        QUEUE_FULL,           // Hàng chờ đã vượt quá sức chứa tối đa
        IN_QUEUE              // Đang trong hàng chờ (bị kick kèm vị trí)
    }

    public record LoginDecision(LoginResultType type, QueuePosition position) {}

    /**
     * Lấy sức chứa tối đa hiệu dụng của server (theo config override hoặc Bukkit.getMaxPlayers()).
     */
    public int getEffectiveMaxPlayers() {
        int override = config.getMaxPlayersOverride();
        if (override > 0) {
            return override;
        }
        return serverMaxPlayers.get();
    }

    /**
     * Lấy số lượng người chơi đang chiếm slot thực tế:
     * Online Players + In-Flight Logins (đang trong quá trình handshake).
     */
    public int getActiveOccupiedSlots() {
        return onlinePlayers.get() + inFlightLogins.size();
    }

    public int getOnlinePlayerCount() {
        return onlinePlayers.get();
    }

    /**
     * Dọn dẹp các inFlightLogins bị treo quá 15 giây (do client bị crash hoặc rớt mạng khi handshake).
     */
    private void cleanInFlightLogins() {
        long now = System.currentTimeMillis();
        inFlightLogins.entrySet().removeIf(entry -> elapsed(now, entry.getValue()) > IN_FLIGHT_TIMEOUT_MS);
    }

    private static long elapsed(long now, long then) {
        return now >= then ? now - then : 0L;
    }

    /** Refreshes Bukkit-owned capacity state. Must only be called from the server thread. */
    public void refreshServerCapacity() {
        if (Bukkit.getServer() != null) {
            onlinePlayers.set(Bukkit.getOnlinePlayers().size());
            serverMaxPlayers.set(Math.max(0, Bukkit.getMaxPlayers()));
        }
    }

    void setOnlinePlayersForTesting(int count) {
        onlinePlayers.set(Math.max(0, count));
    }

    /**
     * Lưu trữ cache quyền của người chơi khi họ online.
     */
    public void cachePlayerPermissions(Player player) {
        Set<String> perms = new HashSet<>();
        if (player.hasPermission("queue.bypass")) {
            perms.add("queue.bypass");
        }
        for (String perm : config.getPermissionPriorities().keySet()) {
            if (player.hasPermission(perm)) {
                perms.add(perm);
            }
        }
        synchronized (permissionCache) {
            if (permissionCache.size() >= MAX_TRACKED_IDENTITIES && !permissionCache.containsKey(player.getUniqueId())) {
                permissionCache.remove(permissionCache.keySet().iterator().next());
            }
            permissionCache.put(player.getUniqueId(), Set.copyOf(perms));
        }
    }

    /**
     * Tính toán trọng số ưu tiên của người chơi dựa trên cache hoặc quyền.
     */
    public int calculatePriority(UUID uuid) {
        Set<String> perms;
        synchronized (permissionCache) {
            perms = permissionCache.get(uuid);
        }
        if (perms == null || perms.isEmpty()) {
            return 0;
        }
        int maxPriority = 0;
        for (Map.Entry<String, Integer> entry : config.getPermissionPriorities().entrySet()) {
            if (perms.contains(entry.getKey())) {
                maxPriority = Math.max(maxPriority, entry.getValue());
            }
        }
        return maxPriority;
    }

    /**
     * Kiểm tra xem người chơi có quyền bypass hàng chờ không.
     * Tự động cho phép OP (kể cả khi vừa khởi động server chưa online).
     */
    public boolean hasBypassPermission(UUID uuid) {
        Set<String> perms;
        synchronized (permissionCache) {
            perms = permissionCache.get(uuid);
        }
        return perms != null && perms.contains("queue.bypass");
    }

    /**
     * Xử lý sự kiện AsyncPlayerPreLogin.
     * Phương thức cốt lõi xử lý xếp hàng, giữ chỗ, kiểm tra timeout và cấp phép vào server.
     */
    public LoginDecision handlePreLogin(UUID uuid, String name, String ipAddress) {
        synchronized (config) {
            return handlePreLoginWithConfigSnapshot(uuid, name, ipAddress);
        }
    }

    private LoginDecision handlePreLoginWithConfigSnapshot(UUID uuid, String name, String ipAddress) {
        Objects.requireNonNull(uuid, "uuid");
        long now = System.currentTimeMillis();

        if (config.isAntiBotEnabled() && !acceptAntiBotAttempt(ipAddress, now)) {
            return new LoginDecision(LoginResultType.RATE_LIMITED, null);
        }

        // 1. Kiểm tra Rate Limit chống spam kết nối
        synchronized (rateLimitMap) {
            Long lastAttempt = rateLimitMap.get(uuid);
            if (lastAttempt != null && (now - lastAttempt) < config.getRateLimitMs()) {
                return new LoginDecision(LoginResultType.RATE_LIMITED, null);
            }
            if (lastAttempt == null && rateLimitMap.size() >= MAX_TRACKED_IDENTITIES) {
                rateLimitMap.entrySet().removeIf(entry -> elapsed(now, entry.getValue()) > 60_000L);
                if (rateLimitMap.size() >= MAX_TRACKED_IDENTITIES) {
                    rateLimitMap.remove(rateLimitMap.keySet().iterator().next());
                }
            }
            rateLimitMap.put(uuid, now);
        }

        // 2. Nếu plugin bị tắt, cho phép vào thẳng
        if (!config.isEnabled()) {
            return new LoginDecision(LoginResultType.ALLOWED_DIRECT, null);
        }

        // 3. Kiểm tra quyền Bypass
        if (hasBypassPermission(uuid)) {
            lock.writeLock().lock();
            try {
                cleanInFlightLogins();
                if (inFlightLogins.containsKey(uuid)) {
                    return new LoginDecision(LoginResultType.RATE_LIMITED, null);
                }
                queueMap.remove(uuid);
                queueOrderDirty = true;
                inFlightLogins.put(uuid, now);
            } finally {
                lock.writeLock().unlock();
            }
            return new LoginDecision(LoginResultType.ALLOWED_BYPASS, null);
        }

        lock.writeLock().lock();
        try {
            cleanInFlightLogins();
            if (inFlightLogins.containsKey(uuid)) {
                return new LoginDecision(LoginResultType.RATE_LIMITED, null);
            }
            int maxCap = getEffectiveMaxPlayers();
            int occupied = getActiveOccupiedSlots();
            int freeSlots = Math.max(0, maxCap - occupied);

            QueuedPlayer existingPlayer = queueMap.get(uuid);

            if (config.isAntiBotEnabled() && existingPlayer == null
                    && config.getAntiBotMaxQueuedPerIp() > 0
                    && countQueuedFromIp(ipAddress) >= config.getAntiBotMaxQueuedPerIp()) {
                return new LoginDecision(LoginResultType.RATE_LIMITED, null);
            }

            // 4. Kiểm tra xem người chơi có Reservation hợp lệ không
            if (existingPlayer != null && existingPlayer.hasActiveReservation() && freeSlots > 0) {
                // Người chơi đang giữ slot hợp lệ và kết nối lại -> Cho phép vào!
                queueMap.remove(uuid);
                queueOrderDirty = true;
                inFlightLogins.put(uuid, now);
                if (config.isDebugLog()) {
                    plugin.getLogger().info("[Queue] Người chơi " + name + " đã kích hoạt slot giữ chỗ thành công và vào game.");
                }
                return new LoginDecision(LoginResultType.ALLOWED_RESERVED, null);
            }

            // 5. Nếu server còn slot trống VÀ hàng chờ đang trống: Cho phép vào thẳng
            if (freeSlots > 0 && queueMap.isEmpty()) {
                inFlightLogins.put(uuid, now);
                return new LoginDecision(LoginResultType.ALLOWED_DIRECT, null);
            }

            // 6. Trường hợp server đã đầy hoặc có hàng chờ:
            // Cập nhật hoặc thêm người chơi vào hàng chờ
            int priority = calculatePriority(uuid);
            QueuedPlayer queuedPlayer;

            if (existingPlayer != null) {
                queuedPlayer = existingPlayer;
                queuedPlayer.recordPing(); // Cập nhật thời gian Heartbeat (5 phút tính từ thời điểm này)
                if (queuedPlayer.getPriority() != priority) {
                    queuedPlayer.setPriority(priority);
                    queueOrderDirty = true;
                }
            } else {
                // Kiểm tra giới hạn số lượng tối đa của hàng chờ
                if (config.getMaxQueueSize() > 0 && queueMap.size() >= config.getMaxQueueSize()) {
                    return new LoginDecision(LoginResultType.QUEUE_FULL, null);
                }
                queuedPlayer = new QueuedPlayer(uuid, name, ipAddress, priority);
                queueMap.put(uuid, queuedPlayer);
                queueOrderDirty = true;
                if (config.isDebugLog()) {
                    plugin.getLogger().info("[Queue] Thêm người chơi " + name + " vào hàng chờ (Priority: " + priority + ")");
                }
            }

            // 7. Cập nhật phân phối Reservation cho những người đứng đầu hàng chờ
            List<QueuedPlayer> sortedQueue = getSortedQueueInternal();
            processReservationsInternal(sortedQueue, freeSlots);

            // Kiểm tra lại: nếu sau khi phân phối, người chơi này vừa nhận được reservation ngay lúc này
            // và slot trống vẫn còn, cho phép vào ngay lập tức!
            if (queuedPlayer.hasActiveReservation() && (maxCap - getActiveOccupiedSlots()) > 0) {
                queueMap.remove(uuid);
                queueOrderDirty = true;
                inFlightLogins.put(uuid, now);
                return new LoginDecision(LoginResultType.ALLOWED_RESERVED, null);
            }

            // 8. Tính toán vị trí hiện tại trong hàng chờ
            int position = 1;
            for (int i = 0; i < sortedQueue.size(); i++) {
                if (sortedQueue.get(i).getUuid().equals(uuid)) {
                    position = i + 1;
                    break;
                }
            }

            long remainingSec = queuedPlayer.getRemainingKeepAliveMs(config.getKeepAliveMs()) / 1000L;
            QueuePosition pos = new QueuePosition(
                    position,
                    sortedQueue.size(),
                    remainingSec,
                    queuedPlayer.hasActiveReservation() && freeSlots > 0,
                    queuedPlayer.getPriority()
            );

            return new LoginDecision(LoginResultType.IN_QUEUE, pos);

        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean acceptAntiBotAttempt(String ipAddress, long now) {
        String ip = (ipAddress == null || ipAddress.isBlank()) ? "unknown" : ipAddress;
        synchronized (globalAttemptWindow) {
            long cutoff = now - config.getAntiBotWindowMs();
            while (!globalAttemptWindow.isEmpty() && globalAttemptWindow.peekFirst() <= cutoff) globalAttemptWindow.removeFirst();
            int globalLimit = config.getAntiBotGlobalAttemptsPerWindow();
            if (globalLimit > 0 && globalAttemptWindow.size() >= globalLimit) return false;
            if (ipAttemptWindows.size() >= 4096 && !ipAttemptWindows.containsKey(ip)) return false;
            ArrayDeque<Long> attempts = ipAttemptWindows.computeIfAbsent(ip, ignored -> new ArrayDeque<>());
            while (!attempts.isEmpty() && attempts.peekFirst() <= cutoff) attempts.removeFirst();
            int ipLimit = config.getAntiBotMaxAttemptsPerIp();
            if (ipLimit > 0 && attempts.size() >= ipLimit) return false;
            attempts.addLast(now);
            globalAttemptWindow.addLast(now);
            if (ipAttemptWindows.size() > 4096) ipAttemptWindows.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            return true;
        }
    }

    private int countQueuedFromIp(String ipAddress) {
        int count = 0;
        for (QueuedPlayer player : queueMap.values()) {
            if (Objects.equals(player.getIpAddress(), ipAddress)) count++;
        }
        return count;
    }

    /**
     * Phân phối reservation cho các slot trống (yêu cầu gọi bên trong writeLock).
     */
    private void processReservationsInternal(int freeSlots) {
        if (queueMap.isEmpty()) {
            return;
        }
        processReservationsInternal(getSortedQueueInternal(), freeSlots);
    }

    private void processReservationsInternal(List<QueuedPlayer> sorted, int freeSlots) {
        if (sorted.isEmpty()) {
            return;
        }

        for (int i = 0; i < sorted.size(); i++) {
            QueuedPlayer qp = sorted.get(i);
            if (i < freeSlots && !qp.hasActiveReservation()) {
                qp.reserveSlot(config.getSlotReservationMs());
                if (config.isDebugLog()) {
                    plugin.getLogger().info("[Queue] Đã cấp slot giữ chỗ cho: " + qp.getUsername() + " (#" + (i + 1) + ")");
                }
            } else if (i >= freeSlots && qp.hasActiveReservation()) {
                qp.clearReservation();
            }
        }
    }

    /**
     * Lấy danh sách hàng chờ đã sắp xếp theo Priority và Thời gian vào hàng (yêu cầu gọi bên trong lock).
     */
    private List<QueuedPlayer> getSortedQueueInternal() {
        if (queueOrderDirty) {
            ArrayList<QueuedPlayer> list = new ArrayList<>(queueMap.values());
            Collections.sort(list);
            sortedSnapshot = List.copyOf(list);
            queueOrderDirty = false;
        }
        return sortedSnapshot;
    }

    /**
     * Dọn dẹp định kỳ (được gọi bởi QueueCleanupTask):
     * 1. Xóa người chơi quá 5 phút không gửi heartbeat ping (isKeepAliveExpired).
     * 2. Xóa các slot reservation đã hết hạn và chuyển lượt cho người tiếp theo.
     */
    public void cleanupTick() {
        synchronized (config) {
            cleanupTickWithConfigSnapshot();
        }
    }

    private void cleanupTickWithConfigSnapshot() {
        // Dọn dẹp rateLimitMap (xóa các bản ghi quá 60s để tránh rò rỉ bộ nhớ)
        long now = System.currentTimeMillis();
        synchronized (rateLimitMap) {
            rateLimitMap.entrySet().removeIf(entry -> elapsed(now, entry.getValue()) > 60_000L);
        }
        synchronized (globalAttemptWindow) {
            long cutoff = now - config.getAntiBotWindowMs();
            while (!globalAttemptWindow.isEmpty() && globalAttemptWindow.peekFirst() <= cutoff) globalAttemptWindow.removeFirst();
            ipAttemptWindows.values().forEach(attempts -> attempts.removeIf(timestamp -> timestamp <= cutoff));
            ipAttemptWindows.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }

        lock.writeLock().lock();
        try {
            cleanInFlightLogins();
            long maxKeepAliveMs = config.getKeepAliveMs();

            // 1. Kiểm tra hết hạn Keep-Alive (5 phút không reconnect)
            Iterator<Map.Entry<UUID, QueuedPlayer>> iterator = queueMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, QueuedPlayer> entry = iterator.next();
                QueuedPlayer qp = entry.getValue();

                if (qp.isKeepAliveExpired(maxKeepAliveMs)) {
                    iterator.remove();
                    queueOrderDirty = true;
                    if (config.isDebugLog()) {
                        plugin.getLogger().info("[Queue] Người chơi " + qp.getUsername() + " đã bị xóa khỏi hàng chờ do quá 5 phút không ping.");
                    }
                } else if (qp.isReservationExpired()) {
                    // Hết hạn giữ slot -> Thu hồi slot reservation
                    qp.requeueAfterExpiredReservation();
                    queueOrderDirty = true;
                    if (config.isDebugLog()) {
                        plugin.getLogger().info("[Queue] Thu hồi slot giữ chỗ của " + qp.getUsername() + " do quá thời hạn giữ slot.");
                    }
                }
            }

            // 2. Phân phối lại slot còn trống cho những người đứng đầu
            int maxCap = getEffectiveMaxPlayers();
            int occupied = getActiveOccupiedSlots();
            int freeSlots = Math.max(0, maxCap - occupied);
            processReservationsInternal(freeSlots);

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gọi khi người chơi kết nối thành công vào game (PlayerJoinEvent).
     */
    public void onPlayerJoin(Player player) {
        UUID uuid = player.getUniqueId();
        cachePlayerPermissions(player);
        refreshServerCapacity();
        lock.writeLock().lock();
        try {
            inFlightLogins.remove(uuid);
            queueMap.remove(uuid);
            queueOrderDirty = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gọi khi người chơi thoát game (PlayerQuitEvent).
     * Ngay lập tức giải phóng slot và kích hoạt phân phối slot cho người đứng đầu hàng chờ.
     */
    public void onPlayerQuit(Player player) {
        UUID uuid = player.getUniqueId();
        cachePlayerPermissions(player);
        onlinePlayers.updateAndGet(current -> Math.max(0, current - 1));
        synchronized (config) {
            onPlayerQuitWithConfigSnapshot(uuid);
        }
    }

    private void onPlayerQuitWithConfigSnapshot(UUID uuid) {
        lock.writeLock().lock();
        try {
            inFlightLogins.remove(uuid);
            int maxCap = getEffectiveMaxPlayers();
            int occupied = getActiveOccupiedSlots();
            int freeSlots = Math.max(0, maxCap - occupied);
            processReservationsInternal(freeSlots);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Kiểm tra xem người chơi có đang trong quá trình chuyển giao kết nối (handshake / in-flight) hay không.
     */
    public boolean isInFlight(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        return inFlightLogins.containsKey(uuid);
    }

    public void releaseInFlight(UUID uuid) {
        if (uuid != null) {
            inFlightLogins.remove(uuid);
        }
    }

    public int getInFlightCount() {
        return inFlightLogins.size();
    }

    public int getTrackedRateLimitCount() {
        synchronized (rateLimitMap) {
            return rateLimitMap.size();
        }
    }

    public int getTrackedIpCount() {
        synchronized (globalAttemptWindow) {
            return ipAttemptWindows.size();
        }
    }

    /**
     * Lấy danh sách toàn bộ người chơi đang trong hàng chờ (bản sao an toàn).
     */
    public List<QueuedPlayer> getQueueList() {
        lock.readLock().lock();
        try {
            return getSortedQueueInternal();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Lấy kích thước hiện tại của hàng chờ.
     */
    public int getQueueSize() {
        lock.readLock().lock();
        try {
            return queueMap.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Đếm số lượng slot đang được giữ chỗ.
     */
    public int getReservedSlotCount() {
        lock.readLock().lock();
        try {
            int count = 0;
            for (QueuedPlayer qp : queueMap.values()) {
                if (qp.hasActiveReservation()) {
                    count++;
                }
            }
            return count;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Xóa toàn bộ hàng chờ.
     */
    public int clearQueue() {
        lock.writeLock().lock();
        try {
            int size = queueMap.size();
            queueMap.clear();
            queueOrderDirty = true;
            return size;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clearRuntimeState() {
        lock.writeLock().lock();
        try {
            queueMap.clear();
            queueOrderDirty = true;
            inFlightLogins.clear();
            synchronized (rateLimitMap) {
                rateLimitMap.clear();
            }
            synchronized (permissionCache) {
                permissionCache.clear();
            }
            synchronized (globalAttemptWindow) {
                globalAttemptWindow.clear();
                ipAttemptWindows.clear();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Đá một người chơi cụ thể khỏi hàng chờ theo tên.
     */
    public boolean kickPlayerFromQueue(String username) {
        lock.writeLock().lock();
        try {
            UUID targetUuid = null;
            for (QueuedPlayer qp : queueMap.values()) {
                if (qp.getUsername().equalsIgnoreCase(username)) {
                    targetUuid = qp.getUuid();
                    break;
                }
            }
            if (targetUuid != null) {
                queueMap.remove(targetUuid);
                queueOrderDirty = true;
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Kiểm tra xem người chơi có đang trong hàng chờ hay không theo UUID.
     */
    public boolean isInQueue(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        lock.readLock().lock();
        try {
            return queueMap.containsKey(uuid);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Kiểm tra xem người chơi có đang trong hàng chờ hay không theo tên.
     */
    public boolean isInQueue(String username) {
        if (username == null || username.isEmpty()) {
            return false;
        }
        lock.readLock().lock();
        try {
            for (QueuedPlayer qp : queueMap.values()) {
                if (username.equalsIgnoreCase(qp.getUsername())) {
                    return true;
                }
            }
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Kiểm tra nhanh xem chuỗi log có chứa tên của bất kỳ người chơi nào trong hàng chờ hay không.
     * Thực hiện trực tiếp trên tập hợp values của map mà không cấp phát đối tượng hay sắp xếp danh sách.
     */
    public boolean containsAnyQueuedPlayerName(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        lock.readLock().lock();
        try {
            for (QueuedPlayer qp : queueMap.values()) {
                if (text.contains(qp.getUsername())) {
                    return true;
                }
            }
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }
}
