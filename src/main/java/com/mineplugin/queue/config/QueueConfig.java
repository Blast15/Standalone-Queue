package com.mineplugin.queue.config;

import com.mineplugin.queue.model.QueuePosition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;

/**
 * Quản lý cấu hình plugin và định dạng thông điệp.
 *
 * @author BlastSama
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
    private boolean antiBotEnabled;
    private long antiBotWindowSeconds;
    private int antiBotMaxAttemptsPerIp;
    private int antiBotMaxQueuedPerIp;
    private int antiBotGlobalAttemptsPerWindow;
    private Boolean antiBotRuntimeOverride;

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
        this.maxPlayersOverride = Math.max(-1, config.getInt("queue.max-players-override", -1));
        this.keepAliveSeconds = positiveOrDefault(config.getLong("queue.keep-alive-seconds", 300L), 300L);
        this.slotReservationSeconds = positiveOrDefault(config.getLong("queue.slot-reservation-seconds", 60L), 60L);
        this.rateLimitSeconds = Math.max(0L, config.getLong("queue.rate-limit-seconds", 2L));
        this.maxQueueSize = positiveOrDefault(config.getInt("queue.max-queue-size", 500), 500);
        this.debugLog = config.getBoolean("queue.debug-log", false);
        this.antiBotEnabled = config.getBoolean("queue.anti-bot.enabled", false);
        this.antiBotWindowSeconds = Math.max(1L, config.getLong("queue.anti-bot.window-seconds", 10L));
        this.antiBotMaxAttemptsPerIp = positiveOrDefault(config.getInt("queue.anti-bot.max-attempts-per-ip", 20), 20);
        this.antiBotMaxQueuedPerIp = positiveOrDefault(config.getInt("queue.anti-bot.max-queued-per-ip", 5), 5);
        this.antiBotGlobalAttemptsPerWindow = positiveOrDefault(config.getInt("queue.anti-bot.global-attempts-per-window", 100), 100);

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

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized int getMaxPlayersOverride() {
        return maxPlayersOverride;
    }

    public synchronized long getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    public synchronized long getKeepAliveMs() {
        return toMillisSaturated(keepAliveSeconds);
    }

    public synchronized long getSlotReservationSeconds() {
        return slotReservationSeconds;
    }

    public synchronized long getSlotReservationMs() {
        return toMillisSaturated(slotReservationSeconds);
    }

    public synchronized long getRateLimitSeconds() {
        return rateLimitSeconds;
    }

    public synchronized long getRateLimitMs() {
        return toMillisSaturated(rateLimitSeconds);
    }

    private static long toMillisSaturated(long seconds) {
        return seconds > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : seconds * 1000L;
    }

    private static long positiveOrDefault(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    private static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    public synchronized int getMaxQueueSize() {
        return maxQueueSize;
    }

    public synchronized boolean isDebugLog() {
        return debugLog;
    }

    public synchronized boolean isAntiBotEnabled() { return antiBotRuntimeOverride != null ? antiBotRuntimeOverride : antiBotEnabled; }
    public synchronized void setAntiBotRuntimeOverride(Boolean enabled) { this.antiBotRuntimeOverride = enabled; }
    public synchronized void clearRuntimeOverrides() { this.antiBotRuntimeOverride = null; }
    public synchronized long getAntiBotWindowMs() { return toMillisSaturated(antiBotWindowSeconds); }
    public synchronized int getAntiBotMaxAttemptsPerIp() { return antiBotMaxAttemptsPerIp; }
    public synchronized int getAntiBotMaxQueuedPerIp() { return antiBotMaxQueuedPerIp; }
    public synchronized int getAntiBotGlobalAttemptsPerWindow() { return antiBotGlobalAttemptsPerWindow; }

    public synchronized Map<String, Integer> getPermissionPriorities() {
        return Map.copyOf(permissionPriorities);
    }

    /**
     * Chuyển đổi chuỗi thành Adventure Component hỗ trợ cả MiniMessage lẫn ký hiệu '&' và '§' cổ điển một cách an toàn.
     */
    public Component parseText(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        if (text.contains("&") || text.contains("§")) {
            text = convertLegacyToMiniMessage(text);
        }
        try {
            return miniMessage.deserialize(text);
        } catch (Exception e) {
            return legacySerializer.deserialize(text);
        }
    }

    private String convertLegacyToMiniMessage(String text) {
        if (text.contains("§")) {
            text = text.replace("§", "&");
        }
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
    public synchronized Component formatKickInQueue(QueuePosition pos, int onlineCount, int maxCapacity) {
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

    public synchronized Component formatKickSlotReady() {
        return parseText(rawKickSlotReady);
    }

    public synchronized Component formatKickRateLimited() {
        return parseText(rawKickRateLimited);
    }

    public synchronized Component formatKickQueueFull(int total) {
        return parseText(rawKickQueueFull.replace("<total>", String.valueOf(total)));
    }

    public synchronized Component formatKickReservationExpired(long seconds) {
        return parseText(rawKickReservationExpired.replace("<time>", String.valueOf(seconds)));
    }

    public synchronized Component getAdminMessage(String key, String... replacements) {
        String msg = adminMessages.getOrDefault(key, "<red>Missing message: " + key + "</red>");
        for (int i = 0; i < replacements.length - 1; i += 2) {
            String placeholder = replacements[i];
            String val = replacements[i + 1];
            msg = msg.replace(placeholder, val != null ? val : "");
        }
        return parseText(rawPrefix + msg);
    }

    public synchronized Component getRawAdminMessage(String key, String... replacements) {
        String msg = adminMessages.getOrDefault(key, "<red>Missing message: " + key + "</red>");
        for (int i = 0; i < replacements.length - 1; i += 2) {
            String placeholder = replacements[i];
            String val = replacements[i + 1];
            msg = msg.replace(placeholder, val != null ? val : "");
        }
        return parseText(msg);
    }
}
