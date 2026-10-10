package com.mineplugin.queue.logging;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.QueueManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;
import java.util.Arrays;

/** Log4j suppression uses both trusted logger identity and an exact recent plugin disconnect. */
public class Log4jQueueFilter extends AbstractFilter {
    private final QueueConfig config;
    private final QueueManager queueManager;

    public Log4jQueueFilter(QueueConfig config, QueueManager queueManager) {
        this.config = config;
        this.queueManager = queueManager;
    }

    private Result decide(String loggerName, Level level, Message message, Throwable thrown) {
        if (!ConsoleFilterManager.acceptsSource(loggerName, config)) return Result.NEUTRAL;
        if (level == null || level.isMoreSpecificThan(Level.WARN) || thrown != null || message == null
                || message.getThrowable() != null) return Result.NEUTRAL;
        Object[] parameters = message.getParameters();
        if (parameters != null && Arrays.stream(parameters).anyMatch(Throwable.class::isInstance)) return Result.NEUTRAL;
        return ConsoleFilterManager.isQueueMessage(loggerName, message.getFormattedMessage(), config, queueManager)
                ? Result.DENY : Result.NEUTRAL;
    }

    @Override public Result filter(LogEvent event) {
        return event == null ? Result.NEUTRAL : decide(event.getLoggerName(), event.getLevel(), event.getMessage(), event.getThrown());
    }

    @Override public Result filter(Logger logger, Level level, Marker marker, Message message, Throwable thrown) {
        return decide(logger == null ? null : logger.getName(), level, message, thrown);
    }

    @Override public Result filter(Logger logger, Level level, Marker marker, Object message, Throwable thrown) {
        if (level == null || level.isMoreSpecificThan(Level.WARN) || thrown != null
                || !ConsoleFilterManager.acceptsSource(logger == null ? null : logger.getName(), config)) return Result.NEUTRAL;
        if (message instanceof Throwable) return Result.NEUTRAL;
        Message value = message instanceof Message typed ? typed : new org.apache.logging.log4j.message.SimpleMessage(String.valueOf(message));
        return decide(logger == null ? null : logger.getName(), level, value, thrown);
    }

    @Override public Result filter(Logger logger, Level level, Marker marker, String message, Object... params) {
        if (level == null || level.isMoreSpecificThan(Level.WARN)
                || !ConsoleFilterManager.acceptsSource(logger == null ? null : logger.getName(), config)) return Result.NEUTRAL;
        if (params != null && Arrays.stream(params).anyMatch(Throwable.class::isInstance)) return Result.NEUTRAL;
        return decide(logger == null ? null : logger.getName(), level, new ParameterizedMessage(message, params), null);
    }
}
