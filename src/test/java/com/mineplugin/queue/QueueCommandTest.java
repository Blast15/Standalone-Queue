package com.mineplugin.queue;

import com.mineplugin.queue.command.QueueCommand;
import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.QueueManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử toàn diện cho lệnh quản trị hàng chờ /queue (QueueCommand).
 */
public class QueueCommandTest {

    private QueueConfig config;
    private QueueManager queueManager;
    private QueueCommand commandExecutor;
    private List<String> sentMessages;
    private CommandSender adminSender;
    private CommandSender nonAdminSender;
    private Command mockCommand;

    @BeforeEach
    public void setup() {
        String yaml = """
            queue:
              enabled: true
              max-players-override: 5
              keep-alive-seconds: 300
              slot-reservation-seconds: 60
              rate-limit-seconds: 0
              max-queue-size: 100
              debug-log: false
            messages:
              prefix: "[HÀNG CHỜ] "
              admin:
                no-permission: "Bạn không có quyền!"
                reloaded: "Đã tải lại cấu hình!"
                cleared: "Đã xóa toàn bộ hàng chờ: <count> người"
                kicked-success: "Đã đá <player> khỏi hàng chờ"
                player-not-found: "Không tìm thấy <player>"
                status-header: "=== TRẠNG THÁI HÀNG CHỜ ==="
                status-line-online: "Online: <online>/<max> (Override: <override>)"
                status-line-queue: "Đang xếp hàng: <queue_size> người (Giữ: <reserved_count>)"
                list-header: "=== DANH SÁCH (<count>) ==="
                list-entry: "<pos>. <player> | Priority: <prio> | Ping: <last_ping> | Status: <status>"
            """;
        YamlConfiguration yamlConfig = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        config = new QueueConfig(yamlConfig);
        queueManager = new QueueManager(null, config);
        commandExecutor = new QueueCommand(null, queueManager, config);

        sentMessages = new ArrayList<>();

        adminSender = (CommandSender) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> {
                    if ("hasPermission".equals(method.getName())) {
                        return true;
                    }
                    if ("sendMessage".equals(method.getName()) && args != null && args.length > 0) {
                        if (args[0] instanceof net.kyori.adventure.text.Component c) {
                            sentMessages.add(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().serialize(c));
                        } else {
                            sentMessages.add(String.valueOf(args[0]));
                        }
                        return null;
                    }
                    return null;
                }
        );

        nonAdminSender = (CommandSender) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> {
                    if ("hasPermission".equals(method.getName())) {
                        return false;
                    }
                    if ("sendMessage".equals(method.getName()) && args != null && args.length > 0) {
                        if (args[0] instanceof net.kyori.adventure.text.Component c) {
                            sentMessages.add(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().serialize(c));
                        } else {
                            sentMessages.add(String.valueOf(args[0]));
                        }
                        return null;
                    }
                    return null;
                }
        );

        mockCommand = new Command("queue") {
            @Override
            public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                return false;
            }
        };
    }

    @Test
    @DisplayName("Command Test 1: Người chơi không có quyền sẽ bị từ chối")
    public void testNoPermission() {
        boolean result = commandExecutor.onCommand(nonAdminSender, mockCommand, "queue", new String[]{"status"});
        assertTrue(result);
        assertEquals(1, sentMessages.size());
        assertTrue(sentMessages.get(0).contains("Bạn không có quyền!"));
    }

    @Test
    @DisplayName("Command Test 2: /queue help hiển thị trợ giúp")
    public void testHelpCommand() {
        boolean result = commandExecutor.onCommand(adminSender, mockCommand, "queue", new String[]{"help"});
        assertTrue(result);
        assertFalse(sentMessages.isEmpty());
        boolean hasHelpEntry = sentMessages.stream().anyMatch(m -> m.contains("/queue status"));
        assertTrue(hasHelpEntry, "Trợ giúp phải chứa lệnh /queue status");
    }

    @Test
    @DisplayName("Command Test 3: /queue status hiển thị thông tin chính xác")
    public void testStatusCommand() {
        boolean result = commandExecutor.onCommand(adminSender, mockCommand, "queue", new String[]{"status"});
        assertTrue(result);
        assertEquals(3, sentMessages.size());
        assertTrue(sentMessages.get(0).contains("TRẠNG THÁI HÀNG CHỜ"));
        assertTrue(sentMessages.get(1).contains("Online:"));
        assertTrue(sentMessages.get(2).contains("Đang xếp hàng:"));
    }

    @Test
    @DisplayName("Command Test 4: /queue list khi hàng chờ trống và có người")
    public void testListCommand() {
        // Hàng chờ trống
        commandExecutor.onCommand(adminSender, mockCommand, "queue", new String[]{"list"});
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("hàng chờ đang trống")));

        sentMessages.clear();

        // Lấp đầy server và cho 2 người vào hàng chờ
        for (int i = 0; i < 5; i++) {
            queueManager.handlePreLogin(UUID.randomUUID(), "Online" + i, "127.0.0.1");
        }
        queueManager.handlePreLogin(UUID.randomUUID(), "Alice", "127.0.0.1");
        queueManager.handlePreLogin(UUID.randomUUID(), "Bob", "127.0.0.1");

        commandExecutor.onCommand(adminSender, mockCommand, "queue", new String[]{"list"});
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("Alice")));
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("Bob")));
    }

    @Test
    @DisplayName("Command Test 5: /queue kick thành công và khi không tìm thấy người chơi")
    public void testKickCommand() {
        // Điền đầy server và thêm người vào hàng chờ
        for (int i = 0; i < 5; i++) {
            queueManager.handlePreLogin(UUID.randomUUID(), "Online" + i, "127.0.0.1");
        }
        queueManager.handlePreLogin(UUID.randomUUID(), "TargetPlayer", "127.0.0.1");

        // Kick thiếu đối số
        sentMessages.clear();
        commandExecutor.onCommand(adminSender, mockCommand, "queue", new String[]{"kick"});
        assertTrue(sentMessages.get(0).contains("Cách dùng:"));

        // Kick người không tồn tại
        sentMessages.clear();
        commandExecutor.onCommand(adminSender, mockCommand, "queue", new String[]{"kick", "GhostUser"});
        assertTrue(sentMessages.get(0).contains("Không tìm thấy GhostUser"));

        // Kick thành công
        sentMessages.clear();
        commandExecutor.onCommand(adminSender, mockCommand, "queue", new String[]{"kick", "TargetPlayer"});
        assertTrue(sentMessages.get(0).contains("Đã đá TargetPlayer"));
        assertFalse(queueManager.isInQueue("TargetPlayer"));
    }

    @Test
    @DisplayName("Command Test 6: /queue clear xóa toàn bộ hàng chờ")
    public void testClearCommand() {
        for (int i = 0; i < 5; i++) {
            queueManager.handlePreLogin(UUID.randomUUID(), "Online" + i, "127.0.0.1");
        }
        queueManager.handlePreLogin(UUID.randomUUID(), "P1", "127.0.0.1");
        queueManager.handlePreLogin(UUID.randomUUID(), "P2", "127.0.0.1");
        assertEquals(2, queueManager.getQueueSize());

        sentMessages.clear();
        commandExecutor.onCommand(adminSender, mockCommand, "queue", new String[]{"clear"});
        assertEquals(0, queueManager.getQueueSize());
        assertTrue(sentMessages.get(0).contains("Đã xóa toàn bộ hàng chờ: 2 người"));
    }

    @Test
    @DisplayName("Command Test 7: Tab completion trả về gợi ý chính xác")
    public void testTabCompletion() {
        // Không có quyền
        List<String> noPermTabs = commandExecutor.onTabComplete(nonAdminSender, mockCommand, "queue", new String[]{""});
        assertTrue(noPermTabs.isEmpty());

        // Gợi ý lệnh cấp 1
        List<String> tabs1 = commandExecutor.onTabComplete(adminSender, mockCommand, "queue", new String[]{"s"});
        assertEquals(List.of("status"), tabs1);

        List<String> tabsAll = commandExecutor.onTabComplete(adminSender, mockCommand, "queue", new String[]{""});
        assertTrue(tabsAll.contains("status"));
        assertTrue(tabsAll.contains("list"));
        assertTrue(tabsAll.contains("clear"));
        assertTrue(tabsAll.contains("kick"));
        assertTrue(tabsAll.contains("reload"));

        // Gợi ý tên người chơi khi gõ /queue kick
        for (int i = 0; i < 5; i++) {
            queueManager.handlePreLogin(UUID.randomUUID(), "Online" + i, "127.0.0.1");
        }
        queueManager.handlePreLogin(UUID.randomUUID(), "Charlie", "127.0.0.1");
        queueManager.handlePreLogin(UUID.randomUUID(), "Chris", "127.0.0.1");

        List<String> kickTabs = commandExecutor.onTabComplete(adminSender, mockCommand, "queue", new String[]{"kick", "ch"});
        assertTrue(kickTabs.contains("Charlie"));
        assertTrue(kickTabs.contains("Chris"));
    }
}
