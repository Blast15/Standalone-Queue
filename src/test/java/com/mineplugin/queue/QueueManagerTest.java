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
        queueManager = new QueueManager(null, config);
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
        Thread.sleep(1100);

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

        // Thêm VIP user (giả lập quyền VIP có priority = 10)
        UUID vipUser = UUID.randomUUID();
        QueuedPlayer vipPlayer = new QueuedPlayer(vipUser, "VipUser", "127.0.0.1", 10);

        // Cho VIP vào hàng chờ
        queueManager.getQueueList(); // Ensure initialized
        // Sử dụng reflection hoặc add trực tiếp qua priority cache
        // QueueManager ưu tiên tính theo compareTo
        QueuedPlayer normalPlayer = queueManager.getQueueList().get(0);

        assertTrue(vipPlayer.compareTo(normalPlayer) < 0, "VIP (prio 10) phải đứng trước người thường (prio 0)");
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
        // Giả lập quyền bypass
        // QueueManager kiểm tra permissionCache
        queueManager.cachePlayerPermissions(createMockPlayer(adminUuid, true));

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
    }

    private org.bukkit.entity.Player createMockPlayer(UUID uuid, boolean hasBypass) {
        // Simple dynamic proxy for Bukkit Player interface to mock permissions
        return (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class},
                (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) {
                        return uuid;
                    }
                    if ("hasPermission".equals(method.getName()) && args.length > 0) {
                        String perm = (String) args[0];
                        if ("queue.bypass".equals(perm)) {
                            return hasBypass;
                        }
                        return Boolean.FALSE;
                    }
                    return null;
                }
        );
    }
}
