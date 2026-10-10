package com.mineplugin.queue.config;

import com.mineplugin.queue.model.QueuePosition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

    private volatile QueueSettings settings;
    private Boolean antiBotRuntimeOverride;

    public QueueConfig(FileConfiguration config) {
        // Preserve startup fallback behavior for old config files; reload is strictly validated.
        settings = QueueSettings.parse(config, false);
    }

    public QueueSettings snapshot() { return settings; }

    public synchronized void load(FileConfiguration file) {
        publish(QueueSettings.parse(file));
    }

    public synchronized void publish(QueueSettings candidate) {
        settings = Objects.requireNonNull(candidate, "candidate");
        antiBotRuntimeOverride = null;
    }

    public synchronized boolean isEnabled() { return settings.enabled(); }
    public synchronized int getMaxPlayersOverride() { return settings.maxPlayersOverride(); }
    public synchronized long getKeepAliveSeconds() { return settings.keepAliveSeconds(); }
    public synchronized long getSlotReservationSeconds() { return settings.slotReservationSeconds(); }
    public synchronized long getRateLimitSeconds() { return settings.rateLimitSeconds(); }
    public synchronized int getMaxQueueSize() { return settings.maxQueueSize(); }
    public synchronized boolean isDebugLog() { return settings.debugLog(); }
    public synchronized boolean isAntiBotEnabled() { return antiBotRuntimeOverride != null ? antiBotRuntimeOverride : settings.antiBotEnabled(); }
    public synchronized long getAntiBotWindowSeconds() { return settings.antiBotWindowSeconds(); }
    public synchronized int getAntiBotMaxAttemptsPerIp() { return settings.antiBotMaxAttemptsPerIp(); }
    public synchronized int getAntiBotMaxQueuedPerIp() { return settings.antiBotMaxQueuedPerIp(); }
    public synchronized int getAntiBotGlobalAttemptsPerWindow() { return settings.antiBotGlobalAttemptsPerWindow(); }
    public synchronized long getPermissionResolveTimeoutMs() { return settings.permissionResolveTimeoutMs(); }
    public synchronized long getInFlightTimeoutSeconds() { return settings.inFlightTimeoutSeconds(); }
    public synchronized boolean isSuppressQueueNoise() { return settings.suppressQueueNoise(); }
    public synchronized Map<String, Integer> getPermissionPriorities() { return settings.permissionPriorities(); }
    public synchronized long getKeepAliveMs() { return toMillisSaturated(settings.keepAliveSeconds()); }
    public synchronized long getSlotReservationMs() { return toMillisSaturated(settings.slotReservationSeconds()); }
    public synchronized long getRateLimitMs() { return toMillisSaturated(settings.rateLimitSeconds()); }
    public synchronized long getAntiBotWindowMs() { return toMillisSaturated(settings.antiBotWindowSeconds()); }
    public synchronized long getInFlightTimeoutMs() { return toMillisSaturated(settings.inFlightTimeoutSeconds()); }
    public synchronized void setAntiBotRuntimeOverride(Boolean enabled) { antiBotRuntimeOverride = enabled; }
    public synchronized void clearRuntimeOverrides() { antiBotRuntimeOverride = null; }

    private static long toMillisSaturated(long seconds) {
        return seconds > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : seconds * 1000L;
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
        return formatQueueTemplate("kick-in-queue", pos, onlineCount, maxCapacity);
    }

    public synchronized Component formatKickReservationExpired(QueuePosition pos, int onlineCount, int maxCapacity) {
        return formatQueueTemplate("kick-reservation-expired", pos, onlineCount, maxCapacity);
    }

    private Component formatQueueTemplate(String key, QueuePosition pos, int onlineCount, int maxCapacity) {
        String msg = settings.messages().get(key)
                .replace("<position>", String.valueOf(pos.position()))
                .replace("<total>", String.valueOf(pos.total()))
                .replace("<online>", String.valueOf(onlineCount))
                .replace("<max>", String.valueOf(maxCapacity))
                .replace("<time_left>", pos.getFormattedTimeLeft())
                .replace("<priority>", String.valueOf(pos.priority()))
                .replace("<eta>", pos.getFormattedEta())
                .replace("<time>", String.valueOf(settings.slotReservationSeconds()));
        return parseText(msg);
    }

    public synchronized Component formatKickLoginTimeout() {
        return parseText(settings.messages().get("kick-login-timeout"));
    }

    public synchronized Component formatKickAdmissionRequired() {
        return parseText(settings.messages().get("kick-admission-required"));
    }

    /** Deprecated config compatibility; admitted connections are not kicked with a ready message. */
    @Deprecated
    public synchronized Component formatKickSlotReady() {
        return parseText(settings.messages().get("kick-slot-ready"));
    }

    public synchronized Component formatKickRateLimited() {
        return parseText(settings.messages().get("kick-rate-limited"));
    }

    public synchronized Component formatKickQueueFull(int total) {
        return parseText(settings.messages().get("kick-queue-full").replace("<total>", String.valueOf(total)));
    }

    public synchronized Component formatKickReservationExpired(long seconds) {
        return parseText(settings.messages().get("kick-reservation-expired").replace("<time>", String.valueOf(seconds)));
    }

    public synchronized Component getAdminMessage(String key, String... replacements) {
        String msg = settings.adminMessages().getOrDefault(key, "<red>Missing message: " + key + "</red>");
        for (int i = 0; i < replacements.length - 1; i += 2) {
            String placeholder = replacements[i];
            String val = replacements[i + 1];
            msg = msg.replace(placeholder, val != null ? val : "");
        }
        return parseText(settings.messages().get("prefix") + msg);
    }

    public synchronized Component getRawAdminMessage(String key, String... replacements) {
        String msg = settings.adminMessages().getOrDefault(key, "<red>Missing message: " + key + "</red>");
        for (int i = 0; i < replacements.length - 1; i += 2) {
            String placeholder = replacements[i];
            String val = replacements[i + 1];
            msg = msg.replace(placeholder, val != null ? val : "");
        }
        return parseText(msg);
    }
}
