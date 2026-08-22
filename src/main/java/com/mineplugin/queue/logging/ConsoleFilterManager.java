package com.mineplugin.queue.logging;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.QueueManager;
import org.bukkit.Bukkit;

import java.util.logging.Handler;
import java.util.logging.Logger;

/**
 * Quản lý bộ lọc log console (Log4j2 + Java Util Logging).
 * Tự động tắt/ẩn toàn bộ thông báo ngắt kết nối, xếp hàng, kick message rác trên console
 * khi debug-log = false (mặc định), giữ cho console luôn sạch sẽ.
 */
public class ConsoleFilterManager {

    private final QueueConfig config;
    private final QueueManager queueManager;

    private Log4jQueueFilter log4jFilter;
    private JulQueueFilter julFilter;

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

            Logger rootJul = Logger.getLogger("");
            rootJul.setFilter(julFilter);
            for (Handler handler : rootJul.getHandlers()) {
                handler.setFilter(julFilter);
            }

            if (Bukkit.getServer() != null) {
                Bukkit.getLogger().setFilter(julFilter);
            }
        } catch (Throwable ignored) {
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
        }
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
            for (var player : queueManager.getQueueList()) {
                if (text.contains(player.getUsername())) {
                    return true;
                }
            }
        }

        return false;
    }
}
