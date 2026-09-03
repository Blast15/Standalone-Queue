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

    /**
     * Kiểm tra xem chuỗi log có phải là thông báo ngắt kết nối / hàng chờ cần ẩn hay không.
     */
    public static boolean isQueueMessage(String text, QueueConfig config, QueueManager queueManager) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        if (config != null && config.isDebugLog()) {
            return false;
        }

        // 1. Kiểm tra các từ khóa nhận diện thông báo hàng chờ / kick queue
        if (text.contains("MÁY CHỦ HIỆN ĐÃ ĐẦY")
                || text.contains("MAY CHU HIEN DA DAY")
                || text.contains("trong hàng chờ")
                || text.contains("trong hang cho")
                || text.contains("HÀNG CHỜ")
                || text.contains("HANG CHO")
                || text.contains("ĐÃ ĐẾN LƯỢT CỦA BẠN")
                || text.contains("DA DEN LUOT CUA BAN")
                || text.contains("KẾT NỐI QUÁ NHANH")
                || text.contains("KET NOI QUA NHANH")
                || text.contains("HÀNG CHỜ ĐÃ ĐẦY")
                || text.contains("HANG CHO DA DAY")
                || text.contains("LƯỢT CỦA BẠN ĐÃ BỊ HẾT HẠN")
                || text.contains("LUOT CUA BAN DA BI HET HAN")
                || text.contains("HÃY BẤM KẾT NỐI LẠI")
                || text.contains("HAY BAM KET NOI LAI")
                || text.contains("DUY TRÌ HÀNG CHỜ")
                || text.contains("Trạng thái máy chủ:")
                || text.contains("Trang thai may chu:")
                || text.contains("Bạn đang trong hàng chờ.")
                || text.contains("[Queue]")
                || text.contains("vị trí: #")
                || text.contains("vi tri: #")) {
            return true;
        }

        // 2. Kiểm tra log UUID của người chơi đang trong hàng chờ
        if (text.contains("UUID of player ") && queueManager != null) {
            String[] parts = text.split("UUID of player ");
            if (parts.length > 1) {
                String remainder = parts[1].trim();
                String playerName = remainder.split(" ")[0];
                if (queueManager.isInQueue(playerName)) {
                    return true;
                }
            }
        }

        // 3. Kiểm tra log Disconnecting / lost connection của người chơi trong hàng chờ
        if ((text.contains("Disconnecting ") || text.contains("lost connection:")) && queueManager != null) {
            return queueManager.containsAnyQueuedPlayerName(text);
        }

        return false;
    }
}
