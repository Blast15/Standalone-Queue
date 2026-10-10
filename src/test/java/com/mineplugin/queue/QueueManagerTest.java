package com.mineplugin.queue;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.QueueManager;
import com.mineplugin.queue.model.QueuedPlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test cho QueueManager.
 *
 * @author BlastSama
 */
public class QueueManagerTest {

    private QueueConfig config;
    private MutableClock clock;
    private FakePermissionResolver permissions;
    private QueueManager queueManager;

    @BeforeEach
    public void setup() {
        String yaml = """
            queue:
              enabled: true
              max-players-override: 3
              keep-alive-seconds: 300
              slot-reservation-seconds: 60
              rate-limit-seconds: 1
              max-queue-size: 100
              debug-log: false
            priorities:
              vip:
                permission: "queue.priority.vip"
                weight: 10
              mvp:
                permission: "queue.priority.mvp"
                weight: 20
            messages:
              prefix: "[HÀNG CHỜ] "
            """;
        YamlConfiguration yamlConfig = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        config = new QueueConfig(yamlConfig);
        clock = new MutableClock();
        permissions = new FakePermissionResolver(clock);
        queueManager = new QueueManager(null, config, permissions, clock);
    }

    @Test
    @DisplayName("Case 1: Cho phép vào thẳng khi server còn slot trống và hàng chờ trống")
    public void testDirectJoinWhenSlotsAvailable() {
        UUID u1 = UUID.randomUUID();
        QueueManager.LoginDecision d1 = queueManager.handlePreLogin(u1, "Player1", "127.0.0.1");
        assertEquals(QueueManager.LoginResultType.ALLOWED_DIRECT, d1.type(), "Người chơi đầu tiên phải được vào thẳng");
    }

    @Test
    @DisplayName("Case 2: Chuyển người chơi vào hàng chờ khi đã đủ sức chứa (max-players-override)")
    public void testQueueWhenServerFull() {
        // Đăng nhập 3 người chơi để làm đầy sức chứa (max = 3)
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        UUID u3 = UUID.randomUUID();
        queueManager.handlePreLogin(u1, "P1", "127.0.0.1");
        queueManager.handlePreLogin(u2, "P2", "127.0.0.1");
        queueManager.handlePreLogin(u3, "P3", "127.0.0.1");

        // Người thứ 4 kết nối
        UUID u4 = UUID.randomUUID();
        QueueManager.LoginDecision d4 = queueManager.handlePreLogin(u4, "P4", "127.0.0.1");

        assertEquals(QueueManager.LoginResultType.IN_QUEUE, d4.type(), "Người thứ 4 phải bị đưa vào hàng chờ");
        assertNotNull(d4.position());
        assertEquals(1, d4.position().position(), "Người thứ 4 phải ở vị trí #1 trong hàng chờ");
        assertEquals(1, d4.position().total(), "Tổng số người trong hàng chờ là 1");
    }

    @Test
    @DisplayName("Case 3: Duy trì hàng chờ (Keep-Alive Heartbeat 5 phút)")
    public void testKeepAliveHeartbeatAndExpiration() throws InterruptedException {
        // Lấp đầy server
        for (int i = 0; i < 3; i++) {
            queueManager.handlePreLogin(UUID.randomUUID(), "Full" + i, "127.0.0.1");
        }

        UUID u = UUID.randomUUID();
        QueueManager.LoginDecision d = queueManager.handlePreLogin(u, "WaitingPlayer", "127.0.0.1");
        assertEquals(QueueManager.LoginResultType.IN_QUEUE, d.type());

        QueuedPlayer qp = queueManager.getQueueList().get(0);
        assertTrue(qp.getRemainingKeepAliveMs(300_000L) > 290_000L, "Thời gian giữ chỗ ban đầu phải gần 300s (5 phút)");

        // Giả lập người chơi không ping quá 300s
        String shortTimeoutYaml = """
            queue:
              enabled: true
              max-players-override: 3
              keep-alive-seconds: 1
              slot-reservation-seconds: 1
              rate-limit-seconds: 0
              max-queue-size: 100
            """;
        config.load(YamlConfiguration.loadConfiguration(new StringReader(shortTimeoutYaml)));

        // Đợi 1.1s để timeout kích hoạt
        clock.advance(1100);

        // Chạy cleanup tick
        queueManager.cleanupTick();

        assertEquals(0, queueManager.getQueueSize(), "Người chơi quá hạn 5 phút (1s trong test) phải bị loại khỏi hàng chờ");
    }

    @Test
    @DisplayName("Case 4: Giữ chỗ (Slot Reservation) & Chống cướp lượt của người đứng sau")
    public void testSlotReservationAndAntiSteal() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        UUID u3 = UUID.randomUUID();
        queueManager.handlePreLogin(u1, "P1", "127.0.0.1");
        queueManager.handlePreLogin(u2, "P2", "127.0.0.1");
        queueManager.handlePreLogin(u3, "P3", "127.0.0.1");

        // Thêm 2 người vào hàng chờ
        UUID q1 = UUID.randomUUID();
        UUID q2 = UUID.randomUUID();
        queueManager.handlePreLogin(q1, "QueueFirst", "127.0.0.1");
        queueManager.handlePreLogin(q2, "QueueSecond", "127.0.0.1");

        assertEquals(2, queueManager.getQueueSize());

        // Giả lập 1 slot trống bằng cách hủy 1 inFlightLogin
        // và gọi cleanupTick để phân phối slot cho người #1 (QueueFirst)
        String customYaml = """
            queue:
              enabled: true
              max-players-override: 4
              keep-alive-seconds: 300
              slot-reservation-seconds: 60
              rate-limit-seconds: 0
            """;
        config.load(YamlConfiguration.loadConfiguration(new StringReader(customYaml)));
        queueManager.cleanupTick();

        // Kiểm tra QueueFirst (#1) đã có reservation
        assertEquals(1, queueManager.getReservedSlotCount(), "Phải có 1 slot được cấp cho người đứng đầu");

        // Người #2 (QueueSecond) cố tình kết nối trước
        QueueManager.LoginDecision d2 = queueManager.handlePreLogin(q2, "QueueSecond", "127.0.0.1");
        assertEquals(QueueManager.LoginResultType.IN_QUEUE, d2.type(), "Người #2 không được cướp slot của người #1");

        // Người #1 (QueueFirst) kết nối lại -> Cho phép vào thành công!
        QueueManager.LoginDecision d1 = queueManager.handlePreLogin(q1, "QueueFirst", "127.0.0.1");
        assertEquals(QueueManager.LoginResultType.ALLOWED_RESERVED, d1.type(), "Người #1 phải được vào bằng slot giữ chỗ");
    }

    @Test
    @DisplayName("Case 5: Hệ thống phân hạng ưu tiên (Priority / VIP Queue)")
    public void testPriorityQueue() {
        // Lấp đầy server
        for (int i = 0; i < 3; i++) {
            queueManager.handlePreLogin(UUID.randomUUID(), "P" + i, "127.0.0.1");
        }

        UUID normalUser = UUID.randomUUID();
        queueManager.handlePreLogin(normalUser, "NormalUser", "127.0.0.1");

        UUID vipUser = UUID.randomUUID();
        permissions.set(vipUser, false, 10);
        QueueManager.LoginDecision vipDecision = queueManager.handlePreLogin(vipUser, "VipUser", "127.0.0.1");
        assertEquals(1, vipDecision.position().position());
        assertEquals(10, vipDecision.position().priority());
        assertEquals(vipUser, queueManager.getQueueList().getFirst().getUuid());
    }

    @Test
    @DisplayName("Case 6: Chống spam kết nối (Rate-Limiting)")
    public void testRateLimiting() {
        UUID u = UUID.randomUUID();
        queueManager.handlePreLogin(u, "Spammer", "127.0.0.1");

        // Kết nối lại ngay lập tức (dưới 1s rate limit)
        QueueManager.LoginDecision d2 = queueManager.handlePreLogin(u, "Spammer", "127.0.0.1");
        assertEquals(QueueManager.LoginResultType.RATE_LIMITED, d2.type(), "Phải bị rate-limit khi reconnect dưới 1s");
    }

    @Test
    @DisplayName("Case 7: Quyền Bypass vượt qua hàng chờ khi server đầy")
    public void testBypassPermission() {
        // Lấp đầy server
        for (int i = 0; i < 3; i++) {
            queueManager.handlePreLogin(UUID.randomUUID(), "P" + i, "127.0.0.1");
        }

        // Thêm 2 người vào hàng chờ
        queueManager.handlePreLogin(UUID.randomUUID(), "Q1", "127.0.0.1");
        queueManager.handlePreLogin(UUID.randomUUID(), "Q2", "127.0.0.1");

        // Admin có quyền bypass
        UUID adminUuid = UUID.randomUUID();
        // Quyền được resolve trước admission, kể cả UUID chưa từng join.
        permissions.set(adminUuid, true, 0);

        QueueManager.LoginDecision decision = queueManager.handlePreLogin(adminUuid, "Admin", "127.0.0.1");
        assertEquals(QueueManager.LoginResultType.ALLOWED_BYPASS, decision.type(), "Admin có quyền bypass phải vào được ngay");
    }

    @Test
    @DisplayName("Case 8: Kiểm thử tải bất đồng bộ đa luồng (Multi-threaded Stress Test 50 threads)")
    public void testConcurrentStress() throws InterruptedException {
        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successfulDecisions = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int id = i;
            executor.submit(() -> {
                try {
                    UUID u = UUID.randomUUID();
                    QueueManager.LoginDecision dec = queueManager.handlePreLogin(u, "StressUser" + id, "127.0.0.1");
                    if (dec != null && dec.type() != null) {
                        successfulDecisions.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(threads, successfulDecisions.get(), "Toàn bộ 50 luồng đồng thời phải được xử lý an toàn không gặp exception");
        assertEquals(3, queueManager.getInFlightCount(), "Admission must never exceed configured capacity");
        assertEquals(47, queueManager.getQueueSize());
    }

    @Test
    @DisplayName("Capacity: concurrent attempts for the same UUID create only one in-flight login")
    public void testDuplicateUuidCannotDoubleAdmit() throws InterruptedException {
        config.load(YamlConfiguration.loadConfiguration(new StringReader("""
            queue:
              max-players-override: 3
              rate-limit-seconds: 0
              max-queue-size: 100
            """)));
        UUID uuid = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<QueueManager.LoginResultType> results = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    results.add(queueManager.handlePreLogin(uuid, "SamePlayer", "127.0.0.1").type());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, results.stream().filter(type -> type == QueueManager.LoginResultType.ALLOWED_DIRECT).count());
        assertEquals(1, queueManager.getInFlightCount());
    }

    @Test
    @DisplayName("Reservation: an expired holder moves behind the next player")
    public void testExpiredReservationAdvancesQueue() throws InterruptedException {
        config.load(YamlConfiguration.loadConfiguration(new StringReader("""
            queue:
              max-players-override: 1
              keep-alive-seconds: 30
              slot-reservation-seconds: 1
              rate-limit-seconds: 0
              max-queue-size: 10
            """)));
        UUID occupant = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        queueManager.handlePreLogin(occupant, "Occupant", "127.0.0.1");
        queueManager.handlePreLogin(first, "First", "127.0.0.2");
        queueManager.handlePreLogin(second, "Second", "127.0.0.3");
        queueManager.releaseInFlight(occupant);
        queueManager.cleanupTick();
        assertEquals(first, queueManager.getQueueList().get(0).getUuid());
        assertTrue(queueManager.getQueueList().get(0).hasActiveReservation());

        clock.advance(1100L);
        queueManager.cleanupTick();
        assertEquals(second, queueManager.getQueueList().get(0).getUuid());
        assertTrue(queueManager.getQueueList().get(0).hasActiveReservation());
    }

    @Test
    @DisplayName("Anti-abuse: random UUID tracking remains bounded")
    public void testRandomUuidTrackingIsBounded() {
        config.load(YamlConfiguration.loadConfiguration(new StringReader("""
            queue:
              max-players-override: 1
              rate-limit-seconds: 0
              max-queue-size: 10
              anti-bot:
                enabled: false
            """)));
        for (int i = 0; i < 10_000; i++) {
            queueManager.handlePreLogin(UUID.randomUUID(), "Bot" + i, "192.0.2.1");
        }
        assertTrue(queueManager.getTrackedRateLimitCount() <= 4096);
        assertTrue(queueManager.getQueueSize() <= 10);
    }

    @Test
    @DisplayName("Reservation count is reconciled when capacity shrinks")
    public void testCapacityShrinkRevokesExcessReservations() {
        for (int i = 0; i < 3; i++) {
            queueManager.handlePreLogin(UUID.randomUUID(), "Online" + i, "127.0.0.1");
        }
        queueManager.handlePreLogin(UUID.randomUUID(), "First", "127.0.0.2");
        queueManager.handlePreLogin(UUID.randomUUID(), "Second", "127.0.0.3");

        config.load(YamlConfiguration.loadConfiguration(new StringReader("""
            queue:
              max-players-override: 5
              slot-reservation-seconds: 60
              rate-limit-seconds: 0
              max-queue-size: 100
            """)));
        queueManager.cleanupTick();
        assertEquals(2, queueManager.getReservedSlotCount());

        config.load(YamlConfiguration.loadConfiguration(new StringReader("""
            queue:
              max-players-override: 3
              slot-reservation-seconds: 60
              rate-limit-seconds: 0
              max-queue-size: 100
            """)));
        queueManager.cleanupTick();
        assertEquals(0, queueManager.getReservedSlotCount());
    }

}
