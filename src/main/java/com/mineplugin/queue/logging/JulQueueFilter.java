package com.mineplugin.queue.logging;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.QueueManager;
import java.util.logging.Filter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.Arrays;

/** JUL suppression is opt-in and source-aware; warnings and throwable parameters always survive. */
public class JulQueueFilter implements Filter {
    private final QueueConfig config;
    private final QueueManager queueManager;
    private static final Formatter FORMATTER = new Formatter() {
        @Override public String format(LogRecord record) { return formatMessage(record); }
    };

    public JulQueueFilter(QueueConfig config, QueueManager queueManager) {
        this.config = config;
        this.queueManager = queueManager;
    }

    @Override
    public boolean isLoggable(LogRecord record) {
        if (record == null || record.getThrown() != null
                || record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) return true;
        if (!ConsoleFilterManager.acceptsSource(record.getLoggerName(), config)) return true;
        Object[] params = record.getParameters();
        if (params != null && Arrays.stream(params).anyMatch(Throwable.class::isInstance)) return true;
        return !ConsoleFilterManager.isQueueMessage(record.getLoggerName(), FORMATTER.format(record), config, queueManager);
    }
}
