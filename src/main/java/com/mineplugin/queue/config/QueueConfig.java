package com.mineplugin.queue.config;

import com.mineplugin.queue.model.QueuePosition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;

/**
 * Quản lý cấu hình plugin và định dạng thông điệp MiniMessage.
 */
public class QueueConfig {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();

    private boolean enabled;
    private int maxPlayersOverride;
    private long keepAliveSeconds;
    private long slotReservationSeconds;
    private long rateLimitSeconds;
    private int maxQueueSize;
    private boolean debugLog;

    private final Map<String, Integer> permissionPriorities = new HashMap<>();

    private String rawPrefix;
    private String rawKickInQueue;
    private String rawKickSlotReady;
    private String rawKickRateLimited;
    private String rawKickQueueFull;
    private String rawKickReservationExpired;

    private final Map<String, String> adminMessages = new HashMap<>();

    public QueueConfig(FileConfiguration config) {
        load(config);
    }

    /**
     * Tải lại các giá trị cấu hình từ FileConfiguration.
     */
    public synchronized void load(FileConfiguration config) {
        this.enabled = config.getBoolean("queue.enabled", true);
        this.maxPlayersOverride = config.getInt("queue.max-players-override", -1);
        this.keepAliveSeconds = config.getLong("queue.keep-alive-seconds", 300L);
        this.slotReservationSeconds = config.getLong("queue.slot-reservation-seconds", 60L);
        this.rateLimitSeconds = config.getLong("queue.rate-limit-seconds", 2L);
        this.maxQueueSize = config.getInt("queue.max-queue-size", 500);
        this.debugLog = config.getBoolean("queue.debug-log", false);

        // Load Priorities
        permissionPriorities.clear();
        ConfigurationSection prioSection = config.getConfigurationSection("priorities");
        if (prioSection != null) {
            for (String key : prioSection.getKeys(false)) {
                String permission = prioSection.getString(key + ".permission");
                int weight = prioSection.getInt(key + ".weight", 0);
                if (permission != null && !permission.isEmpty()) {
                    permissionPriorities.put(permission.toLowerCase(), weight);
                }
            }
        }

        // Load Raw Messages
        this.rawPrefix = config.getString("messages.prefix", "<gold>[HÀNG CHỜ]</gold> ");
        this.rawKickInQueue = config.getString("messages.kick-in-queue", "Bạn đang ở vị trí #<position> trong hàng chờ.");
        this.rawKickSlotReady = config.getString("messages.kick-slot-ready", "Đã đến lượt bạn! Bấm Reconnect để vào.");
        this.rawKickRateLimited = config.getString("messages.kick-rate-limited", "Vui lòng không kết nối quá nhanh!");
        this.rawKickQueueFull = config.getString("messages.kick-queue-full", "Hàng chờ đã đầy!");
        this.rawKickReservationExpired = config.getString("messages.kick-reservation-expired", "Lượt của bạn đã hết hạn!");

        // Load Admin Messages
        adminMessages.clear();
        ConfigurationSection adminSection = config.getConfigurationSection("messages.admin");
        if (adminSection != null) {
            for (String key : adminSection.getKeys(false)) {
                adminMessages.put(key, adminSection.getString(key, ""));
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxPlayersOverride() {
        return maxPlayersOverride;
    }

    public long getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    public long getKeepAliveMs() {
        return keepAliveSeconds * 1000L;
    }

    public long getSlotReservationSeconds() {
        return slotReservationSeconds;
    }

    public long getSlotReservationMs() {
        return slotReservationSeconds * 1000L;
    }

    public long getRateLimitSeconds() {
        return rateLimitSeconds;
    }

    public long getRateLimitMs() {
        return rateLimitSeconds * 1000L;
    }

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    public boolean isDebugLog() {
        return debugLog;
    }

    public Map<String, Integer> getPermissionPriorities() {
        return Collections.unmodifiableMap(permissionPriorities);
    }

    /**
     * Chuyển đổi chuỗi thành Adventure Component hỗ trợ cả MiniMessage lẫn ký hiệu '&' cổ điển một cách an toàn.
     */
    public Component parseText(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        if (text.contains("&")) {
            text = convertLegacyToMiniMessage(text);
        }
        try {
            return miniMessage.deserialize(text);
        } catch (Exception e) {
            return legacySerializer.deserialize(text);
        }
    }

    private String convertLegacyToMiniMessage(String text) {
        return text
                .replace("&0", "<black>")
                .replace("&1", "<dark_blue>")
                .replace("&2", "<dark_green>")
                .replace("&3", "<dark_aqua>")
                .replace("&4", "<dark_red>")
                .replace("&5", "<dark_purple>")
                .replace("&6", "<gold>")
                .replace("&7", "<gray>")
                .replace("&8", "<dark_gray>")
                .replace("&9", "<blue>")
                .replace("&a", "<green>")
                .replace("&b", "<aqua>")
                .replace("&c", "<red>")
                .replace("&d", "<light_purple>")
                .replace("&e", "<yellow>")
                .replace("&f", "<white>")
                .replace("&k", "<obfuscated>")
                .replace("&l", "<bold>")
                .replace("&m", "<strikethrough>")
                .replace("&n", "<underlined>")
                .replace("&o", "<italic>")
                .replace("&r", "<reset>");
    }

    /**
     * Định dạng thông điệp đá ra màn hình ngắt kết nối cho người đang xếp hàng.
     */
    public Component formatKickInQueue(QueuePosition pos, int onlineCount, int maxCapacity) {
        String msg = rawKickInQueue
                .replace("<position>", String.valueOf(pos.position()))
                .replace("<total>", String.valueOf(pos.total()))
                .replace("<online>", String.valueOf(onlineCount))
                .replace("<max>", String.valueOf(maxCapacity))
                .replace("<time_left>", pos.getFormattedTimeLeft())
                .replace("<priority>", String.valueOf(pos.priority()))
                .replace("<eta>", pos.getFormattedEta());

        return parseText(msg);
    }

    public Component formatKickSlotReady() {
        return parseText(rawKickSlotReady);
    }

    public Component formatKickRateLimited() {
        return parseText(rawKickRateLimited);
    }

    public Component formatKickQueueFull(int total) {
        return parseText(rawKickQueueFull.replace("<total>", String.valueOf(total)));
    }

    public Component formatKickReservationExpired(long seconds) {
        return parseText(rawKickReservationExpired.replace("<time>", String.valueOf(seconds)));
    }

    public Component getAdminMessage(String key, String... replacements) {
        String msg = adminMessages.getOrDefault(key, "<red>Missing message: " + key + "</red>");
        for (int i = 0; i < replacements.length - 1; i += 2) {
            String placeholder = replacements[i];
            String val = replacements[i + 1];
            msg = msg.replace(placeholder, val != null ? val : "");
        }
        return parseText(rawPrefix + msg);
    }

    public Component getRawAdminMessage(String key, String... replacements) {
        String msg = adminMessages.getOrDefault(key, "<red>Missing message: " + key + "</red>");
        for (int i = 0; i < replacements.length - 1; i += 2) {
            String placeholder = replacements[i];
            String val = replacements[i + 1];
            msg = msg.replace(placeholder, val != null ? val : "");
        }
        return parseText(msg);
    }
}
