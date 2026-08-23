package com.mineplugin.queue.logging;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.QueueManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

/**
 * Bộ lọc Log4j2 cho server Paper / Minecraft console.
 *
 * @author BlastSama
 */
public class Log4jQueueFilter extends AbstractFilter {

    private final QueueConfig config;
    private final QueueManager queueManager;

    public Log4jQueueFilter(QueueConfig config, QueueManager queueManager) {
        this.config = config;
        this.queueManager = queueManager;
    }

    private boolean shouldFilter(String msg) {
        return ConsoleFilterManager.isQueueMessage(msg, config, queueManager);
    }

    @Override
    public Result filter(LogEvent event) {
        if (event == null || (config != null && config.isDebugLog())) {
            return Result.NEUTRAL;
        }
        String msg = event.getMessage() != null ? event.getMessage().getFormattedMessage() : null;
        if (shouldFilter(msg)) {
            return Result.DENY;
        }
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        if (msg != null && shouldFilter(msg.getFormattedMessage())) {
            return Result.DENY;
        }
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        if (msg != null && shouldFilter(msg.toString())) {
            return Result.DENY;
        }
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        if (shouldFilter(msg)) {
            return Result.DENY;
        }
        if (params != null && config != null && !config.isDebugLog()) {
            for (Object p : params) {
                if (p != null && shouldFilter(p.toString())) {
                    return Result.DENY;
                }
            }
        }
        return Result.NEUTRAL;
    }
}
