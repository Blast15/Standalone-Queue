package com.mineplugin.queue.logging;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.QueueManager;
import java.util.logging.Handler;
import java.util.logging.Filter;
import java.util.logging.Logger;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Quản lý bộ lọc log console (Log4j2 + Java Util Logging).
 *
 * @author BlastSama
 */
public class ConsoleFilterManager {

    private final QueueConfig config;
    private final QueueManager queueManager;

    private Log4jQueueFilter log4jFilter;
    private JulQueueFilter julFilter;
    private Logger rootJul;
    private Filter originalRootFilter;
    private Filter installedRootFilter;
    private final Map<Handler, Filter> originalHandlerFilters = new IdentityHashMap<>();
    private final Map<Handler, Filter> installedHandlerFilters = new IdentityHashMap<>();

    public ConsoleFilterManager(QueueConfig config, QueueManager queueManager) {
        this.config = config;
        this.queueManager = queueManager;
    }

    /**
     * Đăng ký các bộ lọc log vào Log4j2 và java.util.logging.
     */
    public void register() {
        // 1. Đăng ký Log4j2 Filter (Mojang / Paper console backend)
        try {
            this.log4jFilter = new Log4jQueueFilter(config, queueManager);
            org.apache.logging.log4j.core.Logger rootLogger =
                    (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getRootLogger();
            rootLogger.addFilter(log4jFilter);
        } catch (Throwable ignored) {
            // Không có Log4j2 trên runtime hoặc chạy trong môi trường test
        }

        // 2. Đăng ký Java Util Logging Filter (Bukkit / Server fallback)
        try {
            this.julFilter = new JulQueueFilter(config, queueManager);

            rootJul = Logger.getLogger("");
            originalRootFilter = rootJul.getFilter();
            installedRootFilter = combine(originalRootFilter, julFilter);
            rootJul.setFilter(installedRootFilter);
            for (Handler handler : rootJul.getHandlers()) {
                Filter original = handler.getFilter();
                Filter installed = combine(original, julFilter);
                originalHandlerFilters.put(handler, original);
                installedHandlerFilters.put(handler, installed);
                handler.setFilter(installed);
            }
        } catch (RuntimeException ignored) {
            // Console filtering is optional; a hostile/custom JUL manager must not disable the plugin.
        }
    }

    /**
     * Hủy đăng ký bộ lọc khi plugin tắt.
     */
    public void unregister() {
        try {
            if (log4jFilter != null) {
                log4jFilter.stop();
                org.apache.logging.log4j.core.Logger rootLogger =
                        (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getRootLogger();
                if (rootLogger != null && rootLogger.get() != null) {
                    rootLogger.get().removeFilter(log4jFilter);
                }
            }
        } catch (Throwable ignored) {
            // Log4j may be absent or shutting down; filter cleanup is best-effort during disable.
        }
        if (rootJul != null && rootJul.getFilter() == installedRootFilter) {
            rootJul.setFilter(originalRootFilter);
        }
        for (Map.Entry<Handler, Filter> entry : installedHandlerFilters.entrySet()) {
            Handler handler = entry.getKey();
            if (handler.getFilter() == entry.getValue()) {
                handler.setFilter(originalHandlerFilters.get(handler));
            }
        }
        originalHandlerFilters.clear();
        installedHandlerFilters.clear();
    }

    private static Filter combine(Filter existing, Filter queueFilter) {
        return record -> (existing == null || existing.isLoggable(record)) && queueFilter.isLoggable(record);
    }

    private static final java.util.Set<String> DISCONNECT_LOGGERS = java.util.Set.of(
            "net.minecraft.server.network.ServerLoginPacketListenerImpl",
            "net.minecraft.server.network.ServerGamePacketListenerImpl",
            "net.minecraft.server.network.ServerCommonPacketListenerImpl");
    private static final java.util.regex.Pattern DISCONNECT = java.util.regex.Pattern.compile(
            "^(?:Disconnecting ([A-Za-z0-9_]{1,16}) \\(/(.+):[0-9]+\\): |([A-Za-z0-9_]{1,16}) \\(/(.+):[0-9]+\\) lost connection: )(.*)$",
            java.util.regex.Pattern.DOTALL);

    static boolean acceptsSource(String loggerName, QueueConfig config) {
        if (!DISCONNECT_LOGGERS.contains(loggerName == null ? "" : loggerName)) return false;
        // Logging can be called while the queue lock is held. Never acquire the config monitor here.
        return config != null && config.snapshot().suppressQueueNoise();
    }

    /** Unknown sources/formats fail open. Names and text alone never identify queue noise. */
    public static boolean isQueueMessage(String loggerName, String text, QueueConfig config, QueueManager manager) {
        if (manager == null || text == null || !acceptsSource(loggerName, config)) return false;
        var match = DISCONNECT.matcher(text);
        if (!match.matches()) return false;
        String name = match.group(1) != null ? match.group(1) : match.group(3);
        String ip = match.group(2) != null ? match.group(2) : match.group(4);
        return manager.matchesQueueDisconnect(name, ip, match.group(5));
    }
}
