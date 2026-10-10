package com.mineplugin.queue.config;

import org.bukkit.configuration.file.FileConfiguration;
import java.util.*;

/** A completely parsed settings generation. Construction never changes the active config. */
public record QueueSettings(
        boolean enabled,
        int maxPlayersOverride,
        long keepAliveSeconds,
        long slotReservationSeconds,
        long rateLimitSeconds,
        int maxQueueSize,
        boolean debugLog,
        boolean antiBotEnabled,
        long antiBotWindowSeconds,
        int antiBotMaxAttemptsPerIp,
        int antiBotMaxQueuedPerIp,
        int antiBotGlobalAttemptsPerWindow,
        long permissionResolveTimeoutMs,
        long inFlightTimeoutSeconds,
        boolean suppressQueueNoise,
        Map<String, Integer> permissionPriorities,
        Map<String, String> messages,
        Map<String, String> adminMessages) {

    public QueueSettings {
        permissionPriorities = Map.copyOf(permissionPriorities);
        messages = Map.copyOf(messages);
        adminMessages = Map.copyOf(adminMessages);
    }

    public static QueueSettings parse(FileConfiguration file) {
        return parse(file, true);
    }

    public static QueueSettings read(java.io.File source)
            throws java.io.IOException, org.bukkit.configuration.InvalidConfigurationException {
        var file = new org.bukkit.configuration.file.YamlConfiguration();
        // Report syntax and I/O errors; loadConfiguration() silently falls back to an empty config.
        file.load(source);
        return parse(file);
    }

    static QueueSettings parse(FileConfiguration file, boolean strict) {
        Objects.requireNonNull(file, "file");
        if (strict) validate(file);
        Map<String, Integer> priorities = new HashMap<>();
        var section = file.getConfigurationSection("priorities");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String node = section.getString(key + ".permission");
                int weight = section.getInt(key + ".weight", 0);
                if (node == null || node.isBlank() || weight < 0) {
                    if (strict) throw new IllegalArgumentException("priorities." + key + ": permission/weight không hợp lệ");
                    continue;
                }
                node = node.toLowerCase(Locale.ROOT);
                if (strict && priorities.containsKey(node)) throw new IllegalArgumentException("Permission trùng: " + node);
                priorities.put(node, weight);
            }
        }
        Map<String, String> messages = new HashMap<>();
        messages.put("prefix", file.getString("messages.prefix", "<gold>[HÀNG CHỜ]</gold> "));
        messages.put("kick-in-queue", file.getString("messages.kick-in-queue", "Bạn đang ở vị trí #<position> trong hàng chờ."));
        messages.put("kick-slot-ready", file.getString("messages.kick-slot-ready", "Đã đến lượt bạn! Bấm Reconnect để vào."));
        messages.put("kick-rate-limited", file.getString("messages.kick-rate-limited", "Vui lòng không kết nối quá nhanh!"));
        messages.put("kick-queue-full", file.getString("messages.kick-queue-full", "Hàng chờ đã đầy!"));
        messages.put("kick-reservation-expired", file.getString("messages.kick-reservation-expired", "Lượt giữ slot đã hết hạn. Bạn đang ở vị trí #<position>/<total>."));
        messages.put("kick-login-timeout", file.getString("messages.kick-login-timeout", "Kết nối đăng nhập đã hết hạn. Vui lòng kết nối lại."));
        messages.put("kick-admission-required", file.getString("messages.kick-admission-required", "Kết nối chưa được cấp slot hợp lệ. Vui lòng kết nối lại."));
        Map<String, String> admin = new HashMap<>();
        admin.put("reloading", "<gray>Đang kiểm tra cấu hình và làm mới permission...</gray>");
        admin.put("reloaded", "<green>Đã tải lại cấu hình và điều hòa hàng chờ.</green>");
        admin.put("reload-failed", "<red>Reload thất bại, giữ cấu hình cũ: <reason></red>");
        admin.put("status-line-in-flight", "<gray>In-flight: <in_flight> | Trễ đang giữ slot: <timed_out> | Tổng timeout: <timeout_count></gray>");
        var adminSection = file.getConfigurationSection("messages.admin");
        if (adminSection != null) {
            for (String key : adminSection.getKeys(false)) {
                if (strict && !adminSection.isString(key)) throw new IllegalArgumentException("messages.admin." + key + " phải là chuỗi");
                admin.put(key, adminSection.getString(key, ""));
            }
        }
        return new QueueSettings(
                file.getBoolean("queue.enabled", true),
                Math.max(-1, file.getInt("queue.max-players-override", -1)),
                positive(file.getLong("queue.keep-alive-seconds", 300L), 300L),
                positive(file.getLong("queue.slot-reservation-seconds", 60L), 60L),
                Math.max(0L, file.getLong("queue.rate-limit-seconds", 2L)),
                positive(file.getInt("queue.max-queue-size", 500), 500),
                file.getBoolean("queue.debug-log", false),
                file.getBoolean("queue.anti-bot.enabled", false),
                positive(file.getLong("queue.anti-bot.window-seconds", 10L), 10L),
                positive(file.getInt("queue.anti-bot.max-attempts-per-ip", 20), 20),
                positive(file.getInt("queue.anti-bot.max-queued-per-ip", 5), 5),
                positive(file.getInt("queue.anti-bot.global-attempts-per-window", 100), 100),
                Math.clamp(file.getLong("permissions.resolve-timeout-ms", 1000L), 1L, 5000L),
                Math.clamp(file.getLong("queue.in-flight-timeout-seconds", 30L), 5L, 300L),
                file.getBoolean("logging.suppress-queue-noise", false),
                priorities, messages, admin);
    }

    private static void validate(FileConfiguration file) {
        if (file.contains("queue.enabled") && !file.isBoolean("queue.enabled")) throw new IllegalArgumentException("queue.enabled phải là boolean");
        number(file, "queue.max-players-override", -1L, Integer.MAX_VALUE);
        number(file, "queue.keep-alive-seconds", 1L, Long.MAX_VALUE / 1000L);
        number(file, "queue.slot-reservation-seconds", 1L, Long.MAX_VALUE / 1000L);
        number(file, "queue.rate-limit-seconds", 0L, Long.MAX_VALUE / 1000L);
        number(file, "queue.max-queue-size", 1L, Integer.MAX_VALUE);
        if (file.contains("queue.debug-log") && !file.isBoolean("queue.debug-log")) throw new IllegalArgumentException("queue.debug-log phải là boolean");
        if (file.contains("queue.anti-bot.enabled") && !file.isBoolean("queue.anti-bot.enabled")) throw new IllegalArgumentException("queue.anti-bot.enabled phải là boolean");
        number(file, "queue.anti-bot.window-seconds", 1L, Long.MAX_VALUE / 1000L);
        number(file, "queue.anti-bot.max-attempts-per-ip", 1L, Integer.MAX_VALUE);
        number(file, "queue.anti-bot.max-queued-per-ip", 1L, Integer.MAX_VALUE);
        number(file, "queue.anti-bot.global-attempts-per-window", 1L, Integer.MAX_VALUE);
        number(file, "permissions.resolve-timeout-ms", 1L, 5000L);
        number(file, "queue.in-flight-timeout-seconds", 5L, 300L);
        if (file.contains("logging.suppress-queue-noise") && !file.isBoolean("logging.suppress-queue-noise")) throw new IllegalArgumentException("logging.suppress-queue-noise phải là boolean");
        if (file.getLong("queue.max-players-override", -1) == 0) throw new IllegalArgumentException("queue.max-players-override phải là -1 hoặc số dương");
        for (String section : List.of("queue", "queue.anti-bot", "permissions", "logging", "priorities", "messages", "messages.admin")) {
            if (file.contains(section) && !file.isConfigurationSection(section)) throw new IllegalArgumentException(section + " phải là section");
        }
        var priorities = file.getConfigurationSection("priorities");
        if (priorities != null) {
            for (String key : priorities.getKeys(false)) {
                if (!priorities.isConfigurationSection(key) || !priorities.isString(key + ".permission")) throw new IllegalArgumentException("priorities." + key + " không hợp lệ");
                number(file, "priorities." + key + ".weight", 0, Integer.MAX_VALUE);
            }
        }
        if (file.contains("messages.prefix") && !file.isString("messages.prefix")) throw new IllegalArgumentException("messages.prefix phải là chuỗi");
        if (file.contains("messages.kick-in-queue") && !file.isString("messages.kick-in-queue")) throw new IllegalArgumentException("messages.kick-in-queue phải là chuỗi");
        if (file.contains("messages.kick-slot-ready") && !file.isString("messages.kick-slot-ready")) throw new IllegalArgumentException("messages.kick-slot-ready phải là chuỗi");
        if (file.contains("messages.kick-rate-limited") && !file.isString("messages.kick-rate-limited")) throw new IllegalArgumentException("messages.kick-rate-limited phải là chuỗi");
        if (file.contains("messages.kick-queue-full") && !file.isString("messages.kick-queue-full")) throw new IllegalArgumentException("messages.kick-queue-full phải là chuỗi");
        if (file.contains("messages.kick-reservation-expired") && !file.isString("messages.kick-reservation-expired")) throw new IllegalArgumentException("messages.kick-reservation-expired phải là chuỗi");
        if (file.contains("messages.kick-login-timeout") && !file.isString("messages.kick-login-timeout")) throw new IllegalArgumentException("messages.kick-login-timeout phải là chuỗi");
        if (file.contains("messages.kick-admission-required") && !file.isString("messages.kick-admission-required")) throw new IllegalArgumentException("messages.kick-admission-required phải là chuỗi");
    }

    private static void number(FileConfiguration file, String key, long min, long max) {
        Object value = file.get(key);
        if (value == null) return;
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()
                || number.longValue() < min || number.longValue() > max) {
            throw new IllegalArgumentException(key + " phải là số nguyên trong " + min + ".." + max);
        }
    }

    private static long positive(long value, long fallback) { return value > 0 ? value : fallback; }
    private static int positive(int value, int fallback) { return value > 0 ? value : fallback; }
}
