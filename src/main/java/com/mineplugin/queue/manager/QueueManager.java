package com.mineplugin.queue.manager;

import com.mineplugin.queue.StandaloneQueuePlugin;
import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.model.QueuePosition;
import com.mineplugin.queue.model.QueuedPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Bộ quản lý hàng chờ trung tâm (Queue Engine).
 * Xử lý kiểm soát luồng, slot reservation, keep-alive heartbeat, và thread-safe concurrency.
 */
public class QueueManager {

    private final StandaloneQueuePlugin plugin;
    private final QueueConfig config;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Map lưu thông tin người chơi trong hàng chờ: UUID -> QueuedPlayer
    private final Map<UUID, QueuedPlayer> queueMap = new HashMap<>();

    // Theo dõi người chơi đang trong quá trình chuyển giao kết nối (đã qua PreLogin nhưng chưa vào JoinEvent)
    // UUID -> Timestamp lúc được duyệt qua PreLogin
    private final Map<UUID, Long> inFlightLogins = new ConcurrentHashMap<>();

    // Theo dõi thời điểm kết nối gần nhất để chống spam (UUID -> Timestamp)
    private final Map<UUID, Long> rateLimitMap = new ConcurrentHashMap<>();

    // Cache quyền hạn của người chơi (UUID -> Set<Permission>)
    private final Map<UUID, Set<String>> permissionCache = new ConcurrentHashMap<>();

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
        return Bukkit.getServer() != null ? Bukkit.getMaxPlayers() : 20;
    }

    /**
     * Lấy số lượng người chơi đang chiếm slot thực tế:
     * Online Players + In-Flight Logins (đang trong quá trình handshake).
     */
    public int getActiveOccupiedSlots() {
        cleanInFlightLogins();
        int onlineCount = Bukkit.getServer() != null ? Bukkit.getOnlinePlayers().size() : 0;
        return onlineCount + inFlightLogins.size();
    }

    /**
     * Dọn dẹp các inFlightLogins bị treo quá 15 giây (do client bị crash hoặc rớt mạng khi handshake).
     */
    private void cleanInFlightLogins() {
        long now = System.currentTimeMillis();
        inFlightLogins.entrySet().removeIf(entry -> (now - entry.getValue()) > 15_000L);
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
        permissionCache.put(player.getUniqueId(), perms);
    }

    /**
     * Tính toán trọng số ưu tiên của người chơi dựa trên cache hoặc quyền.
     */
    public int calculatePriority(UUID uuid) {
        Set<String> perms = permissionCache.get(uuid);
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
        if (Bukkit.getServer() != null) {
            try {
                org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
                if (offlinePlayer.isOp()) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        Set<String> perms = permissionCache.get(uuid);
        return perms != null && perms.contains("queue.bypass");
    }

    /**
     * Xử lý sự kiện AsyncPlayerPreLogin.
     * Phương thức cốt lõi xử lý xếp hàng, giữ chỗ, kiểm tra timeout và cấp phép vào server.
     */
    public LoginDecision handlePreLogin(UUID uuid, String name, String ipAddress) {
        long now = System.currentTimeMillis();

        // 1. Kiểm tra Rate Limit chống spam kết nối
        Long lastAttempt = rateLimitMap.get(uuid);
        if (lastAttempt != null && (now - lastAttempt) < config.getRateLimitMs()) {
            return new LoginDecision(LoginResultType.RATE_LIMITED, null);
        }
        rateLimitMap.put(uuid, now);

        // 2. Nếu plugin bị tắt, cho phép vào thẳng
        if (!config.isEnabled()) {
            return new LoginDecision(LoginResultType.ALLOWED_DIRECT, null);
        }

        // 3. Kiểm tra quyền Bypass
        if (hasBypassPermission(uuid)) {
            lock.writeLock().lock();
            try {
                queueMap.remove(uuid);
                inFlightLogins.put(uuid, now);
            } finally {
                lock.writeLock().unlock();
            }
            return new LoginDecision(LoginResultType.ALLOWED_BYPASS, null);
        }

        lock.writeLock().lock();
        try {
            cleanInFlightLogins();
            int maxCap = getEffectiveMaxPlayers();
            int occupied = getActiveOccupiedSlots();
            int freeSlots = Math.max(0, maxCap - occupied);

            QueuedPlayer existingPlayer = queueMap.get(uuid);

            // 4. Kiểm tra xem người chơi có Reservation hợp lệ không
            if (existingPlayer != null && existingPlayer.hasActiveReservation()) {
                // Người chơi đang giữ slot hợp lệ và kết nối lại -> Cho phép vào!
                queueMap.remove(uuid);
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
                queuedPlayer.setPriority(priority);
            } else {
                // Kiểm tra giới hạn số lượng tối đa của hàng chờ
                if (config.getMaxQueueSize() > 0 && queueMap.size() >= config.getMaxQueueSize()) {
                    return new LoginDecision(LoginResultType.QUEUE_FULL, null);
                }
                queuedPlayer = new QueuedPlayer(uuid, name, ipAddress, priority);
                queueMap.put(uuid, queuedPlayer);
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
                    queuedPlayer.hasActiveReservation(),
                    queuedPlayer.getPriority()
            );

            return new LoginDecision(LoginResultType.IN_QUEUE, pos);

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Phân phối reservation cho các slot trống (yêu cầu gọi bên trong writeLock).
     */
    private void processReservationsInternal(int freeSlots) {
        if (freeSlots <= 0 || queueMap.isEmpty()) {
            return;
        }
        processReservationsInternal(getSortedQueueInternal(), freeSlots);
    }

    private void processReservationsInternal(List<QueuedPlayer> sorted, int freeSlots) {
        if (freeSlots <= 0 || sorted.isEmpty()) {
            return;
        }

        int allocated = 0;
        for (int i = 0; i < sorted.size(); i++) {
            QueuedPlayer qp = sorted.get(i);
            if (qp.hasActiveReservation()) {
                allocated++;
                continue;
            }
            if (allocated < freeSlots) {
                qp.reserveSlot(config.getSlotReservationMs());
                allocated++;
                if (config.isDebugLog()) {
                    plugin.getLogger().info("[Queue] Đã cấp slot giữ chỗ cho: " + qp.getUsername() + " (#" + (i + 1) + ")");
                }
            } else {
                break;
            }
        }
    }

    /**
     * Lấy danh sách hàng chờ đã sắp xếp theo Priority và Thời gian vào hàng (yêu cầu gọi bên trong lock).
     */
    private List<QueuedPlayer> getSortedQueueInternal() {
        List<QueuedPlayer> list = new ArrayList<>(queueMap.values());
        Collections.sort(list);
        return list;
    }

    /**
     * Dọn dẹp định kỳ (được gọi bởi QueueCleanupTask):
     * 1. Xóa người chơi quá 5 phút không gửi heartbeat ping (isKeepAliveExpired).
     * 2. Xóa các slot reservation đã hết hạn và chuyển lượt cho người tiếp theo.
     */
    public void cleanupTick() {
        // Dọn dẹp rateLimitMap (xóa các bản ghi quá 60s để tránh rò rỉ bộ nhớ)
        long now = System.currentTimeMillis();
        rateLimitMap.entrySet().removeIf(entry -> (now - entry.getValue()) > 60_000L);

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
                    if (config.isDebugLog()) {
                        plugin.getLogger().info("[Queue] Người chơi " + qp.getUsername() + " đã bị xóa khỏi hàng chờ do quá 5 phút không ping.");
                    }
                } else if (qp.isReservationExpired()) {
                    // Hết hạn giữ slot -> Thu hồi slot reservation
                    qp.clearReservation();
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
        lock.writeLock().lock();
        try {
            inFlightLogins.remove(uuid);
            queueMap.remove(uuid);
            cachePlayerPermissions(player);
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
        lock.writeLock().lock();
        try {
            inFlightLogins.remove(uuid);
            // Kích hoạt phân phối slot ngay lập tức
            boolean scheduled = false;
            if (Bukkit.getServer() != null && plugin != null && plugin.isEnabled()) {
                try {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        lock.writeLock().lock();
                        try {
                            int maxCap = getEffectiveMaxPlayers();
                            int occupied = getActiveOccupiedSlots();
                            int freeSlots = Math.max(0, maxCap - occupied);
                            processReservationsInternal(freeSlots);
                        } finally {
                            lock.writeLock().unlock();
                        }
                    }, 1L);
                    scheduled = true;
                } catch (Throwable ignored) {
                    scheduled = false;
                }
            }
            if (!scheduled) {
                int maxCap = getEffectiveMaxPlayers();
                int occupied = getActiveOccupiedSlots();
                int freeSlots = Math.max(0, maxCap - occupied);
                processReservationsInternal(freeSlots);
            }
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
            return size;
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
