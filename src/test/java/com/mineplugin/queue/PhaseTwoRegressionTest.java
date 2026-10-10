package com.mineplugin.queue;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.config.QueueSettings;
import com.mineplugin.queue.manager.QueueManager;
import com.mineplugin.queue.model.LoginAttempt;
import com.mineplugin.queue.model.QueuedPlayer;
import com.mineplugin.queue.permission.PermissionResolver;
import com.mineplugin.queue.permission.PermissionSnapshot;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static com.mineplugin.queue.manager.QueueManager.LoginResultType.*;
import static org.junit.jupiter.api.Assertions.*;

class PhaseTwoRegressionTest {
    private final MutableClock clock = new MutableClock();
    private final YamlConfiguration yaml = settings(1);
    private final QueueConfig config = new QueueConfig(yaml);
    private final FakePermissionResolver permissions = new FakePermissionResolver(clock);
    private final QueueManager manager = new QueueManager(null, config, permissions, clock, () -> null);
    private static final String IP = "192.0.2.1";

    static YamlConfiguration settings(int capacity) {
        var yaml = new YamlConfiguration();
        yaml.set("queue.max-players-override", capacity);
        yaml.set("queue.rate-limit-seconds", 0);
        yaml.set("queue.keep-alive-seconds", 300);
        yaml.set("queue.slot-reservation-seconds", 60);
        yaml.set("queue.in-flight-timeout-seconds", 30);
        yaml.set("permissions.resolve-timeout-ms", 1000);
        return yaml;
    }

    private QueueManager.LoginDecision attempt(UUID uuid) {
        return manager.handlePreLogin(uuid, "User", IP);
    }
    private QueuedPlayer entry(QueueManager queue, UUID uuid) {
        return queue.getQueueList().stream().filter(p -> p.getUuid().equals(uuid)).findFirst().orElseThrow();
    }

    @ParameterizedTest @ValueSource(longs = {29_999, 30_000, 30_001, 120_000})
    void delayedPreLoginNeverReleasesPhysicalCapacity(long elapsed) {
        UUID old = UUID.randomUUID();
        var grant = attempt(old);
        clock.advance(elapsed);
        manager.cleanupTick();
        UUID waiting = UUID.randomUUID();
        assertEquals(IN_QUEUE, attempt(waiting).type());
        assertEquals(1, manager.getInFlightCount());
        assertEquals(0, manager.getReservedSlotCount());
        assertEquals(elapsed >= 30_000 ? 1 : 0, manager.getTimedOutInFlightCount());
        var permit = manager.beginLogin(old, IP, new Object());
        assertEquals(grant.attemptId(), permit.attemptId());
        assertEquals(elapsed < 30_000, permit.admitted());
        manager.completeLogin(old, permit.attemptId(), permit.admitted());
        if (elapsed >= 30_000) assertEquals(0, manager.getInFlightCount());
        manager.onConnectionClose(old, IP);
        manager.cleanupTick();
        assertTrue(entry(manager, waiting).hasActiveReservation());
    }

    @Test void acceptedLoginStillOccupiesCapacityBeyondJoinDeadline() {
        UUID uuid = UUID.randomUUID();
        var grant = attempt(uuid);
        Object identity = new Object();
        assertTrue(manager.beginLogin(uuid, IP, identity).admitted());
        manager.completeLogin(uuid, grant.attemptId(), true);
        clock.advance(120_000);
        manager.cleanupTick();
        assertEquals(LoginAttempt.Phase.LOGIN_ACCEPTED, manager.getAttempt(uuid).phase());
        assertEquals(1, manager.getInFlightCount());
        assertEquals(IN_QUEUE, attempt(UUID.randomUUID()).type());
        assertEquals(1, manager.getInFlightTimeoutCount());
        manager.cleanupTick();
        assertEquals(1, manager.getInFlightTimeoutCount());
    }

    @Test void failedAttemptBlocksDuplicateUntilCloseAndStaleTokenCannotReleaseNewAttempt() {
        UUID uuid = UUID.randomUUID();
        var old = attempt(uuid);
        assertTrue(manager.releaseInFlight(uuid, old.attemptId()));
        assertEquals(0, manager.getInFlightCount());
        assertEquals(1, manager.getTrackedConnectionCount());
        assertEquals(RATE_LIMITED, attempt(uuid).type());
        manager.onConnectionClose(uuid, "192.0.2.99");
        assertNotNull(manager.getAttempt(uuid));
        manager.onConnectionClose(uuid, IP);
        var current = attempt(uuid);
        assertNotEquals(old.attemptId(), current.attemptId());
        assertFalse(manager.releaseInFlight(uuid, old.attemptId()));
        manager.completeLogin(uuid, old.attemptId(), false);
        assertFalse(manager.cancelPreLogin(uuid, old.attemptId()));
        assertTrue(manager.isInFlight(uuid));
    }

    @Test void mismatchedPlayerOrAddressCannotBindOrResurrectTimedOutAttempt() {
        UUID uuid = UUID.randomUUID();
        var grant = attempt(uuid);
        Object player = new Object();
        assertFalse(manager.beginLogin(uuid, "192.0.2.99", player).admitted());
        assertTrue(manager.beginLogin(uuid, IP, player).admitted());
        assertFalse(manager.beginLogin(uuid, IP, new Object()).admitted());
        clock.advance(30_000);
        assertTrue(manager.beginLogin(uuid, IP, player).timedOut());
        manager.completeLogin(uuid, grant.attemptId(), true);
        assertEquals(LoginAttempt.Phase.FAILED, manager.getAttempt(uuid).phase());
        manager.completeLogin(uuid, grant.attemptId(), true);
        assertEquals(LoginAttempt.Phase.FAILED, manager.getAttempt(uuid).phase());
    }

    @Test void cancellationAndCloseReleaseHandshakeBeforeAnyLogin() {
        UUID uuid = UUID.randomUUID();
        var grant = attempt(uuid);
        assertTrue(manager.cancelPreLogin(uuid, grant.attemptId()));
        assertEquals(0, manager.getTrackedConnectionCount());
        assertFalse(manager.cancelPreLogin(uuid, grant.attemptId()));
        var current = attempt(uuid);
        manager.onConnectionClose(uuid, IP);
        assertEquals(0, manager.getInFlightCount());
        assertNotNull(current.attemptId());
    }

    @Test void reloadRefreshesAllWeightsResortsAndPreservesOldestLiveLease() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), occupied = UUID.randomUUID();
        PermissionResolver resolver = new PermissionResolver() {
            @Override public CompletionStage<PermissionSnapshot> resolve(UUID uuid, String name) {
                return resolve(uuid, name, config.getPermissionPriorities());
            }
            @Override public CompletionStage<PermissionSnapshot> resolve(UUID uuid, String name, Map<String, Integer> weights) {
                int weight = weights.getOrDefault(uuid.equals(a) ? "queue.priority.a" : "queue.priority.b", 0);
                return CompletableFuture.completedFuture(new PermissionSnapshot(false, weight, clock.millis(), "verified"));
            }
        };
        QueueManager queue = new QueueManager(null, config, resolver, clock, () -> null);
        queue.handlePreLogin(occupied, "Occupied", IP);
        queue.handlePreLogin(a, "A", IP);
        queue.handlePreLogin(b, "B", IP);
        queue.releaseInFlight(occupied, queue.getAttemptId(occupied));
        queue.cleanupTick();
        long lease = entry(queue, a).getReservationId();
        long deadline = entry(queue, a).getSlotReservedUntilTimestamp();
        config.setAntiBotRuntimeOverride(true);
        var next = settings(1);
        next.set("priorities.b.permission", "queue.priority.b");
        next.set("priorities.b.weight", 100);
        next.set("queue.slot-reservation-seconds", 1);
        queue.reload(QueueSettings.parse(next)).get(5, TimeUnit.SECONDS);
        assertEquals(b, queue.getQueueList().getFirst().getUuid());
        assertEquals(100, entry(queue, b).getPriority());
        assertEquals(lease, entry(queue, a).getReservationId());
        assertEquals(deadline, entry(queue, a).getSlotReservedUntilTimestamp());
        assertFalse(entry(queue, b).hasActiveReservation());
        assertFalse(config.isAntiBotEnabled());
        assertEquals(ALLOWED_RESERVED, queue.handlePreLogin(a, "A", IP).type());
    }

    @Test void reloadCapacityShrinkRevokesNewestLeaseInSameTransaction() throws Exception {
        config.load(settings(2));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), x = UUID.randomUUID(), y = UUID.randomUUID();
        attempt(x); attempt(y); attempt(a); attempt(b);
        manager.releaseInFlight(x, manager.getAttemptId(x));
        manager.releaseInFlight(y, manager.getAttemptId(y));
        manager.cleanupTick();
        long lease = entry(manager, a).getReservationId();
        manager.reload(QueueSettings.parse(settings(1))).get(5, TimeUnit.SECONDS);
        assertEquals(1, manager.getReservedSlotCount());
        assertEquals(lease, entry(manager, a).getReservationId());
        assertFalse(entry(manager, b).hasActiveReservation());
        assertEquals(IN_QUEUE, attempt(b).type());
    }

    @Test void providerFailureUnverifiedStaleOrTimeoutKeepsEntireOldGeneration() throws Exception {
        List<CompletionStage<PermissionSnapshot>> responses = List.of(
                CompletableFuture.failedFuture(new IllegalStateException("storage offline")),
                CompletableFuture.completedFuture(PermissionSnapshot.none(clock.millis())),
                CompletableFuture.completedFuture(new PermissionSnapshot(false, 50, clock.millis() - 2000, "stale")),
                new CompletableFuture<>());
        for (var response : responses) {
            AtomicReference<CompletionStage<PermissionSnapshot>> pending = new AtomicReference<>(
                    CompletableFuture.completedFuture(new PermissionSnapshot(false, 0, clock.millis(), "verified")));
            QueueManager queue = new QueueManager(null, config, (uuid, name) -> pending.get(), clock, () -> null);
            queue.handlePreLogin(UUID.randomUUID(), "Occupied", IP);
            queue.handlePreLogin(UUID.randomUUID(), "Waiting", IP);
            QueueSettings previous = config.snapshot();
            config.setAntiBotRuntimeOverride(true);
            var next = settings(2);
            next.set("permissions.resolve-timeout-ms", 25);
            pending.set(response);
            assertThrows(ExecutionException.class, () -> queue.reload(QueueSettings.parse(next)).get(5, TimeUnit.SECONDS));
            assertSame(previous, config.snapshot());
            assertEquals(1, queue.getQueueSize());
            assertEquals(0, queue.getReservedSlotCount());
            assertTrue(config.isAntiBotEnabled());
            assertEquals(0, queue.getQueueList().getFirst().getPriority());
        }
    }

    @Test void membershipChangeDuringReloadRejectsWithoutPublishingPartialSettings() throws Exception {
        CompletableFuture<PermissionSnapshot> delayed = new CompletableFuture<>();
        CountDownLatch resolving = new CountDownLatch(1);
        AtomicReference<Boolean> reload = new AtomicReference<>(false);
        QueueManager queue = new QueueManager(null, config, (uuid, name) -> {
            if (reload.get()) { resolving.countDown(); return delayed; }
            return CompletableFuture.completedFuture(new PermissionSnapshot(false, 0, clock.millis(), "verified"));
        }, clock, () -> null);
        queue.handlePreLogin(UUID.randomUUID(), "Occupied", IP);
        queue.handlePreLogin(UUID.randomUUID(), "Waiting", IP);
        QueueSettings previous = config.snapshot();
        reload.set(true);
        var result = queue.reload(QueueSettings.parse(settings(2)));
        assertTrue(resolving.await(5, TimeUnit.SECONDS));
        queue.clearQueue();
        delayed.complete(new PermissionSnapshot(false, 100, clock.millis(), "verified"));
        assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
        assertSame(previous, config.snapshot());
    }

    @Test void olderReloadCannotOverwriteNewerSuccessfulReload() throws Exception {
        CompletableFuture<PermissionSnapshot> delayed = new CompletableFuture<>();
        CountDownLatch resolving = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        QueueManager queue = new QueueManager(null, config, (uuid, name) -> {
            if (calls.incrementAndGet() == 3) { resolving.countDown(); return delayed; }
            return CompletableFuture.completedFuture(new PermissionSnapshot(false, 0, clock.millis(), "verified"));
        }, clock, () -> null);
        queue.handlePreLogin(UUID.randomUUID(), "Occupied", IP);
        queue.handlePreLogin(UUID.randomUUID(), "Waiting", IP);
        var older = queue.reload(QueueSettings.parse(settings(2)));
        assertTrue(resolving.await(5, TimeUnit.SECONDS));
        queue.reload(QueueSettings.parse(settings(3))).get(5, TimeUnit.SECONDS);
        delayed.complete(new PermissionSnapshot(false, 0, clock.millis(), "verified"));
        assertThrows(ExecutionException.class, () -> older.get(5, TimeUnit.SECONDS));
        assertEquals(3, config.getMaxPlayersOverride());
    }

    @Test void settingsReloadDoesNotShortenExistingAttemptDeadline() throws Exception {
        UUID uuid = UUID.randomUUID();
        var old = attempt(uuid);
        long deadline = manager.getAttempt(uuid).deadline();
        var next = settings(1);
        next.set("queue.in-flight-timeout-seconds", 5);
        manager.reload(QueueSettings.parse(next)).get(5, TimeUnit.SECONDS);
        clock.advance(5_000);
        manager.cleanupTick();
        assertEquals(deadline, manager.getAttempt(uuid).deadline());
        assertTrue(manager.beginLogin(uuid, IP, new Object()).admitted());
        assertEquals(old.attemptId(), manager.getAttemptId(uuid));
    }

    @Test void disabledQueueTracksHandshakesForLaterEnableAndShutdownInvalidatesReload() throws Exception {
        yaml.set("queue.enabled", false);
        config.load(yaml);
        assertEquals(ALLOWED_DIRECT, attempt(UUID.randomUUID()).type());
        manager.reload(QueueSettings.parse(settings(1))).get(5, TimeUnit.SECONDS);
        assertEquals(IN_QUEUE, attempt(UUID.randomUUID()).type());
        manager.clearRuntimeState();
        assertEquals(0, manager.getTrackedConnectionCount());
        assertThrows(ExecutionException.class, () -> manager.reload(QueueSettings.parse(settings(1))).get(5, TimeUnit.SECONDS));
        assertEquals(RATE_LIMITED, attempt(UUID.randomUUID()).type());
    }

    @Test void concurrentReloadAdmissionCleanupAndReadersDoNotDeadlockOrLoseWaitingMembers() throws Exception {
        var next = settings(1);
        next.set("queue.max-queue-size", 1000);
        config.load(next);
        attempt(UUID.randomUUID());
        List<UUID> queued = new ArrayList<>();
        for (int i = 0; i < 500; i++) { UUID uuid = UUID.randomUUID(); queued.add(uuid); attempt(uuid); }
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final int worker = i;
                work.add(pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < 10; j++) {
                        if (worker == 0) {
                            manager.reload(QueueSettings.parse(next)).get(5, TimeUnit.SECONDS);
                        } else {
                            attempt(queued.get((worker * 31 + j) % queued.size()));
                            manager.cleanupTick();
                            assertEquals(500, manager.getQueueList().size());
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : work) task.get(15, TimeUnit.SECONDS);
        }
        assertEquals(500, manager.getQueueSize());
        assertEquals(1, manager.getInFlightCount());
        assertEquals(0, manager.getReservedSlotCount());
    }
    @Test void preLoginOverlappingReloadResolvesNewGenerationWithoutApplyingOldBypass() throws Exception {
        yaml.set("permissions.resolve-timeout-ms", 5000);
        config.load(yaml);
        UUID vip = UUID.randomUUID();
        CompletableFuture<PermissionSnapshot> old = new CompletableFuture<>();
        CountDownLatch resolving = new CountDownLatch(1);
        AtomicReference<Boolean> delay = new AtomicReference<>(false);
        PermissionResolver provider = new PermissionResolver() {
            @Override public CompletionStage<PermissionSnapshot> resolve(UUID uuid, String name) { return resolve(uuid, name, config.getPermissionPriorities()); }
            @Override public CompletionStage<PermissionSnapshot> resolve(UUID uuid, String name, Map<String, Integer> weights) {
                int weight = weights.getOrDefault("queue.priority.vip", 0);
                if (uuid.equals(vip) && weight == 0 && delay.get()) { resolving.countDown(); return old; }
                return CompletableFuture.completedFuture(new PermissionSnapshot(false, weight, clock.millis(), "verified"));
            }
        };
        QueueManager queue = new QueueManager(null, config, provider, clock, () -> null);
        queue.handlePreLogin(UUID.randomUUID(), "Occupied", IP);
        queue.handlePreLogin(vip, "Vip", IP);
        delay.set(true);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            var attempt = pool.submit(() -> queue.handlePreLogin(vip, "Vip", IP));
            assertTrue(resolving.await(5, TimeUnit.SECONDS));
            var candidate = settings(1);
            candidate.set("priorities.vip.permission", "queue.priority.vip");
            candidate.set("priorities.vip.weight", 70);
            queue.reload(QueueSettings.parse(candidate)).get(5, TimeUnit.SECONDS);
            old.complete(new PermissionSnapshot(true, 999, clock.millis(), "old-generation"));
            assertEquals(IN_QUEUE, attempt.get(5, TimeUnit.SECONDS).type());
        }
        assertEquals(70, entry(queue, vip).getPriority());
        assertEquals(1, queue.getInFlightCount());
    }

    @Test void slowOldFileParseCannotStartARequestAfterANewerCommandCommitted() throws Exception {
        long oldCommand = manager.beginReloadRequest();
        manager.reload(QueueSettings.parse(settings(3))).get(5, TimeUnit.SECONDS);
        assertThrows(ExecutionException.class, () -> manager.reload(QueueSettings.parse(settings(2)), oldCommand).get(5, TimeUnit.SECONDS));
        assertEquals(3, config.getMaxPlayersOverride());
    }

}
