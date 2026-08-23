package com.mineplugin.queue;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.model.QueuePosition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test cho QueueConfig.
 *
 * @author BlastSama
 */
public class QueueConfigTest {

    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @Test
    @DisplayName("Config Test 1: Đọc cấu hình mặc định và các giá trị hợp lệ")
    public void testLoadConfigValues() {
        String yaml = """
            queue:
              enabled: true
              max-players-override: 50
              keep-alive-seconds: 300
              slot-reservation-seconds: 45
              rate-limit-seconds: 3
              max-queue-size: 200
              debug-log: true
            priorities:
              vip:
                permission: "queue.priority.vip"
                weight: 10
              staff:
                permission: "queue.priority.staff"
                weight: 50
            """;
        YamlConfiguration yamlConfig = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        QueueConfig config = new QueueConfig(yamlConfig);

        assertTrue(config.isEnabled());
        assertEquals(50, config.getMaxPlayersOverride());
        assertEquals(300L, config.getKeepAliveSeconds());
        assertEquals(300_000L, config.getKeepAliveMs());
        assertEquals(45L, config.getSlotReservationSeconds());
        assertEquals(45_000L, config.getSlotReservationMs());
        assertEquals(3L, config.getRateLimitSeconds());
        assertEquals(3_000L, config.getRateLimitMs());
        assertEquals(200, config.getMaxQueueSize());
        assertTrue(config.isDebugLog());

        assertEquals(10, config.getPermissionPriorities().get("queue.priority.vip"));
        assertEquals(50, config.getPermissionPriorities().get("queue.priority.staff"));
    }

    @Test
    @DisplayName("Config Test: Chuẩn hóa giá trị âm và chống tràn số mili-giây")
    public void testInvalidDurationValuesAreNormalized() {
        String yaml = """
            queue:
              max-players-override: -20
              keep-alive-seconds: -1
              slot-reservation-seconds: -2
              rate-limit-seconds: 9223372036854775807
              max-queue-size: -10
            """;
        QueueConfig config = new QueueConfig(YamlConfiguration.loadConfiguration(new StringReader(yaml)));

        assertEquals(-1, config.getMaxPlayersOverride());
        assertEquals(0L, config.getKeepAliveSeconds());
        assertEquals(0L, config.getSlotReservationSeconds());
        assertEquals(0L, config.getMaxQueueSize());
        assertEquals(Long.MAX_VALUE, config.getRateLimitMs());
    }

    @Test
    @DisplayName("Config Test 2: Chuyển đổi mã màu MiniMessage và Legacy (& và §)")
    public void testColorParsing() {
        String yaml = "queue:\n  enabled: true\n";
        QueueConfig config = new QueueConfig(YamlConfiguration.loadConfiguration(new StringReader(yaml)));

        // MiniMessage tag
        Component c1 = config.parseText("<red>Hello <bold>World</bold></red>");
        assertNotNull(c1);
        String s1 = miniMessage.serialize(c1);
        assertTrue(s1.contains("Hello") && s1.contains("World"));

        // Legacy & color code
        Component c2 = config.parseText("&aGreen &lBold");
        assertNotNull(c2);
        String s2 = legacySerializer.serialize(c2);
        assertTrue(s2.contains("Green") && s2.contains("Bold"));

        // Legacy § section sign color code
        Component c3 = config.parseText("§cRed §bAqua");
        assertNotNull(c3);
        String s3 = legacySerializer.serialize(c3);
        assertTrue(s3.contains("Red") && s3.contains("Aqua"));

        // Chuỗi rỗng / null
        assertEquals(Component.empty(), config.parseText(null));
        assertEquals(Component.empty(), config.parseText(""));
    }

    @Test
    @DisplayName("Config Test 3: Định dạng các thông điệp kick kèm placeholder")
    public void testFormatKickMessages() {
        String yaml = """
            messages:
              prefix: "[PREFIX] "
              kick-in-queue: "Vị trí: #<position>/<total> | Online: <online>/<max> | Time: <time_left> | Prio: <priority> | ETA: <eta>"
              kick-slot-ready: "Slot của bạn đã sẵn sàng!"
              kick-rate-limited: "Chậm lại nào!"
              kick-queue-full: "Hàng chờ full: <total>"
              kick-reservation-expired: "Hết hạn: <time>s"
            """;
        QueueConfig config = new QueueConfig(YamlConfiguration.loadConfiguration(new StringReader(yaml)));

        QueuePosition pos = new QueuePosition(2, 10, 240, false, 20);
        Component kickMsg = config.formatKickInQueue(pos, 45, 50);
        String plain = legacySerializer.serialize(kickMsg);

        assertTrue(plain.contains("Vị trí: #2/10"));
        assertTrue(plain.contains("Online: 45/50"));
        assertTrue(plain.contains("Time: 4m 00s"));
        assertTrue(plain.contains("Prio: 20"));

        // Slot ready
        assertTrue(legacySerializer.serialize(config.formatKickSlotReady()).contains("Slot của bạn đã sẵn sàng!"));

        // Rate limited
        assertTrue(legacySerializer.serialize(config.formatKickRateLimited()).contains("Chậm lại nào!"));

        // Queue full
        assertTrue(legacySerializer.serialize(config.formatKickQueueFull(100)).contains("Hàng chờ full: 100"));

        // Reservation expired
        assertTrue(legacySerializer.serialize(config.formatKickReservationExpired(60)).contains("Hết hạn: 60s"));
    }
}
