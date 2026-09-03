package com.mineplugin.queue;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.logging.ConsoleFilterManager;
import com.mineplugin.queue.logging.JulQueueFilter;
import com.mineplugin.queue.logging.Log4jQueueFilter;
import com.mineplugin.queue.manager.QueueManager;
import org.apache.logging.log4j.core.Filter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test cho console filter.
 *
 * @author BlastSama
 */
public class ConsoleFilterTest {

    @Test
    @DisplayName("Filter Test 1: debug-log = false ẩn toàn bộ log disconnect & kick của hàng chờ")
    public void testFilterSilencesQueueLogsWhenDebugDisabled() {
        String yaml = """
            queue:
              enabled: true
              max-players-override: 1
              debug-log: false
            messages:
              kick-in-queue: "⭐ MÁY CHỦ HIỆN ĐÃ ĐẦY ⭐"
            """;
        YamlConfiguration yamlConfig = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        QueueConfig config = new QueueConfig(yamlConfig);
        QueueManager queueManager = new QueueManager(null, config);

        // Lấp đầy slot máy chủ
        queueManager.handlePreLogin(UUID.randomUUID(), "PlayerInServer", "127.0.0.1");

        // Giả lập người chơi KenBlackLive bị đưa vào hàng chờ
        UUID u1 = UUID.randomUUID();
        queueManager.handlePreLogin(u1, "KenBlackLive", "14.191.173.44");

        // Các log rác trên console khi người chơi bị kick vào hàng chờ
        String logDisconnecting = "Disconnecting KenBlackLive (/14.191.173.44:16236): ⭐ MÁY CHỦ HIỆN ĐÃ ĐẦY ⭐\\nTrạng thái máy chủ: 1/1 người chơi";
        String logLostConnection = "KenBlackLive (/14.191.173.44:16236) lost connection: ⭐ MÁY CHỦ HIỆN ĐÃ ĐẦY ⭐";
        String logInQueue = "Bạn đang ở vị trí: #1 / 1 trong hàng chờ";
        String logReconnectNotice = "👉 HÃY BẤM KẾT NỐI LẠI TRONG VÒNG 5 PHÚT ĐỂ DUY TRÌ HÀNG CHỜ!";
        String logUuid = "UUID of player KenBlackLive is " + u1;

        assertTrue(ConsoleFilterManager.isQueueMessage(logDisconnecting, config, queueManager));
        assertTrue(ConsoleFilterManager.isQueueMessage(logLostConnection, config, queueManager));
        assertTrue(ConsoleFilterManager.isQueueMessage(logInQueue, config, queueManager));
        assertTrue(ConsoleFilterManager.isQueueMessage(logReconnectNotice, config, queueManager));
        assertTrue(ConsoleFilterManager.isQueueMessage(logUuid, config, queueManager));

        // Kiểm thử với Log4j Filter
        Log4jQueueFilter log4jFilter = new Log4jQueueFilter(config, queueManager);
        assertEquals(Filter.Result.DENY, log4jFilter.filter(null, org.apache.logging.log4j.Level.INFO, null, (Object) logDisconnecting, null));
        assertEquals(Filter.Result.DENY, log4jFilter.filter(null, org.apache.logging.log4j.Level.INFO, null, (Object) logLostConnection, null));

        // Kiểm thử với JUL Filter
        JulQueueFilter julFilter = new JulQueueFilter(config, queueManager);
        LogRecord record = new LogRecord(Level.INFO, logDisconnecting);
        assertFalse(julFilter.isLoggable(record), "JUL filter phải chặn log disconnect hàng chờ");
    }

    @Test
    @DisplayName("Filter Test 2: debug-log = true cho phép hiển thị tất cả log")
    public void testFilterAllowsLogsWhenDebugEnabled() {
        String yaml = """
            queue:
              enabled: true
              debug-log: true
            """;
        YamlConfiguration yamlConfig = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        QueueConfig config = new QueueConfig(yamlConfig);
        QueueManager queueManager = new QueueManager(null, config);

        String logDisconnecting = "Disconnecting KenBlackLive (/14.191.173.44:16236): ⭐ MÁY CHỦ HIỆN ĐÃ ĐẦY ⭐";

        assertFalse(ConsoleFilterManager.isQueueMessage(logDisconnecting, config, queueManager),
                "Khi debug-log = true, không được lọc bỏ tin nhắn");

        Log4jQueueFilter log4jFilter = new Log4jQueueFilter(config, queueManager);
        assertEquals(Filter.Result.NEUTRAL, log4jFilter.filter(null, org.apache.logging.log4j.Level.INFO, null, (Object) logDisconnecting, null));

        JulQueueFilter julFilter = new JulQueueFilter(config, queueManager);
        LogRecord record = new LogRecord(Level.INFO, logDisconnecting);
        assertTrue(julFilter.isLoggable(record), "Khi debug-log = true, JUL filter phải cho phép log");
    }

    @Test
    @DisplayName("Filter Test 3: Không chặn các log bình thường của server")
    public void testNormalServerLogsAreNotFiltered() {
        String yaml = """
            queue:
              enabled: true
              debug-log: false
            """;
        YamlConfiguration yamlConfig = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        QueueConfig config = new QueueConfig(yamlConfig);
        QueueManager queueManager = new QueueManager(null, config);

        String normalQuit = "KenBlackLive left the game";
        String normalJoin = "KenBlackLive joined the game";
        String normalChat = "<KenBlackLive> Hello everyone!";
        String normalCommand = "KenBlackLive issued server command: /help";

        assertFalse(ConsoleFilterManager.isQueueMessage(normalQuit, config, queueManager));
        assertFalse(ConsoleFilterManager.isQueueMessage(normalJoin, config, queueManager));
        assertFalse(ConsoleFilterManager.isQueueMessage(normalChat, config, queueManager));
        assertFalse(ConsoleFilterManager.isQueueMessage(normalCommand, config, queueManager));
    }

    @Test
    @DisplayName("Console filter never hides warnings or exceptions")
    public void testWarningsAndExceptionsAreNeverFiltered() {
        QueueConfig config = new QueueConfig(YamlConfiguration.loadConfiguration(new StringReader("""
            queue:
              debug-log: false
            """)));
        QueueManager queueManager = new QueueManager(null, config);
        String queueText = "HÀNG CHỜ ĐÃ ĐẦY";

        JulQueueFilter jul = new JulQueueFilter(config, queueManager);
        LogRecord warning = new LogRecord(Level.WARNING, queueText);
        assertTrue(jul.isLoggable(warning));
        LogRecord failure = new LogRecord(Level.INFO, queueText);
        failure.setThrown(new IllegalStateException("boom"));
        assertTrue(jul.isLoggable(failure));

        Log4jQueueFilter log4j = new Log4jQueueFilter(config, queueManager);
        assertEquals(Filter.Result.NEUTRAL,
                log4j.filter(null, org.apache.logging.log4j.Level.ERROR, null, (Object) queueText, null));
        assertEquals(Filter.Result.NEUTRAL,
                log4j.filter(null, org.apache.logging.log4j.Level.INFO, null, (Object) queueText,
                        new IllegalStateException("boom")));
    }

    @Test
    @DisplayName("Config Test: Thông báo kick-in-queue mặc định không còn dòng 'Thời gian giữ vị trí' và 'Ước tính chờ'")
    public void testDefaultConfigMessageDoesNotContainRemovedLines() {
        java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertNotNull(is, "File config.yml trong resources phải tồn tại");

        java.util.Scanner scanner = new java.util.Scanner(is, java.nio.charset.StandardCharsets.UTF_8).useDelimiter("\\A");
        String content = scanner.hasNext() ? scanner.next() : "";

        assertFalse(content.contains("Thời gian giữ vị trí:"), "config.yml không được chứa dòng 'Thời gian giữ vị trí'");
        assertFalse(content.contains("Ước tính chờ:"), "config.yml không được chứa dòng 'Ước tính chờ'");
        assertTrue(content.contains("Bạn đang ở vị trí:"), "config.yml vẫn phải chứa dòng vị trí trong hàng chờ");
    }
}
