package com.mineplugin.queue;

import com.mineplugin.queue.config.*;
import com.mineplugin.queue.manager.*;
import com.mineplugin.queue.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.mineplugin.queue.manager.QueueManager.LoginResultType.*;

class QueueRecoveryTest {
    @TempDir Path directory;
    private final MutableClock clock = new MutableClock();
    private final FakePermissionResolver permissions = new FakePermissionResolver(clock);
    private QueueManager manager(int online) {
        var config = new QueueConfig(PhaseTwoRegressionTest.settings(1));
        var manager = new QueueManager(null, config, permissions, clock, () -> new CapacitySource.Snapshot(online, 1));
        manager.refreshServerCapacity(); return manager;
    }
    private QueueEntry entry(String name, long sequence) {
        return new QueueEntry(UUID.randomUUID(), name, sequence, clock.millis(), clock.millis());
    }

    @Test void atomicRoundTripAndOwnershipReacquisition() throws Exception {
        var entries = List.of(entry("A", 1), entry("B", 2));
        try (var store = new QueueSnapshotStore(directory)) {
            assertTrue(store.read().isEmpty()); store.write(entries); assertEquals(entries, store.read());
            assertThrows(IOException.class, () -> new QueueSnapshotStore(directory));
        }
        try (var store = new QueueSnapshotStore(directory)) { assertEquals(entries, store.read()); }
    }

    @Test void invalidReplacementKeepsLastGoodSnapshotAndInterruptedTemporaryIsIgnored() throws Exception {
        var good = entry("A", 1);
        try (var store = new QueueSnapshotStore(directory)) {
            store.write(List.of(good));
            assertThrows(IOException.class, () -> store.write(List.of(good, good)));
            assertThrows(IOException.class, () -> store.write(List.of(entry("\n", 3))));
            Files.write(directory.resolve("queue-state-crashed.tmp"), new byte[]{1, 2});
            assertEquals(List.of(good), store.read());
        }
    }

    @Test void corruptTruncatedOversizedAndUnsupportedSnapshotsAreRejected() throws Exception {
        try (var store = new QueueSnapshotStore(directory)) {
            store.write(List.of(entry("A", 1))); byte[] good = Files.readAllBytes(directory.resolve("queue-state.bin"));
            byte[] corrupt = good.clone(); corrupt[20] ^= 1;
            Files.write(directory.resolve("queue-state.bin"), corrupt); assertThrows(IOException.class, store::read);
            Files.write(directory.resolve("queue-state.bin"), Arrays.copyOf(good, 20)); assertThrows(IOException.class, store::read);
            byte[] future = good.clone(); future[7] = 2;
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(future, future.length - 32));
            System.arraycopy(hash, 0, future, future.length - 32, 32);
            Files.write(directory.resolve("queue-state.bin"), future); assertThrows(IOException.class, store::read);
            Files.write(directory.resolve("queue-state.bin"), new byte[16 * 1024 * 1024 + 1]); assertThrows(IOException.class, store::read);
        }
    }

    @Test void restartPreservesOrderAndHeartbeatButDropsOldLeasesAndInflight() throws Exception {
        var old = manager(1); UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        old.handlePreLogin(a, "A", "secret-ip"); old.handlePreLogin(b, "B", "secret-ip");
        var entries = old.stopAndSnapshot();
        try (var store = new QueueSnapshotStore(directory)) {
            store.write(entries);
            assertFalse(new String(Files.readAllBytes(directory.resolve("queue-state.bin")), java.nio.charset.StandardCharsets.ISO_8859_1).contains("secret-ip"));
            var restored = manager(1); restored.beginRecovery();
            assertEquals(RATE_LIMITED, restored.handlePreLogin(UUID.randomUUID(), "C", "c").type());
            assertEquals(2, restored.restoreEntries(store.read())); restored.finishRecovery();
            assertEquals(List.of(a, b), restored.getQueueList().stream().map(p -> p.getUuid()).toList());
            assertEquals(0, restored.getReservedSlotCount()); assertEquals(0, restored.getInFlightCount());
            assertEquals(entries.getFirst().lastPingAt(), restored.getQueueList().getFirst().getLastPingTimestamp());
            assertEquals(RATE_LIMITED, old.handlePreLogin(UUID.randomUUID(), "D", "d").type());
        }
        var direct = manager(0); direct.handlePreLogin(UUID.randomUUID(), "Direct", "d");
        assertTrue(direct.stopAndSnapshot().isEmpty());
    }

    @Test void recoveryExpiresAtExactDeadlineAndRejectsFutureClockData() {
        var expired = entry("Expired", 1);
        clock.advance(300_000);
        var fresh = entry("Fresh", 2);
        var future = new QueueEntry(UUID.randomUUID(), "Future", 3, clock.millis() + 1, clock.millis() + 1);
        var restored = manager(1); restored.beginRecovery();
        assertEquals(1, restored.restoreEntries(List.of(fresh, expired, future)));
        assertEquals(fresh.uuid(), restored.getQueueList().getFirst().getUuid());
    }

    @Test void changedPermissionAndCapacityAreReconciledWithoutRestoringBypassOrLease() {
        var a = entry("A", 1); var b = entry("B", 2);
        permissions.set(b.uuid(), true, 50);
        var restored = manager(0); restored.beginRecovery();
        assertEquals(2, restored.restoreEntries(List.of(a, b))); restored.finishRecovery();
        assertEquals(b.uuid(), restored.getQueueList().getFirst().getUuid());
        assertEquals(1, restored.getReservedSlotCount()); assertEquals(0, restored.getInFlightCount());
        assertEquals(IN_QUEUE, restored.handlePreLogin(a.uuid(), "A", "a").type());
        permissions.set(b.uuid(), false, 50); // persisted state never grants stale bypass
        assertEquals(ALLOWED_RESERVED, restored.handlePreLogin(b.uuid(), "B", "b").type());
    }

    private java.util.logging.Logger startupLogger(CountDownLatch ready) {
        var logger = java.util.logging.Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { ready.countDown(); }
            public void flush() {} public void close() {}
        });
        return logger;
    }

    @Test void asyncServiceFlushesFinalSnapshotAndReleasesOwnership() throws Exception {
        var manager = manager(1); var ready = new CountDownLatch(1);
        var service = new QueuePersistence(manager, directory, 300, startupLogger(ready));
        service.ready().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("active", service.status());
        manager.handlePreLogin(UUID.randomUUID(), "Final", "hidden-ip");
        service.close(manager.stopAndSnapshot());
        try (var store = new QueueSnapshotStore(directory)) { assertEquals("Final", store.read().getFirst().name()); }
    }

    @Test void corruptStartupUsesEmptyMemoryQueueAndPreservesEvidence() throws Exception {
        Files.write(directory.resolve("queue-state.bin"), new byte[]{1, 2, 3});
        var manager = manager(1); var ready = new CountDownLatch(1);
        var service = new QueuePersistence(manager, directory, 1, startupLogger(ready));
        service.ready().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(service.status().startsWith("failed"));
        service.close(manager.stopAndSnapshot());
        assertEquals(0, manager.getQueueSize());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(directory.resolve("queue-state.bin")));
        try (var store = new QueueSnapshotStore(directory)) { assertThrows(IOException.class, store::read); }
    }


    @Test void restoredIdentitiesRebindIpAndCannotEvadeNatQuota() {
        var yaml = PhaseTwoRegressionTest.settings(1);
        yaml.set("queue.anti-bot.enabled", true); yaml.set("queue.anti-bot.max-queued-per-ip", 1);
        var manager = new QueueManager(null, new QueueConfig(yaml), permissions, clock,
                () -> new CapacitySource.Snapshot(1, 1));
        manager.refreshServerCapacity(); manager.beginRecovery();
        var a = entry(".Bedrock", 1); var b = entry("B", 2);
        manager.restoreEntries(List.of(a, b)); manager.finishRecovery();
        assertEquals(IN_QUEUE, manager.handlePreLogin(a.uuid(), a.name(), "nat").type());
        assertEquals(QUEUE_IP_QUOTA, manager.handlePreLogin(b.uuid(), b.name(), "nat").type());
        assertEquals("nat", manager.getQueueList().getFirst().getIpAddress());
        assertEquals(IN_QUEUE, manager.handlePreLogin(b.uuid(), b.name(), "different").type());
    }


    @Test void permissionFailureDoesNotPublishPartialRecoveryOrOverwriteSnapshot() throws Exception {
        var entry = entry("A", 1);
        try (var store = new QueueSnapshotStore(directory)) { store.write(List.of(entry)); }
        byte[] before = Files.readAllBytes(directory.resolve("queue-state.bin"));
        var manager = new QueueManager(null, new QueueConfig(PhaseTwoRegressionTest.settings(1)),
                (uuid, name) -> CompletableFuture.failedFuture(new IOException("provider unavailable")), clock,
                () -> new CapacitySource.Snapshot(1, 1));
        manager.refreshServerCapacity();
        var service = new QueuePersistence(manager, directory, 300, startupLogger(new CountDownLatch(1)));
        service.ready().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(service.status().startsWith("failed")); assertEquals(0, manager.getQueueSize());
        service.close(manager.stopAndSnapshot());
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("queue-state.bin")));
    }

    @Test void shutdownDuringPermissionRecoveryCannotPublishQueueOrReplaceGoodSnapshot() throws Exception {
        var entry = entry("A", 1);
        try (var store = new QueueSnapshotStore(directory)) { store.write(List.of(entry)); }
        byte[] before = Files.readAllBytes(directory.resolve("queue-state.bin"));
        var resolving = new CountDownLatch(1);
        var future = new CompletableFuture<com.mineplugin.queue.permission.PermissionSnapshot>();
        var manager = new QueueManager(null, new QueueConfig(PhaseTwoRegressionTest.settings(1)),
                (uuid, name) -> { resolving.countDown(); return future; }, clock,
                () -> new CapacitySource.Snapshot(1, 1));
        manager.refreshServerCapacity();
        var service = new QueuePersistence(manager, directory, 300, startupLogger(new CountDownLatch(1)));
        assertTrue(resolving.await(5, TimeUnit.SECONDS));
        var finalSnapshot = manager.stopAndSnapshot();
        future.complete(new com.mineplugin.queue.permission.PermissionSnapshot(false, 0, clock.millis(), "test"));
        service.close(finalSnapshot);
        assertEquals(0, manager.getQueueSize());
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("queue-state.bin")));
    }

    @Test void reloadPersistenceRequiresRestartAndKeepsOldSettings() {
        var yaml = PhaseTwoRegressionTest.settings(1); var config = new QueueConfig(yaml);
        var manager = new QueueManager(null, config, permissions, clock, () -> new CapacitySource.Snapshot(1, 1));
        yaml.set("persistence.enabled", true);
        assertThrows(CompletionException.class, () -> manager.reload(QueueSettings.parse(yaml)).join());
        assertFalse(config.snapshot().persistenceEnabled());
        yaml.set("persistence.enabled", "yes"); assertThrows(IllegalArgumentException.class, () -> QueueSettings.parse(yaml));
        yaml.set("persistence.enabled", true); yaml.set("persistence.flush-interval-seconds", 0);
        assertThrows(IllegalArgumentException.class, () -> QueueSettings.parse(yaml));
    }
}
