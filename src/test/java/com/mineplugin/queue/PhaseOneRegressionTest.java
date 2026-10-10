package com.mineplugin.queue;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.QueueManager;
import com.mineplugin.queue.model.QueuedPlayer;
import com.mineplugin.queue.permission.OperatorPermissionResolver;
import com.mineplugin.queue.permission.PermissionSnapshot;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.StringReader;
import java.util.*;
import java.util.concurrent.*;

import static com.mineplugin.queue.manager.QueueManager.LoginResultType.*;
import static org.junit.jupiter.api.Assertions.*;

class PhaseOneRegressionTest {
    private final MutableClock clock = new MutableClock();
    private final FakePermissionResolver permissions = new FakePermissionResolver(clock);
    private final QueueConfig config = new QueueConfig(settings(1));
    private final QueueManager manager = new QueueManager(null, config, permissions, clock);

    private static YamlConfiguration settings(int capacity) {
        return YamlConfiguration.loadConfiguration(new StringReader("""
                queue:
                  max-players-override: %d
                  keep-alive-seconds: 5
                  slot-reservation-seconds: 1
                  rate-limit-seconds: 0
                  max-queue-size: 1000
                permissions:
                  resolve-timeout-ms: 30
                """.formatted(capacity)));
    }

    private UUID enqueue(String name) {
        UUID uuid = UUID.randomUUID();
        attempt(uuid, name);
        return uuid;
    }

    private QueueManager.LoginDecision attempt(UUID uuid, String name) {
        return manager.handlePreLogin(uuid, name, "192.0.2.1");
    }

    private QueuedPlayer entry(UUID uuid) {
        return manager.getQueueList().stream().filter(player -> player.getUuid().equals(uuid)).findFirst().orElseThrow();
    }

    @Test
    void firstConnectionResolvesVipAndAdminAndOfflineRevokeTakesEffect() {
        enqueue("Occupant");
        enqueue("Normal");
        UUID vip = UUID.randomUUID();
        permissions.set(vip, false, 20);
        assertEquals(1, attempt(vip, "NewVip").position().position());
        assertEquals(20, entry(vip).getPriority());
        UUID admin = UUID.randomUUID();
        permissions.set(admin, true, 0);
        assertEquals(ALLOWED_BYPASS, attempt(admin, "NewAdmin").type());
        manager.releaseInFlight(admin);
        permissions.set(admin, false, 0);
        assertEquals(IN_QUEUE, attempt(admin, "RevokedAdmin").type());
    }

    @Test
    void newVipCannotStealExistingLease() {
        UUID occupant = enqueue("Occupant");
        UUID owner = enqueue("Owner");
        manager.releaseInFlight(occupant);
        manager.cleanupTick();
        long lease = entry(owner).getReservationId();
        UUID vip = UUID.randomUUID();
        permissions.set(vip, false, 100);
        assertEquals(IN_QUEUE, attempt(vip, "NewVip").type());
        assertEquals(lease, entry(owner).getReservationId());
        assertEquals(ALLOWED_RESERVED, attempt(owner, "Owner").type());
        assertEquals(1, manager.getInFlightCount());
    }

    @ParameterizedTest
    @CsvSource({"999,false", "1000,false", "1001,false", "999,true", "1000,true", "1001,true"})
    void reservationDeadlineAgreesWithOrWithoutCleanup(long elapsed, boolean cleanupBefore) {
        UUID occupant = enqueue("Occupant");
        UUID first = enqueue("First");
        UUID second = enqueue("Second");
        manager.releaseInFlight(occupant);
        manager.cleanupTick();
        clock.advance(elapsed);
        if (cleanupBefore) manager.cleanupTick();
        assertEquals(elapsed < 1000 ? ALLOWED_RESERVED : IN_QUEUE, attempt(first, "First").type());
        if (elapsed >= 1000) {
            assertEquals(second, manager.getQueueList().getFirst().getUuid());
            assertTrue(entry(second).hasActiveReservation());
            long lease = entry(second).getReservationId();
            manager.cleanupTick();
            assertEquals(lease, entry(second).getReservationId());
        }
    }

    @ParameterizedTest
    @CsvSource({"4999,false", "5000,false", "5001,false", "4999,true", "5000,true", "5001,true"})
    void heartbeatIsCheckedBeforePingEvenWithoutCleanup(long elapsed, boolean cleanupBefore) {
        enqueue("Occupant");
        UUID first = enqueue("First");
        clock.advance(1000);
        UUID second = enqueue("Second");
        clock.advance(elapsed - 1000);
        if (cleanupBefore) manager.cleanupTick();
        assertEquals(elapsed < 5000 ? 1 : 2, attempt(first, "First").position().position());
        assertEquals(elapsed < 5000 ? first : second, manager.getQueueList().getFirst().getUuid());
    }

    @Test
    void liveLeaseSurvivesHeartbeatDeadline() {
        var yaml = settings(1);
        yaml.set("queue.keep-alive-seconds", 1);
        yaml.set("queue.slot-reservation-seconds", 2);
        config.load(yaml);
        UUID occupant = enqueue("Occupant");
        UUID owner = enqueue("Owner");
        manager.releaseInFlight(occupant);
        manager.cleanupTick();
        clock.advance(1000);
        manager.cleanupTick();
        assertEquals(ALLOWED_RESERVED, attempt(owner, "Owner").type());
    }

    @Test
    void capacityShrinkRevokesNewestLeaseBeforeItsOwnerReconnects() {
        config.load(settings(2));
        UUID one = enqueue("Occupant1");
        UUID two = enqueue("Occupant2");
        UUID oldest = enqueue("Oldest");
        UUID newest = enqueue("Newest");
        manager.releaseInFlight(one);
        manager.releaseInFlight(two);
        manager.cleanupTick();
        assertEquals(2, manager.getReservedSlotCount());
        config.load(settings(1));
        assertEquals(IN_QUEUE, attempt(newest, "Newest").type());
        assertTrue(entry(oldest).hasActiveReservation());
        assertFalse(entry(newest).hasActiveReservation());
        assertEquals(ALLOWED_RESERVED, attempt(oldest, "Oldest").type());
    }

    @Test
    void simultaneousExpirationsPreserveFifo() {
        config.load(settings(2));
        UUID one = enqueue("Occupant1");
        UUID two = enqueue("Occupant2");
        UUID a = enqueue("A");
        UUID b = enqueue("B");
        UUID c = enqueue("C");
        UUID d = enqueue("D");
        manager.releaseInFlight(one);
        manager.releaseInFlight(two);
        manager.cleanupTick();
        clock.advance(1000);
        manager.cleanupTick();
        assertEquals(List.of(c, d, a, b), manager.getQueueList().stream().map(QueuedPlayer::getUuid).toList());
    }

    @Test
    void queueSnapshotsAreDetachedAndListIsUnmodifiable() {
        enqueue("Occupant");
        UUID a = enqueue("A");
        List<QueuedPlayer> snapshot = manager.getQueueList();
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        snapshot.getFirst().setPriority(999);
        snapshot.getFirst().reserveSlot(10_000);
        assertEquals(0, entry(a).getPriority());
        assertFalse(entry(a).hasActiveReservation());
        clock.advance(1000);
        attempt(a, "A");
        assertNotEquals(snapshot.getFirst().getLastPingTimestamp(), entry(a).getLastPingTimestamp());
    }

    @Test
    void concurrentLeasesAndDuplicateUuidNeverDoubleAdmit() throws Exception {
        config.load(settings(2));
        UUID one = enqueue("Occupant1");
        UUID two = enqueue("Occupant2");
        UUID a = enqueue("A");
        UUID b = enqueue("B");
        UUID c = enqueue("C");
        manager.releaseInFlight(one);
        manager.releaseInFlight(two);
        manager.cleanupTick();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<QueueManager.LoginDecision>> attempts = new ArrayList<>();
            for (UUID uuid : List.of(a, a, b, c)) {
                attempts.add(pool.submit(() -> { start.await(); return attempt(uuid, uuid.toString()); }));
            }
            start.countDown();
            int admitted = 0;
            for (var future : attempts) if (future.get(5, TimeUnit.SECONDS).type() == ALLOWED_RESERVED) admitted++;
            assertEquals(2, admitted);
            assertEquals(2, manager.getInFlightCount());
            assertEquals(0, manager.getReservedSlotCount());
        }
    }

    @Test
    void fiveHundredWaitingPlayersWithFiftyConcurrentReadersAndWriters() throws Exception {
        config.load(settings(5));
        List<UUID> occupants = new ArrayList<>();
        for (int i = 0; i < 5; i++) occupants.add(enqueue("Occupant" + i));
        List<UUID> waiting = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            UUID uuid = UUID.randomUUID();
            permissions.set(uuid, false, i % 3 * 10);
            attempt(uuid, "Waiting" + i);
            waiting.add(uuid);
        }
        occupants.forEach(manager::releaseInFlight);
        manager.cleanupTick();
        try (ExecutorService pool = Executors.newFixedThreadPool(50)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                final int id = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < 10; j++) {
                        UUID uuid = waiting.get(id * 10 + j);
                        permissions.set(uuid, false, (id + j) % 4 * 10);
                        attempt(uuid, "Waiting" + id);
                        var view = manager.getQueueList();
                        for (int k = 1; k < view.size(); k++) assertTrue(view.get(k - 1).compareTo(view.get(k)) < 0);
                        assertEquals(view.size(), view.stream().map(QueuedPlayer::getUuid).distinct().count());
                        if (j % 3 == 0) manager.cleanupTick();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertEquals(5, manager.getInFlightCount());
        assertEquals(495, manager.getQueueSize());
    }

    @Test
    void slowResolverDoesNotHoldConfigMonitorOrQueueLock() throws Exception {
        UUID slow = UUID.randomUUID();
        CompletableFuture<PermissionSnapshot> pending = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        var cfg = settings(1);
        cfg.set("permissions.resolve-timeout-ms", 5000);
        config.load(cfg);
        QueueManager queue = new QueueManager(null, config, (uuid, name) -> {
            if (uuid.equals(slow)) { entered.countDown(); return pending; }
            return CompletableFuture.completedFuture(PermissionSnapshot.none(clock.millis()));
        }, clock);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> blocked = pool.submit(() -> queue.handlePreLogin(slow, "Slow", "192.0.2.1"));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            try {
                assertEquals(ALLOWED_DIRECT, pool.submit(() -> {
                    queue.cleanupTick();
                    return queue.handlePreLogin(UUID.randomUUID(), "Fast", "192.0.2.2").type();
                }).get(1, TimeUnit.SECONDS));
            } finally {
                pending.complete(PermissionSnapshot.none(clock.millis()));
            }
            blocked.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void providerTimeoutFailureAndStaleSnapshotNeverGrantBypass() {
        List<CompletableFuture<PermissionSnapshot>> responses = List.of(
                new CompletableFuture<>(), CompletableFuture.failedFuture(new IllegalStateException("storage offline")),
                CompletableFuture.completedFuture(new PermissionSnapshot(true, 100, clock.millis() - 2000, "stale")));
        for (var response : responses) {
            QueueManager queue = new QueueManager(null, config, (uuid, name) -> response, clock);
            queue.handlePreLogin(UUID.randomUUID(), "Occupant", "192.0.2.1");
            assertEquals(IN_QUEUE, queue.handlePreLogin(UUID.randomUUID(), "Unverified", "192.0.2.1").type());
            assertEquals(0, queue.getQueueList().getFirst().getPriority());
            response.complete(new PermissionSnapshot(true, 100, clock.millis(), "late"));
            assertEquals(1, queue.getInFlightCount());
            assertEquals(1, queue.getQueueSize());
        }
    }

    @Test
    void defaultResolverSupportsNewOnlineModeOperatorsAndExpiresSnapshots() {
        UUID op = UUID.randomUUID();
        OperatorPermissionResolver resolver = new OperatorPermissionResolver(clock);
        resolver.refresh(Set.of(op), true);
        QueueManager queue = new QueueManager(null, config, resolver, clock);
        queue.handlePreLogin(UUID.randomUUID(), "Occupant", "192.0.2.1");
        assertEquals(ALLOWED_BYPASS, queue.handlePreLogin(op, "NewOp", "192.0.2.1").type());
        queue.releaseInFlight(op);
        resolver.refresh(Set.of(), true);
        assertEquals(IN_QUEUE, queue.handlePreLogin(op, "Revoked", "192.0.2.1").type());
        resolver.refresh(Set.of(op), true);
        clock.advance(PermissionSnapshot.MAX_AGE_MS);
        assertFalse(resolver.resolve(op, "NewOp").join().bypass());
        resolver.refresh(Set.of(op), false);
        assertFalse(resolver.resolve(op, "ImpersonatedName").join().bypass());
    }
}
