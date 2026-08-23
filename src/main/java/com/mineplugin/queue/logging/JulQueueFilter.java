package com.mineplugin.queue.logging;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.QueueManager;

import java.util.logging.Filter;
import java.util.logging.LogRecord;

/**
 * Bộ lọc java.util.logging cho Bukkit / Paper logger.
 *
 * @author BlastSama
 */
public class JulQueueFilter implements Filter {

    private final QueueConfig config;
    private final QueueManager queueManager;

    public JulQueueFilter(QueueConfig config, QueueManager queueManager) {
        this.config = config;
        this.queueManager = queueManager;
    }

    @Override
    public boolean isLoggable(LogRecord record) {
        if (record == null || (config != null && config.isDebugLog())) {
            return true;
        }

        String msg = record.getMessage();
        if (ConsoleFilterManager.isQueueMessage(msg, config, queueManager)) {
            return false;
        }

        Object[] params = record.getParameters();
        if (params != null) {
            for (Object param : params) {
                if (param != null && ConsoleFilterManager.isQueueMessage(param.toString(), config, queueManager)) {
                    return false;
                }
            }
        }

        return true;
    }
}
