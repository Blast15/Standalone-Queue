package com.mineplugin.queue;

import com.mineplugin.queue.command.QueueCommand;
import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.AdmissionHistory;
import com.mineplugin.queue.manager.CapacitySource;
import com.mineplugin.queue.manager.QueueManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.mineplugin.queue.manager.QueueManager.LoginResultType.*;
import static org.junit.jupiter.api.Assertions.*;

class PhaseThreeRegressionTest {
    private final MutableClock clock = new MutableClock();
    private final YamlConfiguration yaml = PhaseTwoRegressionTest.settings(1);
    private final FakePermissionResolver permissions = new FakePermissionResolver(clock);
    private final AtomicInteger online = new AtomicInteger(1);
    private QueueConfig config;
    private QueueManager manager;
    private void start() {
        config = new QueueConfig(yaml);
        manager = new QueueManager(null, config, permissions, clock, () -> new CapacitySource.Snapshot(online.get(), 1));
        manager.refreshServerCapacity();
    }
    private QueueManager.LoginDecision attempt(UUID id, String name, String ip) { return manager.handlePreLogin(id, name, ip); }
    private QueueManager.LoginDecision attempt(String name, String ip) { return attempt(UUID.randomUUID(), name, ip); }
    private void antiBot(int ip, int global, int quota) {
        yaml.set("queue.anti-bot.enabled", true);
        yaml.set("queue.anti-bot.max-attempts-per-ip", ip);
        yaml.set("queue.anti-bot.global-attempts-per-window", global);
        yaml.set("queue.anti-bot.max-queued-per-ip", quota);
    }
    private Player player(UUID uuid) {
        return (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Player.class},
                (proxy, method, args) -> method.getName().equals("getUniqueId") ? uuid : null);
    }
    private void join(UUID uuid, String name) {
        var decision = attempt(uuid, name, "192.0.2.1");
        assertNotNull(decision.attemptId());
        Player player = player(uuid);
        assertTrue(manager.beginLogin(uuid, "192.0.2.1", player).admitted());
        manager.completeLogin(uuid, decision.attemptId(), true);
        online.set(1); manager.onPlayerJoin(player); manager.onPlayerJoin(player);
        manager.onPlayerQuit(player); manager.onConnectionClose(uuid, "192.0.2.1"); online.set(0);
    }


    @Test void expiryCountersAndCompletedQueueWaitHistogramAreMeasuredOnce() {
        start(); UUID a = UUID.randomUUID(); attempt(a, "A", "192.0.2.1");
        clock.advance(30_000); online.set(0); manager.refreshServerCapacity(); manager.cleanupTick();
        join(a, "A");
        var stats = manager.getStatistics();
        assertEquals(List.of(1L, 1L, 1L, 1L, 1L), stats.waitBuckets());
        assertEquals(30, stats.waitSecondsSum());
        assertTrue(manager.getMetricsText().contains("standalone_queue_wait_seconds_count 1"));
        online.set(1); manager.refreshServerCapacity(); attempt("B", "b");
        online.set(0); manager.refreshServerCapacity(); manager.cleanupTick();
        clock.advance(60_000); manager.cleanupTick();
        assertEquals(1, manager.getStatistics().reservationExpired());
        clock.advance(300_000); manager.cleanupTick(); manager.cleanupTick();
        assertEquals(1, manager.getStatistics().heartbeatExpired());
    }

    @Test void uuidIpGlobalQuotaAndFullHaveSeparateCounters() {
        antiBot(2, 3, 1); yaml.set("queue.rate-limit-seconds", 2); start();
        UUID a = UUID.randomUUID(); assertEquals(IN_QUEUE, attempt(a, "A", "a").type());
        assertEquals(RATE_LIMITED_UUID, attempt(a, "A", "a").type());
        assertEquals(QUEUE_IP_QUOTA, attempt("B", "a").type());
        assertEquals(RATE_LIMITED_IP, attempt("C", "a").type());
        assertEquals(IN_QUEUE, attempt("D", "b").type());
        assertEquals(RATE_LIMITED_GLOBAL, attempt("E", "c").type());
        var counts = manager.getStatistics().decisions();
        for (var reason : List.of(RATE_LIMITED_UUID, RATE_LIMITED_IP, RATE_LIMITED_GLOBAL, QUEUE_IP_QUOTA)) assertEquals(1L, counts.get(reason));
        assertThrows(UnsupportedOperationException.class, () -> counts.put(IN_QUEUE, 0L));
        yaml.set("queue.anti-bot.enabled", false); yaml.set("queue.max-queue-size", 2);
        config.load(yaml); assertEquals(QUEUE_FULL, attempt("F", "f").type());
        assertEquals(1L, manager.getStatistics().decisions().get(QUEUE_FULL));
    }

    @Test void cleanupPreservesUuidCooldownLongerThanOneMinuteAndExactDeadlineIsAllowed() {
        yaml.set("queue.rate-limit-seconds", 120); start(); UUID id = UUID.randomUUID();
        assertEquals(IN_QUEUE, attempt(id, "A", "a").type());
        clock.advance(61_000); manager.cleanupTick(); assertEquals(RATE_LIMITED_UUID, attempt(id, "A", "a").type());
        clock.advance(59_000); assertEquals(IN_QUEUE, attempt(id, "A", "a").type());
    }

    @Test void oneNoisyIpDoesNotSpendGlobalBudgetOnItsRejectedAttemptsAndNat50CanQueue() {
        antiBot(60, 150, 60); start();
        for (int i = 0; i < 200; i++) attempt("Bot" + i, "198.51.100.1");
        for (int i = 0; i < 50; i++) assertEquals(IN_QUEUE, attempt("Nat" + i, "203.0.113.1").type());
        assertEquals(110, manager.getQueueSize());
        assertEquals(140L, manager.getStatistics().decisions().get(RATE_LIMITED_IP));
        assertEquals(0L, manager.getStatistics().decisions().get(RATE_LIMITED_GLOBAL));
    }

    @Test void globalBurstCannotStealExactLeaseOwnersReconnectButOtherIpGetsNoHeadroom() {
        antiBot(5, 2, 5); start();
        UUID owner = UUID.randomUUID(); assertEquals(IN_QUEUE, attempt(owner, "Owner", "owner-ip").type());
        clock.advance(10_001); online.set(0); manager.refreshServerCapacity(); manager.cleanupTick();
        attempt("Bot1", "bot"); attempt("Bot2", "bot");
        assertEquals(RATE_LIMITED_GLOBAL, attempt(owner, "Owner", "spoof-ip").type());
        assertEquals(ALLOWED_RESERVED, attempt(owner, "Owner", "owner-ip").type());
        assertEquals(1, manager.getInFlightCount());
    }

    @Test void protectedBudgetIsBoundedAndRefillsAtTheExactWindowBoundary() {
        antiBot(5, 1, 5); yaml.set("queue.max-players-override", 2); online.set(2); start();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(); attempt(a, "A", "a");
        clock.advance(10_000); attempt(b, "B", "b"); online.set(0); manager.refreshServerCapacity(); manager.cleanupTick();
        assertEquals(ALLOWED_RESERVED, attempt(a, "A", "a").type());
        assertEquals(RATE_LIMITED_GLOBAL, attempt(b, "B", "b").type());
        assertEquals(1, manager.getInFlightCount()); assertEquals(1, manager.getReservedSlotCount());
        clock.advance(10_000); assertEquals(ALLOWED_RESERVED, attempt(b, "B", "b").type());
        assertEquals(2, manager.getInFlightCount());
    }

    @Test void leaseHeadroomStillEnforcesIpAndUuidCooldown() {
        antiBot(1, 2, 5); start(); UUID owner = UUID.randomUUID();
        attempt(owner, "Owner", "owner"); online.set(0); manager.refreshServerCapacity(); manager.cleanupTick();
        assertEquals(RATE_LIMITED_IP, attempt(owner, "Owner", "owner").type());
        clock.advance(10_000); assertEquals(ALLOWED_RESERVED, attempt(owner, "Owner", "owner").type());
    }

    @Test void reloadRejectsAttemptCeilingsAbove4096AndStartupClampsLegacyValues() {
        yaml.set("queue.anti-bot.max-attempts-per-ip", 4097);
        assertThrows(IllegalArgumentException.class, () -> com.mineplugin.queue.config.QueueSettings.parse(yaml));
        start(); assertEquals(4096, config.getAntiBotMaxAttemptsPerIp());
        yaml.set("queue.anti-bot.max-attempts-per-ip", 20); yaml.set("queue.anti-bot.global-attempts-per-window", 4097);
        assertThrows(IllegalArgumentException.class, () -> com.mineplugin.queue.config.QueueSettings.parse(yaml));
    }

    @Test void trackingIsBoundedAndExpiredIpsAreReclaimedAtWindowBoundary() {
        antiBot(4096, 4096, 4096); start();
        for (int i = 0; i < 4200; i++) attempt("U" + i, "ip" + i);
        assertEquals(4096, manager.getStatistics().trackedUuids());
        assertEquals(4096, manager.getStatistics().trackedIps());
        clock.advance(10_000); assertEquals(QUEUE_FULL, attempt("Fresh", "fresh").type());
        assertEquals(1, manager.getStatistics().trackedIps());
        assertTrue(Double.isFinite(manager.getStatistics().averagePreLoginMs()));
    }

    @Test void pauseKeepsExistingLeaseAndHandshakeStopsNewGrantsAndResumeAllocates() {
        start(); UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        attempt(a, "A", "a"); attempt(b, "B", "b");
        online.set(0); manager.refreshServerCapacity(); manager.cleanupTick();
        long lease = manager.getQueueList().getFirst().getReservationId();
        manager.setPaused(true);
        assertEquals(PAUSED, attempt("New", "new").type());
        assertEquals(2, manager.getQueueSize());
        assertEquals(lease, manager.getQueueList().getFirst().getReservationId());
        var granted = attempt(a, "A", "a"); assertEquals(ALLOWED_RESERVED, granted.type());
        manager.cleanupTick(); assertEquals(1, manager.getInFlightCount());
        manager.cancelPreLogin(a, granted.attemptId()); manager.cleanupTick();
        assertEquals(0, manager.getReservedSlotCount());
        manager.setPaused(false); assertEquals(1, manager.getReservedSlotCount());
    }

    @Test void pauseDoesNotBreakAcceptedLoginAndBlocksNewBypass() {
        online.set(0); start(); UUID id = UUID.randomUUID(); var grant = attempt(id, "A", "a");
        Player p = player(id); manager.beginLogin(id, "a", p); manager.completeLogin(id, grant.attemptId(), true);
        manager.setPaused(true); online.set(1); manager.onPlayerJoin(p);
        assertEquals(1, manager.getStatistics().successfulJoins());
        UUID bypass = UUID.randomUUID(); permissions.set(bypass, true, 100);
        assertEquals(PAUSED, attempt(bypass, "Admin", "b").type());
    }

    @Test void movePreservesTierHeartbeatAndRejectsCrossingLease() {
        start(); UUID vip = UUID.randomUUID(); permissions.set(vip, false, 10); attempt(vip, "VIP", "v");
        attempt("A", "a"); attempt("B", "b"); attempt("C", "c");
        var before = manager.inspect("C"); assertTrue(manager.move("C", 2));
        assertEquals(List.of("VIP", "C", "A", "B"), manager.getQueueList().stream().map(p -> p.getUsername()).toList());
        assertEquals(before.ttlSeconds(), manager.inspect("C").ttlSeconds());
        assertFalse(manager.move("C", 1)); assertFalse(manager.move("C", 0)); assertFalse(manager.move("Missing", 2));
        online.set(0); manager.refreshServerCapacity(); manager.cleanupTick(); assertFalse(manager.move("VIP", 1));
        clock.advance(60_000); assertFalse(manager.move("VIP", 1)); // stale lease must first be reconciled
    }

    @Test void inspectFindsQueueAndInFlightByNameOrUuidWithoutIp() {
        start(); UUID id = UUID.randomUUID(); attempt(id, "Alice", "192.0.2.99");
        assertEquals(id, manager.inspect("alice").uuid()); assertEquals(id, manager.inspect(id.toString()).uuid());
        assertFalse(manager.inspect("Alice").toString().contains("192.0.2.99"));
        online.set(0); manager.refreshServerCapacity(); manager.cleanupTick(); attempt(id, "Alice", "192.0.2.99");
        assertEquals("PRELOGIN_ACCEPTED", manager.inspect("Alice").phase()); assertEquals(0, manager.inspect("Alice").position());
    }

    @Test void etaUsesSuccessfulQueueJoinsAndNeverCountsQuitCancelledLoginOrDuplicateJoin() {
        start(); var ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (int i = 0; i < ids.size(); i++) attempt(ids.get(i), "User" + i, "192.0.2.1");
        assertEquals("Đang cập nhật", attempt(ids.getLast(), "User3", "192.0.2.1").position().getFormattedEta());
        online.set(0); manager.refreshServerCapacity();
        for (int i = 0; i < 3; i++) { manager.cleanupTick(); join(ids.get(i), "User" + i); clock.advance(10_000); }
        online.set(1); manager.refreshServerCapacity(); // exhausted capacity; revoke leftover lease
        var estimate = attempt(ids.getLast(), "User3", "192.0.2.1").position().getFormattedEta();
        assertTrue(estimate.startsWith("Ước tính "), estimate);
        assertEquals(3, manager.getStatistics().successfulJoins()); assertEquals(3, manager.getStatistics().joins5m());
        clock.advance(120_000);
        assertEquals("Đang cập nhật", attempt(ids.getLast(), "User3", "192.0.2.1").position().getFormattedEta());
    }

    @Test void directOrFailedHandshakesDoNotInventQueueThroughput() {
        online.set(0); start();
        for (int i = 0; i < 3; i++) { join(UUID.randomUUID(), "Direct" + i); clock.advance(10_000); }
        assertEquals(3, manager.getStatistics().successfulJoins()); assertEquals(0, manager.getStatistics().joins5m());
        UUID fail = UUID.randomUUID(); var grant = attempt(fail, "Failed", "x");
        manager.cancelPreLogin(fail, grant.attemptId()); assertEquals(3, manager.getStatistics().successfulJoins());
    }

    @Test void rollingHistoryExpiresAtExactFiveAndFifteenMinuteBoundariesAndIsFiniteAtSameTimestamp() {
        var history = new AdmissionHistory(); history.record(0); history.record(0); history.record(0);
        assertEquals(0, history.snapshot(0).secondsPerJoin());
        assertEquals(3, history.snapshot(299_999).joins5m()); assertEquals(0, history.snapshot(300_000).joins5m());
        assertEquals(3, history.snapshot(899_999).joins15m()); assertEquals(0, history.snapshot(900_000).joins15m());
        for (int i = 0; i < 1000; i++) history.record(i * 1000L);
        assertEquals(900, history.snapshot(999_000).joins15m()); assertTrue(Double.isFinite(history.snapshot(999_000).secondsPerJoin()));
    }

    @Test void ewmaRespondsToSlowerSuccessfulAdmissionsAndNoSamplesNeverPromiseInstantEntry() {
        var history = new AdmissionHistory();
        for (int i = 0; i < 30; i++) history.record(i * 10_000L);
        double normal = history.snapshot(290_000).secondsPerJoin();
        history.record(370_000); assertTrue(history.snapshot(370_000).secondsPerJoin() > normal);
        start(); assertEquals("Đang cập nhật", attempt("First", "a").position().getFormattedEta());
    }

    @Test void failedAndCancelledTokensCountOnceAndNoFailedAdmissionContributesToEta() {
        online.set(0); start(); UUID first = UUID.randomUUID(); var granted = attempt(first, "First", "a");
        assertTrue(manager.cancelPreLogin(first, granted.attemptId())); assertFalse(manager.cancelPreLogin(first, granted.attemptId()));
        UUID second = UUID.randomUUID(); granted = attempt(second, "Second", "b");
        manager.beginLogin(second, "b", new Object()); manager.completeLogin(second, granted.attemptId(), false);
        manager.completeLogin(second, granted.attemptId(), false);
        assertEquals(1, manager.getStatistics().cancelledPreLogins()); assertEquals(1, manager.getStatistics().failedLogins());
        assertEquals(0, manager.getStatistics().successfulJoins()); assertEquals(0, manager.getStatistics().joins5m());
    }

    @Test void pauseAndAdminMoveRemainSafeUnderConcurrentMetricsAndCleanup() throws Exception {
        start(); attempt("A", "a"); attempt("B", "b");
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            var jobs = List.<Callable<Void>>of(
                    () -> { for (int i = 0; i < 100; i++) { manager.setPaused(i % 2 == 0); manager.move("B", 1); } return null; },
                    () -> { for (int i = 0; i < 100; i++) manager.getStatistics(); return null; },
                    () -> { for (int i = 0; i < 100; i++) manager.cleanupTick(); return null; });
            for (var result : pool.invokeAll(jobs)) result.get(5, TimeUnit.SECONDS);
            assertEquals(2, manager.getQueueSize()); assertEquals(0, manager.getReservedSlotCount());
        } finally { pool.shutdownNow(); }
    }

    @Test void metricsHaveBoundedReasonLabelsAndNoPlayerOrIp() {
        start(); attempt("SecretPlayer", "192.0.2.222");
        String text = manager.getMetricsText(); assertFalse(text.contains("SecretPlayer")); assertFalse(text.contains("192.0.2.222"));
        assertTrue(text.contains("standalone_queue_decisions_total{reason=\"IN_QUEUE\"} 1"));
        assertFalse(text.contains("NaN")); assertFalse(text.contains("Infinity"));
    }

    private CommandSender sender(Set<String> permissions, List<String> messages) {
        return (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{CommandSender.class}, (p, m, args) -> {
            if (m.getName().equals("hasPermission")) return permissions.contains(args[0]);
            if (m.getName().equals("getName")) return "Operator";
            if (m.getName().equals("sendMessage") && args != null && args[0] instanceof Component c)
                messages.add(PlainTextComponentSerializer.plainText().serialize(c));
            return null;
        });
    }
    private Command command() { return new Command("queue") { public boolean execute(CommandSender s, String l, String[] a) { return true; } }; }

    @Test void granularPermissionsAuthorizeDispatchAndTabCompletionWithoutQueueAdmin() {
        start(); var executor = new QueueCommand(null, manager, config); var messages = new ArrayList<String>();
        var stats = sender(Set.of("queue.stats"), messages);
        executor.onCommand(stats, command(), "queue", new String[]{"stats"}); assertTrue(messages.stream().anyMatch(s -> s.contains("JOIN=")));
        assertEquals(List.of("status", "stats", "help"), executor.onTabComplete(stats, command(), "queue", new String[]{""}));
        executor.onCommand(stats, command(), "queue", new String[]{"pause"}); assertFalse(manager.isPaused());
        assertTrue(executor.onTabComplete(stats, command(), "queue", new String[]{"inspect", ""}).isEmpty());
        var manage = sender(Set.of("queue.manage"), messages); executor.onCommand(manage, command(), "queue", new String[]{"pause"});
        assertTrue(manager.isPaused()); executor.onCommand(manage, command(), "queue", new String[]{"resume"}); assertFalse(manager.isPaused());
        attempt("Alice", "secret-ip"); var inspect = sender(Set.of("queue.inspect"), messages);
        executor.onCommand(inspect, command(), "queue", new String[]{"inspect", "Alice"});
        assertTrue(messages.getLast().contains("TTL=")); assertFalse(messages.getLast().contains("secret-ip"));
    }

    @Test void auditIsVisibleWithDebugOffAndEscapesLineBreaks() {
        start(); var records = new ArrayList<String>(); var logger = java.util.logging.Logger.getLogger("StandaloneQueue");
        var handler = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { records.add(record.getMessage()); }
            public void flush() {} public void close() {}
        };
        logger.addHandler(handler);
        try { manager.audit("A\nFake", "move", "B\rFake"); }
        finally { logger.removeHandler(handler); }
        assertEquals(List.of("[QueueAudit] actor=A_Fake action=move details=B_Fake"), records);
    }

    @Test void concurrent500Queue50AttemptsWithStatsAndPausePreservesCapacity() throws Exception {
        start(); for (int i = 0; i < 500; i++) attempt("User" + i, "ip" + i);
        online.set(0); manager.refreshServerCapacity(); manager.cleanupTick();
        ExecutorService pool = Executors.newFixedThreadPool(50);
        try {
            var jobs = new ArrayList<Callable<Void>>();
            for (var p : manager.getQueueList().subList(0, 50)) jobs.add(() -> {
                attempt(p.getUuid(), p.getUsername(), p.getIpAddress()); manager.getMetricsText(); return null;
            });
            for (var f : pool.invokeAll(jobs)) f.get(5, TimeUnit.SECONDS);
            assertTrue(manager.getActiveOccupiedSlots() <= 1); assertTrue(manager.getInFlightCount() + manager.getReservedSlotCount() <= 1);
            assertEquals(499, manager.getQueueSize());
        } finally { pool.shutdownNow(); }
    }
}
