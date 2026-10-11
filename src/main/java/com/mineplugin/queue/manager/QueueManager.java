package com.mineplugin.queue.manager;

import com.mineplugin.queue.StandaloneQueuePlugin;
import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.config.QueueSettings;
import com.mineplugin.queue.model.LoginAttempt;
import com.mineplugin.queue.model.QueuePosition;
import com.mineplugin.queue.model.QueuedPlayer;
import com.mineplugin.queue.permission.PermissionResolver;
import com.mineplugin.queue.permission.PermissionSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Bộ quản lý hàng chờ trung tâm (Queue Engine).
 *
 * @author BlastSama
 */
public class QueueManager {

    private static final int MAX_TRACKED_IDENTITIES = 4096;

    private final StandaloneQueuePlugin plugin;
    private final QueueConfig config;
    private final Clock clock;
    private final PermissionResolver permissionResolver;
    private final CapacitySource capacitySource;
    private final AtomicLong timeoutCount = new AtomicLong();
    private final AtomicLong reloadRequest = new AtomicLong();
    private volatile boolean stopped;
    private volatile boolean recovering;
    private volatile boolean paused;
    private final EnumMap<LoginResultType, java.util.concurrent.atomic.LongAdder> decisions = new EnumMap<>(LoginResultType.class);
    private final java.util.concurrent.atomic.LongAdder preLoginNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder preLoginCalls = new java.util.concurrent.atomic.LongAdder();
    private final AtomicLong maxPreLoginNanos = new AtomicLong();
    private final AtomicLong failedLogins = new AtomicLong();
    private final AtomicLong cancelledPreLogins = new AtomicLong();
    private final AtomicLong successfulJoins = new AtomicLong();
    private record AdmissionInfo(String name, int priority, long waitingSince) {}
    private final AtomicLong reservationExpired = new AtomicLong();
    private final AtomicLong heartbeatExpired = new AtomicLong();
    private final AtomicLong permissionFailures = new AtomicLong();
    private final long[] waitBuckets = new long[5]; // cumulative 30s, 60s, 300s, 900s, +Inf
    private double waitSecondsSum;
    private final Map<UUID, AdmissionInfo> admissionNames = new HashMap<>();
    private final Set<UUID> reservedAdmissions = new HashSet<>();
    private final AdmissionHistory history = new AdmissionHistory();
    private long historyCachedAt = Long.MIN_VALUE;
    private AdmissionHistory.Snapshot historyCache = new AdmissionHistory.Snapshot(0, 0, 0);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Map lưu thông tin người chơi trong hàng chờ: UUID -> QueuedPlayer
    private final Map<UUID, QueuedPlayer> queueMap = new HashMap<>();
    private List<QueuedPlayer> sortedSnapshot = List.of();
    private boolean queueOrderDirty = true;

    // Theo dõi người chơi đang trong quá trình chuyển giao kết nối (đã qua PreLogin nhưng chưa vào JoinEvent)
    // Includes JOINED/FAILED until close: close events carry no per-connection token.
    private final Map<UUID, LoginAttempt> inFlightLogins = new ConcurrentHashMap<>();

    // Theo dõi thời điểm kết nối gần nhất để chống spam (UUID -> Timestamp)
    private final Map<UUID, Long> rateLimitMap = new LinkedHashMap<>(256, 0.75f, true);
    private final Map<String, ArrayDeque<Long>> ipAttemptWindows = new HashMap<>();
    private final ArrayDeque<Long> globalAttemptWindow = new ArrayDeque<>();
    private final ArrayDeque<Long> protectedAttemptWindow = new ArrayDeque<>();
    private record QueueDisconnect(String name, String ip, String reason, long recordedAt) {}
    private final ArrayDeque<QueueDisconnect> queueDisconnects = new ArrayDeque<>();

    private final AtomicInteger onlinePlayers = new AtomicInteger();
    private final AtomicInteger serverMaxPlayers = new AtomicInteger(20);

    public QueueManager(StandaloneQueuePlugin plugin, QueueConfig config) {
        this(plugin, config, (uuid, name) -> CompletableFuture.completedFuture(
                new PermissionSnapshot(false, 0, System.currentTimeMillis(), "no-provider")), Clock.systemUTC());
    }

    public QueueManager(StandaloneQueuePlugin plugin, QueueConfig config,
                        PermissionResolver permissionResolver, Clock clock) {
        this(plugin, config, permissionResolver, clock, () -> Bukkit.getServer() == null ? null
                : new CapacitySource.Snapshot(Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers()));
    }

    public QueueManager(StandaloneQueuePlugin plugin, QueueConfig config,
                        PermissionResolver permissionResolver, Clock clock, CapacitySource capacitySource) {
        this.plugin = plugin;
        this.config = config;
        this.permissionResolver = Objects.requireNonNull(permissionResolver, "permissionResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.capacitySource = Objects.requireNonNull(capacitySource, "capacitySource");
        for (LoginResultType type : LoginResultType.values()) decisions.put(type, new java.util.concurrent.atomic.LongAdder());
    }

    /**
     * Kết quả xử lý khi người chơi cố gắng kết nối.
     */
    public enum LoginResultType {
        ALLOWED_DIRECT,       // Còn slot, vào thẳng
        ALLOWED_RESERVED,     // Đã có slot giữ chỗ sẵn, vào thành công
        ALLOWED_BYPASS,       // Có quyền bypass hàng chờ
        RATE_LIMITED,         // Kết nối quá nhanh (dưới rate limit)
        RATE_LIMITED_UUID, RATE_LIMITED_IP, RATE_LIMITED_GLOBAL, QUEUE_IP_QUOTA, PAUSED,
        QUEUE_FULL,           // Hàng chờ đã vượt quá sức chứa tối đa
        EXPIRED_AND_REQUEUED, // Lease hết hạn, hiển thị lượt mới
        IN_QUEUE              // Đang trong hàng chờ (bị kick kèm vị trí)
    }

    public record LoginDecision(LoginResultType type, QueuePosition position, UUID attemptId) {
        public LoginDecision(LoginResultType type, QueuePosition position) { this(type, position, null); }
    }

    public record LoginPermit(UUID attemptId, boolean admitted, boolean timedOut, boolean fullOverrideGranted) {}

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
        return onlinePlayers.get() + getInFlightCount();
    }

    public int getOnlinePlayerCount() {
        return onlinePlayers.get();
    }

    /** Time alone never releases capacity: the old handshake can still reach PlayerLoginEvent. */
    private void cleanInFlightLogins(long now) {
        inFlightLogins.replaceAll((uuid, attempt) -> {
            if (attempt.occupiesSlot() && !attempt.timeoutObserved() && now >= attempt.deadline()) {
                timeoutCount.incrementAndGet();
                trace(attempt, "timeout-observed");
                return attempt.observeTimeout();
            }
            return attempt;
        });
    }

    private LoginDecision admit(UUID uuid, String name, String ip, long now, int priority, LoginResultType type) {
        return admit(uuid, name, ip, now, priority, type, now);
    }

    private LoginDecision admit(UUID uuid, String name, String ip, long now, int priority, LoginResultType type, long waitingSince) {
        long duration = config.getInFlightTimeoutMs();
        UUID token = UUID.randomUUID();
        long deadline = duration > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + duration;
        admissionNames.put(uuid, new AdmissionInfo(name, Math.max(0, priority), waitingSince));
        inFlightLogins.put(uuid, new LoginAttempt(token, uuid, ip, now, deadline,
                LoginAttempt.Phase.PRELOGIN_ACCEPTED, null, false, config.isEnabled()));
        if (type == LoginResultType.ALLOWED_RESERVED) reservedAdmissions.add(uuid);
        trace(inFlightLogins.get(uuid), "pre-login-accepted");
        return new LoginDecision(type, null, token);
    }

    private static long elapsed(long now, long then) {
        return now >= then ? now - then : 0L;
    }

    /** Refreshes Bukkit-owned capacity state. Must only be called from the server thread. */
    public void refreshServerCapacity() {
        CapacitySource.Snapshot snapshot = capacitySource.snapshot();
        if (snapshot == null) return;
        lock.writeLock().lock();
        try {
            onlinePlayers.set(Math.max(0, snapshot.onlinePlayers()));
            serverMaxPlayers.set(Math.max(0, snapshot.maxPlayers()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    void setOnlinePlayersForTesting(int count) {
        onlinePlayers.set(Math.max(0, count));
    }

    /**
     * Xử lý sự kiện AsyncPlayerPreLogin.
     * Phương thức cốt lõi xử lý xếp hàng, giữ chỗ, kiểm tra timeout và cấp phép vào server.
     */
    public LoginDecision handlePreLogin(UUID uuid, String name, String ipAddress) {
        long started = System.nanoTime();
        try {
            LoginDecision decision = handlePreLoginInternal(uuid, name, ipAddress);
            decisions.get(decision.type()).increment();
            return decision;
        } finally {
            long duration = Math.max(0, System.nanoTime() - started);
            preLoginCalls.increment(); preLoginNanos.add(duration);
            maxPreLoginNanos.accumulateAndGet(duration, Math::max);
        }
    }

    private LoginDecision handlePreLoginInternal(UUID uuid, String name, String ipAddress) {
        Objects.requireNonNull(uuid, "uuid");
        long timeout;
        QueueSettings generation;
        synchronized (config) {
            LoginDecision early = checkAttempt(uuid, name, ipAddress, clock.millis());
            if (early != null) return early;
            timeout = config.getPermissionResolveTimeoutMs();
            generation = config.snapshot();
        }

        // One retry after a concurrent reload, within the original total wait budget.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        for (int retry = 0; retry < 2; retry++) {
            PermissionSnapshot rights;
            try {
                // Provider I/O never runs under the config monitor or queue lock.
                rights = permissionResolver.resolve(uuid, name, generation.permissionPriorities()).toCompletableFuture()
                        .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                permissionFailures.incrementAndGet();
                rights = PermissionSnapshot.none(clock.millis());
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException error) {
                permissionFailures.incrementAndGet();
                rights = PermissionSnapshot.none(clock.millis());
            }
            synchronized (config) {
                if (config.snapshot() == generation) return decide(uuid, name, ipAddress, rights);
                generation = config.snapshot();
            }
        }
        // Repeated reloads invalidate the lookup; do not overwrite freshly reconciled queue priorities.
        return new LoginDecision(LoginResultType.RATE_LIMITED, null);
    }

    private LoginDecision checkAttempt(UUID uuid, String name, String ipAddress, long now) {
        if (stopped || recovering) return new LoginDecision(LoginResultType.RATE_LIMITED, null);

        // 1. Kiểm tra Rate Limit chống spam kết nối
        synchronized (rateLimitMap) {
            Long lastAttempt = rateLimitMap.get(uuid);
            if (lastAttempt != null && elapsed(now, lastAttempt) < config.getRateLimitMs()) {
                return new LoginDecision(LoginResultType.RATE_LIMITED_UUID, null);
            }
            if (lastAttempt == null && rateLimitMap.size() >= MAX_TRACKED_IDENTITIES) {
                rateLimitMap.entrySet().removeIf(entry -> elapsed(now, entry.getValue()) >= Math.max(60_000L, config.getRateLimitMs()));
                if (rateLimitMap.size() >= MAX_TRACKED_IDENTITIES) {
                    rateLimitMap.remove(rateLimitMap.keySet().iterator().next());
                }
            }
            rateLimitMap.put(uuid, now);
        }

        if (config.isAntiBotEnabled()) {
            LoginResultType rejected = acceptAntiBotAttempt(uuid, ipAddress, now);
            if (rejected != null) return new LoginDecision(rejected, null);
        }

        // 2. Nếu plugin bị tắt, cho phép vào thẳng
        if (!config.isEnabled()) {
            // Still account for handshakes if reload enables queuing before they join.
            return decide(uuid, name, ipAddress, PermissionSnapshot.none(now));
        }

        return null;
    }

    private LoginDecision decide(UUID uuid, String name, String ipAddress, PermissionSnapshot rights) {
        lock.writeLock().lock();
        try {
            long now = clock.millis();
            if (stopped || recovering) return new LoginDecision(LoginResultType.RATE_LIMITED, null);
            if (rights == null || !rights.isFresh(now)) { permissionFailures.incrementAndGet(); rights = PermissionSnapshot.none(now); }
            cleanInFlightLogins(now);
            expireQueueEntries(now);
            if (inFlightLogins.containsKey(uuid) || inFlightLogins.size() >= MAX_TRACKED_IDENTITIES) {
                return new LoginDecision(LoginResultType.RATE_LIMITED, null);
            }
            if (!config.isEnabled()) return admit(uuid, name, ipAddress, now, rights.priorityWeight(), LoginResultType.ALLOWED_DIRECT);
            if (rights.bypass() && paused) return new LoginDecision(LoginResultType.PAUSED, null);
            if (rights.bypass()) {
                queueMap.remove(uuid);
                queueOrderDirty = true;
                LoginDecision decision = admit(uuid, name, ipAddress, now, rights.priorityWeight(), LoginResultType.ALLOWED_BYPASS);
                processReservationsInternal(Math.max(0, getEffectiveMaxPlayers() - getActiveOccupiedSlots()), now);
                return decision;
            }
            int maxCap = getEffectiveMaxPlayers();
            int freeSlots = Math.max(0, maxCap - getActiveOccupiedSlots());
            // Revoke newest leases first if physical capacity shrank, before any owner can redeem one.
            reconcileReservationsInternal(getSortedQueueInternal(), freeSlots, now);

            QueuedPlayer existingPlayer = queueMap.get(uuid);
            if (existingPlayer != null && existingPlayer.getIpAddress().isEmpty()) {
                String restoredIp = ipAddress == null || ipAddress.isBlank() ? "unknown" : ipAddress;
                if (config.isAntiBotEnabled() && countQueuedFromIp(restoredIp) >= config.getAntiBotMaxQueuedPerIp())
                    return new LoginDecision(LoginResultType.QUEUE_IP_QUOTA, null);
                existingPlayer.bindRestoredAddress(restoredIp);
            }

            if (config.isAntiBotEnabled() && existingPlayer == null
                    && config.getAntiBotMaxQueuedPerIp() > 0
                    && countQueuedFromIp(ipAddress) >= config.getAntiBotMaxQueuedPerIp()) {
                return new LoginDecision(LoginResultType.QUEUE_IP_QUOTA, null);
            }

            // 4. Kiểm tra xem người chơi có Reservation hợp lệ không
            if (existingPlayer != null && existingPlayer.hasActiveReservation(now) && freeSlots > 0) {
                // Người chơi đang giữ slot hợp lệ và kết nối lại -> Cho phép vào!
                queueMap.remove(uuid);
                queueOrderDirty = true;
                LoginDecision decision = admit(uuid, name, ipAddress, now, rights.priorityWeight(), LoginResultType.ALLOWED_RESERVED, existingPlayer.getFirstJoinedTimestamp());
                if (config.isDebugLog() && plugin != null) {
                    plugin.getLogger().info("[Queue] Người chơi " + name + " đã kích hoạt slot giữ chỗ thành công và vào game.");
                }
                return decision;
            }

            if (paused) {
                if (existingPlayer != null) existingPlayer.recordPing(now);
                return new LoginDecision(LoginResultType.PAUSED, null);
            }

            // 5. Nếu server còn slot trống VÀ hàng chờ đang trống: Cho phép vào thẳng
            if (freeSlots > 0 && queueMap.isEmpty()) {
                return admit(uuid, name, ipAddress, now, rights.priorityWeight(), LoginResultType.ALLOWED_DIRECT);
            }

            // 6. Trường hợp server đã đầy hoặc có hàng chờ:
            // Cập nhật hoặc thêm người chơi vào hàng chờ
            int priority = Math.max(0, rights.priorityWeight());
            QueuedPlayer queuedPlayer;

            if (existingPlayer != null) {
                queuedPlayer = existingPlayer;
                queuedPlayer.recordPing(now); // Cập nhật thời gian Heartbeat (5 phút tính từ thời điểm này)
                if (queuedPlayer.getPriority() != priority) {
                    queuedPlayer.setPriority(priority);
                    queueOrderDirty = true;
                }
            } else {
                // Kiểm tra giới hạn số lượng tối đa của hàng chờ
                if (config.getMaxQueueSize() > 0 && queueMap.size() >= config.getMaxQueueSize()) {
                    return new LoginDecision(LoginResultType.QUEUE_FULL, null);
                }
                queuedPlayer = new QueuedPlayer(uuid, name, ipAddress, priority, clock);
                queueMap.put(uuid, queuedPlayer);
                queueOrderDirty = true;
                if (config.isDebugLog() && plugin != null) {
                    plugin.getLogger().info("[Queue] Thêm người chơi " + name + " vào hàng chờ (Priority: " + priority + ")");
                }
            }

            // 7. Cập nhật phân phối Reservation cho những người đứng đầu hàng chờ
            List<QueuedPlayer> sortedQueue = getSortedQueueInternal();
            processReservationsInternal(sortedQueue, freeSlots, now);

            // Kiểm tra lại: nếu sau khi phân phối, người chơi này vừa nhận được reservation ngay lúc này
            // và slot trống vẫn còn, cho phép vào ngay lập tức!
            if (queuedPlayer.hasActiveReservation(now) && (maxCap - getActiveOccupiedSlots()) > 0) {
                queueMap.remove(uuid);
                queueOrderDirty = true;
                return admit(uuid, name, ipAddress, now, rights.priorityWeight(), LoginResultType.ALLOWED_RESERVED, queuedPlayer.getFirstJoinedTimestamp());
            }

            // 8. Tính toán vị trí hiện tại trong hàng chờ
            int position = 1;
            for (int i = 0; i < sortedQueue.size(); i++) {
                if (sortedQueue.get(i).getUuid().equals(uuid)) {
                    position = i + 1;
                    break;
                }
            }

            long remainingSec = queuedPlayer.getRemainingKeepAliveMs(config.getKeepAliveMs(), now) / 1000L;
            QueuePosition pos = new QueuePosition(
                    position,
                    sortedQueue.size(),
                    remainingSec,
                    queuedPlayer.hasActiveReservation(now) && freeSlots > 0,
                    queuedPlayer.getPriority(), estimateWait(position, now)
            );

            return new LoginDecision(queuedPlayer.consumeReservationExpiredNotice()
                    ? LoginResultType.EXPIRED_AND_REQUEUED : LoginResultType.IN_QUEUE, pos);

        } finally {
            lock.writeLock().unlock();
        }
    }

    private LoginResultType acceptAntiBotAttempt(UUID uuid, String ipAddress, long now) {
        String ip = (ipAddress == null || ipAddress.isBlank()) ? "unknown" : ipAddress;
        // Config -> queue -> anti-bot: only an exact live UUID/IP lease gets global headroom.
        lock.readLock().lock();
        try {
            QueuedPlayer owner = queueMap.get(uuid);
            boolean leaseOwner = owner != null && owner.hasActiveReservation(now)
                    && Objects.equals(owner.getIpAddress(), ipAddress);
            synchronized (globalAttemptWindow) {
                long cutoff = now - config.getAntiBotWindowMs();
                while (!globalAttemptWindow.isEmpty() && globalAttemptWindow.peekFirst() <= cutoff) globalAttemptWindow.removeFirst();
                if (ipAttemptWindows.size() >= MAX_TRACKED_IDENTITIES && !ipAttemptWindows.containsKey(ip)) {
                    ipAttemptWindows.entrySet().removeIf(e -> e.getValue().isEmpty() || e.getValue().peekLast() <= cutoff);
                    if (ipAttemptWindows.size() >= MAX_TRACKED_IDENTITIES) return LoginResultType.RATE_LIMITED_IP;
                }
                ArrayDeque<Long> attempts = ipAttemptWindows.computeIfAbsent(ip, ignored -> new ArrayDeque<>());
                while (!attempts.isEmpty() && attempts.peekFirst() <= cutoff) attempts.removeFirst();
                // Reject one noisy IP before charging global capacity shared with other IPs.
                if (attempts.size() >= Math.min(MAX_TRACKED_IDENTITIES, config.getAntiBotMaxAttemptsPerIp()))
                    return LoginResultType.RATE_LIMITED_IP;
                while (!protectedAttemptWindow.isEmpty() && protectedAttemptWindow.peekFirst() <= cutoff) protectedAttemptWindow.removeFirst();
                ArrayDeque<Long> budget = leaseOwner ? protectedAttemptWindow : globalAttemptWindow;
                if (budget.size() >= Math.min(MAX_TRACKED_IDENTITIES, config.getAntiBotGlobalAttemptsPerWindow()))
                    return LoginResultType.RATE_LIMITED_GLOBAL;
                attempts.addLast(now);
                budget.addLast(now);
                return null;
            }
        } finally { lock.readLock().unlock(); }
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
    private void processReservationsInternal(int freeSlots, long now) {
        processReservationsInternal(getSortedQueueInternal(), freeSlots, now);
    }

    private int reconcileReservationsInternal(List<QueuedPlayer> sorted, int freeSlots, long now) {
        List<QueuedPlayer> holders = sorted.stream().filter(player -> player.hasActiveReservation(now))
                .sorted(Comparator.comparingLong(QueuedPlayer::getReservationId)).toList();
        for (int i = freeSlots; i < holders.size(); i++) {
            holders.get(i).clearReservation();
        }
        return Math.min(freeSlots, holders.size());
    }

    private void processReservationsInternal(List<QueuedPlayer> sorted, int freeSlots, long now) {
        int available = freeSlots - reconcileReservationsInternal(sorted, freeSlots, now);
        if (paused) return;
        for (QueuedPlayer player : sorted) {
            if (available == 0) break;
            if (!player.hasActiveReservation(now)) {
                player.reserveSlot(config.getSlotReservationMs(), now);
                available--;
            }
        }
    }

    /** Same deadline transitions for pre-login, cleanup and quit; called only under the write lock. */
    private void expireQueueEntries(long now) {
        // Sorted traversal preserves FIFO when several leases expire on the same tick.
        for (QueuedPlayer player : getSortedQueueInternal()) {
            if (!player.hasActiveReservation(now) && player.isKeepAliveExpired(config.getKeepAliveMs(), now)) {
                queueMap.remove(player.getUuid());
                heartbeatExpired.incrementAndGet();
                queueOrderDirty = true;
            } else if (player.isReservationExpired(now)) {
                reservationExpired.incrementAndGet();
                player.requeueAfterExpiredReservation();
                queueOrderDirty = true;
            }
        }
    }

    /**
     * Lấy danh sách hàng chờ đã sắp xếp theo Priority và Thời gian vào hàng (yêu cầu gọi bên trong lock).
     */
    private List<QueuedPlayer> getSortedQueueInternal() {
        if (!lock.isWriteLockedByCurrentThread()) throw new IllegalStateException("Queue write lock required");
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
        long now = clock.millis();
        synchronized (rateLimitMap) {
            rateLimitMap.entrySet().removeIf(entry -> elapsed(now, entry.getValue()) >= Math.max(60_000L, config.getRateLimitMs()));
        }
        synchronized (globalAttemptWindow) {
            long cutoff = now - config.getAntiBotWindowMs();
            while (!globalAttemptWindow.isEmpty() && globalAttemptWindow.peekFirst() <= cutoff) globalAttemptWindow.removeFirst();
            while (!protectedAttemptWindow.isEmpty() && protectedAttemptWindow.peekFirst() <= cutoff) protectedAttemptWindow.removeFirst();
            ipAttemptWindows.values().forEach(attempts -> attempts.removeIf(timestamp -> timestamp <= cutoff));
            ipAttemptWindows.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }

        lock.writeLock().lock();
        try {
            long stateNow = clock.millis();
            cleanInFlightLogins(stateNow);
            expireQueueEntries(stateNow);

            // 2. Phân phối lại slot còn trống cho những người đứng đầu
            int maxCap = getEffectiveMaxPlayers();
            int occupied = getActiveOccupiedSlots();
            int freeSlots = Math.max(0, maxCap - occupied);
            processReservationsInternal(freeSlots, stateNow);

        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Bind the granted attempt to this exact Player identity before overriding KICK_FULL. */
    public LoginPermit beginLogin(UUID uuid, String ip, Object playerIdentity) {
        lock.writeLock().lock();
        try {
            cleanInFlightLogins(clock.millis());
            LoginAttempt attempt = inFlightLogins.get(uuid);
            if (attempt == null || !Objects.equals(ip, attempt.ipAddress())
                    || (attempt.playerIdentity() != null && attempt.playerIdentity() != playerIdentity)
                    || !attempt.occupiesSlot()) return new LoginPermit(null, false, false, false);
            if (attempt.phase() != LoginAttempt.Phase.LOGIN_ACCEPTED) {
                attempt = attempt.transition(attempt.phase(), playerIdentity);
                inFlightLogins.put(uuid, attempt);
            }
            return new LoginPermit(attempt.id(), attempt.phase() != LoginAttempt.Phase.TIMED_OUT,
                    attempt.phase() == LoginAttempt.Phase.TIMED_OUT, attempt.fullOverrideGranted());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void completeLogin(UUID uuid, UUID token, boolean allowed) {
        lock.writeLock().lock();
        try {
            LoginAttempt attempt = inFlightLogins.get(uuid);
            if (attempt == null || !attempt.id().equals(token) || !attempt.occupiesSlot()) return;
            allowed = allowed && attempt.phase() != LoginAttempt.Phase.TIMED_OUT
                    && attempt.playerIdentity() != null;
            LoginAttempt next = attempt.transition(allowed ? LoginAttempt.Phase.LOGIN_ACCEPTED
                    : LoginAttempt.Phase.FAILED, attempt.playerIdentity());
            inFlightLogins.put(uuid, next);
            if (!allowed) { failedLogins.incrementAndGet(); reservedAdmissions.remove(uuid); }
            trace(next, allowed ? "login-accepted" : "login-failed");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Only cancelled pre-login events omit Paper's close event; their exact token can be removed now. */
    public boolean cancelPreLogin(UUID uuid, UUID token) {
        lock.writeLock().lock();
        try {
            LoginAttempt attempt = inFlightLogins.get(uuid);
            boolean removed = attempt != null && attempt.id().equals(token) && attempt.playerIdentity() == null
                    && inFlightLogins.remove(uuid, attempt);
            if (removed) { cancelledPreLogins.incrementAndGet(); reservedAdmissions.remove(uuid); admissionNames.remove(uuid); trace(attempt, "pre-login-cancelled"); }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void onPlayerJoin(Player player) {
        lock.writeLock().lock();
        try {
            refreshServerCapacity();
            LoginAttempt attempt = inFlightLogins.get(player.getUniqueId());
            if (attempt == null || attempt.playerIdentity() != player
                    || attempt.phase() != LoginAttempt.Phase.LOGIN_ACCEPTED) return;
            inFlightLogins.put(player.getUniqueId(), attempt.transition(LoginAttempt.Phase.JOINED, player));
            successfulJoins.incrementAndGet();
            if (reservedAdmissions.remove(player.getUniqueId())) {
                history.record(clock.millis());
                var identity = admissionNames.get(player.getUniqueId());
                double waited = elapsed(clock.millis(), identity.waitingSince()) / 1000.0;
                double[] bounds = {30, 60, 300, 900, Double.POSITIVE_INFINITY};
                for (int i = 0; i < bounds.length; i++) if (waited <= bounds[i]) waitBuckets[i]++;
                waitSecondsSum += waited;
            }
            trace(inFlightLogins.get(player.getUniqueId()), "join");
            queueMap.remove(player.getUniqueId());
            queueOrderDirty = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void onPlayerQuit(Player player) {
        synchronized (config) {
            lock.writeLock().lock();
            try {
                LoginAttempt attempt = inFlightLogins.get(player.getUniqueId());
                if (attempt != null && (attempt.playerIdentity() != player
                        || attempt.phase() != LoginAttempt.Phase.JOINED)) return;
                onlinePlayers.updateAndGet(current -> Math.max(0, current - 1));
                if (attempt != null) inFlightLogins.put(player.getUniqueId(),
                        attempt.transition(LoginAttempt.Phase.FAILED, player));
                if (attempt != null) trace(inFlightLogins.get(player.getUniqueId()), "quit");
                long now = clock.millis();
                expireQueueEntries(now);
                processReservationsInternal(Math.max(0, getEffectiveMaxPlayers() - getActiveOccupiedSlots()), now);
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    /** One outstanding connection per UUID makes Paper's UUID/IP-only close event unambiguous. */
    public void onConnectionClose(UUID uuid, String ip) {
        lock.writeLock().lock();
        try {
            LoginAttempt attempt = inFlightLogins.get(uuid);
            if (attempt != null && Objects.equals(ip, attempt.ipAddress())) {
                inFlightLogins.remove(uuid, attempt);
                reservedAdmissions.remove(uuid);
                admissionNames.remove(uuid);
                trace(attempt, "connection-close");
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public UUID getAttemptId(UUID uuid) {
        LoginAttempt attempt = inFlightLogins.get(uuid);
        return attempt == null ? null : attempt.id();
    }

    public LoginAttempt getAttempt(UUID uuid) { return inFlightLogins.get(uuid); }

    public boolean isInFlight(UUID uuid) {
        LoginAttempt attempt = uuid == null ? null : inFlightLogins.get(uuid);
        return attempt != null && attempt.occupiesSlot();
    }

    /** Failure frees capacity, but keeps connection ownership until its close event arrives. */
    public boolean releaseInFlight(UUID uuid, UUID token) {
        lock.writeLock().lock();
        try {
            LoginAttempt attempt = inFlightLogins.get(uuid);
            if (attempt == null || !attempt.id().equals(token) || !attempt.occupiesSlot()) return false;
            inFlightLogins.put(uuid, attempt.transition(LoginAttempt.Phase.FAILED, attempt.playerIdentity()));
            failedLogins.incrementAndGet(); reservedAdmissions.remove(uuid);
            trace(inFlightLogins.get(uuid), "released-failure");
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int getInFlightCount() {
        return (int) inFlightLogins.values().stream().filter(LoginAttempt::occupiesSlot).count();
    }

    public int getTimedOutInFlightCount() {
        return (int) inFlightLogins.values().stream().filter(a -> a.occupiesSlot() && a.timeoutObserved()).count();
    }

    public long getInFlightTimeoutCount() { return timeoutCount.get(); }
    public int getTrackedConnectionCount() { return inFlightLogins.size(); }
    public boolean isEnabled() { return config.isEnabled(); }

    private void trace(LoginAttempt attempt, String action) {
        if (plugin != null && config.snapshot().debugLog()) {
            plugin.getLogger().info("[Queue] attempt=" + attempt.id() + " uuid=" + attempt.playerUuid()
                    + " action=" + action + " phase=" + attempt.phase()
                    + " elapsedMs=" + elapsed(clock.millis(), attempt.startedAt()));
        }
    }

    /** Records only disconnect reasons produced by this plugin, never another plugin's ban/error. */
    public void recordQueueDisconnect(String name, String ip, String reason) {
        synchronized (queueDisconnects) {
            long now = clock.millis();
            queueDisconnects.removeIf(item -> elapsed(now, item.recordedAt()) >= 5_000L);
            if (queueDisconnects.size() >= MAX_TRACKED_IDENTITIES) queueDisconnects.removeFirst();
            queueDisconnects.addLast(new QueueDisconnect(name, ip, reason, now));
        }
    }

    public boolean matchesQueueDisconnect(String name, String ip, String reason) {
        synchronized (queueDisconnects) {
            long now = clock.millis();
            queueDisconnects.removeIf(item -> elapsed(now, item.recordedAt()) >= 5_000L);
            return queueDisconnects.stream().anyMatch(item -> Objects.equals(name, item.name())
                    && Objects.equals(ip, item.ip()) && Objects.equals(reason, item.reason()));
        }
    }

    private AdmissionHistory.Snapshot cachedHistory(long now) {
        if (historyCachedAt == Long.MIN_VALUE || now < historyCachedAt || now - historyCachedAt >= 1000) {
            historyCache = history.snapshot(now); historyCachedAt = now;
        }
        return historyCache;
    }

    private String estimateWait(int position, long now) {
        double interval = cachedHistory(now).secondsPerJoin();
        if (paused || interval <= 0 || !Double.isFinite(interval)) return "Đang cập nhật";
        // Rank already includes queued leases; redeemed but unfinished admissions are additional work.
        int work = position + reservedAdmissions.size();
        long low = (long) Math.ceil(work * interval * .5);
        long high = (long) Math.ceil(work * interval * 1.5);
        return "Ước tính " + low + "–" + high + " giây";
    }

    public record Statistics(Map<LoginResultType, Long> decisions, long successfulJoins, long timeouts, long failedLogins, long cancelledPreLogins,
                             int online, int capacity, int queued, int leases, int inFlight, boolean paused,
                             long joins5m, long joins15m, double averagePreLoginMs, double maxPreLoginMs,
                             int trackedUuids, int trackedIps, long reservationExpired, long heartbeatExpired,
                             long permissionFailures, List<Long> waitBuckets, double waitSecondsSum) {}

    public Statistics getStatistics() {
        synchronized (config) {
            lock.writeLock().lock();
            try {
                var counts = new EnumMap<LoginResultType, Long>(LoginResultType.class);
                decisions.forEach((type, count) -> counts.put(type, count.sum()));
                var recent = cachedHistory(clock.millis());
                int uuids, ips;
                synchronized (rateLimitMap) { uuids = rateLimitMap.size(); }
                synchronized (globalAttemptWindow) { ips = ipAttemptWindows.size(); }
                long calls = preLoginCalls.sum();
                return new Statistics(Map.copyOf(counts), successfulJoins.get(), timeoutCount.get(), failedLogins.get(), cancelledPreLogins.get(), onlinePlayers.get(),
                        getEffectiveMaxPlayers(), queueMap.size(), (int)queueMap.values().stream()
                        .filter(p -> p.hasActiveReservation(clock.millis())).count(), getInFlightCount(), paused,
                        recent.joins5m(), recent.joins15m(), calls == 0 ? 0 : preLoginNanos.sum() / 1e6 / calls,
                        maxPreLoginNanos.get() / 1e6, uuids, ips, reservationExpired.get(), heartbeatExpired.get(),
                        permissionFailures.get(), Arrays.stream(waitBuckets).boxed().toList(), waitSecondsSum);
            } finally { lock.writeLock().unlock(); }
        }
    }

    /** Plain Prometheus text, generated after the bounded snapshot releases the queue lock. No identity labels. */
    public String getMetricsText() {
        Statistics stats = getStatistics();
        StringBuilder output = new StringBuilder();
        stats.decisions().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> output.append(
                "standalone_queue_decisions_total{reason=\"").append(e.getKey()).append("\"} ").append(e.getValue()).append('\n'));
        output.append("standalone_queue_successful_joins_total ").append(stats.successfulJoins()).append('\n');
        output.append("standalone_queue_timeouts_total ").append(stats.timeouts()).append('\n');
        output.append("standalone_queue_failed_logins_total ").append(stats.failedLogins()).append('\n');
        output.append("standalone_queue_cancelled_prelogins_total ").append(stats.cancelledPreLogins()).append('\n');
        output.append("standalone_queue_reservation_expired_total ").append(stats.reservationExpired()).append('\n');
        output.append("standalone_queue_heartbeat_expired_total ").append(stats.heartbeatExpired()).append('\n');
        output.append("standalone_queue_permission_failures_total ").append(stats.permissionFailures()).append('\n');
        String[] bounds = {"30", "60", "300", "900", "+Inf"};
        for (int i = 0; i < bounds.length; i++) output.append("standalone_queue_wait_seconds_bucket{le=\"")
                .append(bounds[i]).append("\"} ").append(stats.waitBuckets().get(i)).append('\n');
        output.append("standalone_queue_wait_seconds_count ").append(stats.waitBuckets().getLast()).append('\n');
        output.append("standalone_queue_wait_seconds_sum ").append(stats.waitSecondsSum()).append('\n');
        output.append("standalone_queue_online ").append(stats.online()).append('\n');
        output.append("standalone_queue_capacity ").append(stats.capacity()).append('\n');
        output.append("standalone_queue_waiting ").append(stats.queued()).append('\n');
        output.append("standalone_queue_leases ").append(stats.leases()).append('\n');
        output.append("standalone_queue_in_flight ").append(stats.inFlight()).append('\n');
        output.append("standalone_queue_paused ").append(stats.paused() ? 1 : 0).append('\n');
        output.append("standalone_queue_joins_5m ").append(stats.joins5m()).append('\n');
        output.append("standalone_queue_joins_15m ").append(stats.joins15m()).append('\n');
        output.append("standalone_queue_prelogin_average_ms ").append(stats.averagePreLoginMs()).append('\n');
        output.append("standalone_queue_prelogin_max_ms ").append(stats.maxPreLoginMs()).append('\n');
        return output.toString();
    }

    public boolean isPaused() { return paused; }
    public void setPaused(boolean value) {
        synchronized (config) {
            lock.writeLock().lock();
            try {
                paused = value;
                long now = clock.millis();
                expireQueueEntries(now);
                processReservationsInternal(Math.max(0, getEffectiveMaxPlayers() - getActiveOccupiedSlots()), now);
            } finally { lock.writeLock().unlock(); }
        }
    }

    public record Inspection(UUID uuid, String name, int priority, int position, long ttlSeconds,
                             long leaseId, long leaseRemainingSeconds, String phase) {}
    public Inspection inspect(String name) {
        synchronized (config) {
            lock.writeLock().lock();
            try {
                var sorted = getSortedQueueInternal(); long now = clock.millis();
                for (int i = 0; i < sorted.size(); i++) {
                    var p = sorted.get(i);
                    if (p.getUsername().equalsIgnoreCase(name) || p.getUuid().toString().equalsIgnoreCase(name)) {
                        var attempt = inFlightLogins.get(p.getUuid());
                        return new Inspection(p.getUuid(), p.getUsername(), p.getPriority(), i + 1,
                                p.getRemainingKeepAliveMs(config.getKeepAliveMs(), now) / 1000,
                                p.hasActiveReservation(now) ? p.getReservationId() : 0,
                                Math.max(0, p.getSlotReservedUntilTimestamp() - now) / 1000,
                                attempt == null ? "WAITING" : attempt.phase().name());
                    }
                }
                for (var attempt : inFlightLogins.values()) {
                    var identity = admissionNames.get(attempt.playerUuid());
                    String playerName = identity == null ? "" : identity.name();
                    if (playerName.equalsIgnoreCase(name) || attempt.playerUuid().toString().equalsIgnoreCase(name))
                        return new Inspection(attempt.playerUuid(), playerName, identity == null ? 0 : identity.priority(), 0, 0, 0, 0, attempt.phase().name());
                }
                return null;
            } finally { lock.writeLock().unlock(); }
        }
    }

    /** Absolute one-based position; never crosses a tier or any lease in the affected interval. */
    public boolean move(String name, int position) {
        lock.writeLock().lock();
        try {
            var sorted = new ArrayList<>(getSortedQueueInternal());
            int source = -1;
            for (int i = 0; i < sorted.size(); i++) if (sorted.get(i).getUsername().equalsIgnoreCase(name)) source = i;
            if (source < 0 || position < 1 || position > sorted.size()) return false;
            var player = sorted.get(source); int target = position - 1;
            for (int i = Math.min(source, target); i <= Math.max(source, target); i++) {
                var peer = sorted.get(i);
                if (peer.getPriority() != player.getPriority() || peer.getReservationId() != 0
                        || inFlightLogins.containsKey(peer.getUuid())) return false;
            }
            sorted.remove(source); sorted.add(target, player);
            // ponytail: O(n) admin-only; fresh monotonic sequences keep later new arrivals behind peers.
            for (var peer : sorted) peer.refreshSequence();
            queueOrderDirty = true;
            return true;
        } finally { lock.writeLock().unlock(); }
    }

    public void audit(String actor, String action, String details) {
        var logger = plugin == null ? java.util.logging.Logger.getLogger("StandaloneQueue") : plugin.getLogger();
        logger.info("[QueueAudit] actor=" + String.valueOf(actor).replaceAll("[\\r\\n]", "_") + " action=" + action
                + " details=" + details.replaceAll("[\\r\\n]", "_"));
    }

    private record ReloadTarget(UUID uuid, String name, QueuedPlayer identity) {}

    /** Reserve at command invocation, before asynchronous file parsing can reorder requests. */
    public long beginReloadRequest() {
        synchronized (config) { return reloadRequest.incrementAndGet(); }
    }

    /** Prepare provider I/O off-thread; commit settings, ordering and leases as one queue transaction. */
    public CompletableFuture<Void> reload(QueueSettings candidate) {
        return reload(candidate, beginReloadRequest());
    }

    public CompletableFuture<Void> reload(QueueSettings candidate, long request) {
        Objects.requireNonNull(candidate, "candidate");
        QueueSettings previous;
        List<ReloadTarget> targets;
        synchronized (config) {
            lock.readLock().lock();
            try {
                if (stopped || recovering) return CompletableFuture.failedFuture(new IllegalStateException("Plugin đã dừng hoặc đang phục hồi"));
                if (request != reloadRequest.get()) return CompletableFuture.failedFuture(new IllegalStateException("Reload đã bị thay thế; hãy thử lại"));
                previous = config.snapshot();
                targets = queueMap.values().stream()
                        .map(player -> new ReloadTarget(player.getUuid(), player.getUsername(), player)).toList();
            } finally {
                lock.readLock().unlock();
            }
        }
        return CompletableFuture.supplyAsync(() -> prepareReload(targets, candidate)).thenAccept(priorities -> {
            synchronized (config) {
                lock.writeLock().lock();
                try {
                    if (stopped || request != reloadRequest.get() || config.snapshot() != previous) {
                        throw new IllegalStateException("Reload đã bị thay thế; hãy thử lại");
                    }
                    if (queueMap.size() != targets.size() || targets.stream()
                            .anyMatch(target -> queueMap.get(target.uuid()) != target.identity())) {
                        throw new IllegalStateException("Hàng chờ đã thay đổi trong lúc reload; hãy thử lại");
                    }
                    long now = clock.millis();
                    if (priorities.values().stream().anyMatch(rights -> !rights.isFresh(now))) {
                        throw new IllegalStateException("Kết quả permission đã cũ; hãy thử lại");
                    }
                    if (candidate.persistenceEnabled() != previous.persistenceEnabled()
                            || candidate.persistenceFlushSeconds() != previous.persistenceFlushSeconds()) {
                        throw new IllegalArgumentException("Thay đổi persistence yêu cầu restart server");
                    }
                    config.publish(candidate); // All checks finished; no provider calls after publication.
                    targets.forEach(target -> target.identity().setPriority(Math.max(0, priorities.get(target.uuid()).priorityWeight())));
                    queueOrderDirty = true;
                    expireQueueEntries(now);
                    processReservationsInternal(Math.max(0, getEffectiveMaxPlayers() - getActiveOccupiedSlots()), now);
                    synchronized (rateLimitMap) { rateLimitMap.clear(); }
                    synchronized (globalAttemptWindow) {
                        globalAttemptWindow.clear();
                        protectedAttemptWindow.clear();
                        ipAttemptWindows.clear();
                    }
                } finally {
                    lock.writeLock().unlock();
                }
            }
        });
    }

    private Map<UUID, PermissionSnapshot> prepareReload(List<ReloadTarget> targets, QueueSettings candidate) {
        Map<UUID, PermissionSnapshot> prepared = new HashMap<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(candidate.permissionResolveTimeoutMs());
        // ponytail: at most 32 concurrent storage loads; the entire refresh has a bounded timeout.
        for (int offset = 0; offset < targets.size(); offset += 32) {
            List<ReloadTarget> batch = targets.subList(offset, Math.min(offset + 32, targets.size()));
            List<CompletableFuture<PermissionSnapshot>> futures = batch.stream().map(target ->
                    permissionResolver.resolve(target.uuid(), target.name(), candidate.permissionPriorities()).toCompletableFuture()).toList();
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new java.util.concurrent.TimeoutException("Permission refresh timeout");
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(remaining, TimeUnit.NANOSECONDS);
                for (int i = 0; i < batch.size(); i++) {
                    PermissionSnapshot rights = futures.get(i).join();
                    if (rights == null || !rights.isFresh(clock.millis()) || "unverified".equals(rights.sourceVersion())) {
                        throw new IllegalStateException("Không xác minh được permission cho " + batch.get(i).name());
                    }
                    prepared.put(batch.get(i).uuid(), rights);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CompletionException(error);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }
        return prepared;
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
            long now = clock.millis();
            // ponytail: O(n log n) per admin list (bounded queue); publish immutable DTOs if polling grows.
            return queueMap.values().stream().map(player -> player.snapshot(now)).sorted().toList();
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
            long now = clock.millis();
            for (QueuedPlayer qp : queueMap.values()) {
                if (qp.hasActiveReservation(now)) {
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

    /** Called before listeners register. Recovery gates admission until its async transaction finishes. */
    public void beginRecovery() { recovering = true; }
    public void finishRecovery() { recovering = false; }

    public List<com.mineplugin.queue.storage.QueueEntry> persistenceSnapshot() {
        lock.readLock().lock();
        try {
            long now = clock.millis();
            return queueMap.values().stream().filter(p -> !p.isKeepAliveExpired(Math.min(config.snapshot().keepAliveSeconds(), Long.MAX_VALUE / 1000L) * 1000L, now))
                    .sorted().map(QueuedPlayer::durableEntry).toList();
        } finally { lock.readLock().unlock(); }
    }

    /** Stop new admissions/reloads before capturing the final state; disk work remains outside locks. */
    public List<com.mineplugin.queue.storage.QueueEntry> stopAndSnapshot() {
        lock.writeLock().lock();
        try { stopped = true; reloadRequest.incrementAndGet(); return persistenceSnapshot(); }
        finally { lock.writeLock().unlock(); }
    }

    /** Startup-only: resolve current rights outside the state lock, then publish waiting entries together. */
    public int restoreEntries(List<com.mineplugin.queue.storage.QueueEntry> entries) {
        QueueSettings settings = config.snapshot();
        long restoreAt = clock.millis();
        List<ReloadTarget> targets = entries.stream()
                .filter(e -> e.firstJoinedAt() <= restoreAt && e.lastPingAt() <= restoreAt
                        && restoreAt - e.lastPingAt() < Math.min(settings.keepAliveSeconds(), Long.MAX_VALUE / 1000L) * 1000L)
                .sorted(Comparator.comparingLong(com.mineplugin.queue.storage.QueueEntry::sequence))
                .limit(settings.maxQueueSize())
                .map(e -> new ReloadTarget(e.uuid(), e.name(), QueuedPlayer.restore(e, 0, clock))).toList();
        Map<UUID, PermissionSnapshot> rights = prepareReload(targets, settings);
        synchronized (config) {
            lock.writeLock().lock();
            try {
                if (stopped || !recovering || !queueMap.isEmpty() || config.snapshot() != settings)
                    throw new IllegalStateException("Recovery state changed");
                long now = clock.millis();
                for (var target : targets) {
                    var player = target.identity();
                    var permission = rights.get(target.uuid());
                    if (!permission.isFresh(now)) throw new IllegalStateException("Recovery permission expired");
                    player.setPriority(Math.max(0, permission.priorityWeight()));
                }
                for (var target : targets) if (!target.identity().isKeepAliveExpired(config.getKeepAliveMs(), now))
                    queueMap.put(target.uuid(), target.identity());
                queueOrderDirty = true;
                // Startup uses the main-thread capacity snapshot; old leases/in-flight are never restored.
                processReservationsInternal(Math.max(0, getEffectiveMaxPlayers() - getActiveOccupiedSlots()), now);
                return queueMap.size();
            } finally { lock.writeLock().unlock(); }
        }
    }

    public void clearRuntimeState() {
        lock.writeLock().lock();
        try {
            stopped = true;
            reloadRequest.incrementAndGet();
            queueMap.clear();
            queueOrderDirty = true;
            inFlightLogins.clear();
            reservedAdmissions.clear();
            admissionNames.clear();
            synchronized (queueDisconnects) { queueDisconnects.clear(); }
            synchronized (rateLimitMap) {
                rateLimitMap.clear();
            }
            synchronized (globalAttemptWindow) {
                globalAttemptWindow.clear();
                protectedAttemptWindow.clear();
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
