package com.mineplugin.queue;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.logging.ConsoleFilterManager;
import com.mineplugin.queue.logging.JulQueueFilter;
import com.mineplugin.queue.logging.Log4jQueueFilter;
import com.mineplugin.queue.manager.QueueManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import static org.junit.jupiter.api.Assertions.*;

class ConsoleFilterTest {
    private static final String SOURCE = "net.minecraft.server.network.ServerLoginPacketListenerImpl";
    private static final String REASON = "⭐ MÁY CHỦ HIỆN ĐÃ ĐẦY ⭐\nVị trí: #1/2";
    private static final String TEXT = "Disconnecting KenBlackLive (/192.0.2.1:16236): " + REASON;
    private final MutableClock clock = new MutableClock();
    private final YamlConfiguration yaml = new YamlConfiguration();
    private final QueueConfig config = new QueueConfig(yaml);
    private final QueueManager manager = new QueueManager(null, config, new FakePermissionResolver(clock), clock);

    private void enable() {
        yaml.set("logging.suppress-queue-noise", true);
        config.load(yaml);
        manager.recordQueueDisconnect("KenBlackLive", "192.0.2.1", REASON);
    }

    private LogRecord record(Level level, String text) {
        LogRecord record = new LogRecord(level, text);
        record.setLoggerName(SOURCE);
        return record;
    }

    private Log4jLogEvent log4j(org.apache.logging.log4j.Level level, String source, String text, Throwable thrown) {
        return Log4jLogEvent.newBuilder().setLoggerName(source).setLevel(level)
                .setMessage(new SimpleMessage(text)).setThrown(thrown).build();
    }

    @Test void suppressionDefaultsOffEvenWithMatchingDisconnect() {
        manager.recordQueueDisconnect("KenBlackLive", "192.0.2.1", REASON);
        assertTrue(new JulQueueFilter(config, manager).isLoggable(record(Level.INFO, TEXT)));
        assertEquals(Filter.Result.NEUTRAL, new Log4jQueueFilter(config, manager)
                .filter(log4j(org.apache.logging.log4j.Level.INFO, SOURCE, TEXT, null)));
    }

    @Test void onlyExactRecentPluginDisconnectsAreSuppressedInBothBackends() {
        enable();
        assertTrue(ConsoleFilterManager.isQueueMessage(SOURCE, TEXT, config, manager));
        assertFalse(new JulQueueFilter(config, manager).isLoggable(record(Level.INFO, TEXT)));
        assertEquals(Filter.Result.DENY, new Log4jQueueFilter(config, manager)
                .filter(log4j(org.apache.logging.log4j.Level.INFO, SOURCE, TEXT, null)));
        assertTrue(ConsoleFilterManager.isQueueMessage(SOURCE,
                "KenBlackLive (/192.0.2.1:16236) lost connection: " + REASON, config, manager));
        clock.advance(5_000);
        assertFalse(ConsoleFilterManager.isQueueMessage(SOURCE, TEXT, config, manager));
    }

    @ParameterizedTest @ValueSource(strings = {"AuthMe", "AnotherPlugin", "Minecraft", "", "com.mineplugin.queue"})
    void unrelatedOrUnknownLoggerNeverSuppressed(String source) {
        enable();
        assertFalse(ConsoleFilterManager.isQueueMessage(source, TEXT, config, manager));
        assertEquals(Filter.Result.NEUTRAL, new Log4jQueueFilter(config, manager)
                .filter(log4j(org.apache.logging.log4j.Level.INFO, source, TEXT, null)));
        LogRecord record = record(Level.INFO, TEXT);
        record.setLoggerName(source);
        assertTrue(new JulQueueFilter(config, manager).isLoggable(record));
    }

    @Test void banOtherAddressNameSubstringAndUnstructuredQueueTextNeverMatch() {
        enable();
        for (String text : new String[] {TEXT.replace(REASON, "You are banned"), TEXT.replace("192.0.2.1", "192.0.2.2"),
                TEXT.replace("KenBlackLive", "KenBlackLive2"), "[Queue] storage failed", "UUID of player KenBlackLive is 123",
                "<KenBlackLive> " + REASON, "KenBlackLive joined the game", REASON}) {
            assertFalse(ConsoleFilterManager.isQueueMessage(SOURCE, text, config, manager), text);
        }
        assertFalse(ConsoleFilterManager.isQueueMessage(null, TEXT, config, manager));
    }

    @Test void debugTraceAndSuppressionAreIndependent() {
        enable();
        yaml.set("queue.debug-log", true);
        config.load(yaml);
        assertTrue(ConsoleFilterManager.isQueueMessage(SOURCE, TEXT, config, manager));
        assertFalse(ConsoleFilterManager.isQueueMessage("com.mineplugin.queue", "[Queue] attempt=abc action=login-failed", config, manager));
    }

    @Test void warningsErrorsThrownAndThrowableParametersAlwaysSurvive() {
        enable();
        JulQueueFilter jul = new JulQueueFilter(config, manager);
        for (Level level : new Level[] {Level.WARNING, Level.SEVERE}) assertTrue(jul.isLoggable(record(level, TEXT)));
        LogRecord thrown = record(Level.INFO, TEXT);
        thrown.setThrown(new IllegalStateException("boom"));
        assertTrue(jul.isLoggable(thrown));
        LogRecord parameters = record(Level.INFO, TEXT);
        parameters.setParameters(new Object[] {new IllegalStateException("boom")});
        assertTrue(jul.isLoggable(parameters));
        Log4jQueueFilter filter = new Log4jQueueFilter(config, manager);
        for (var level : new org.apache.logging.log4j.Level[] {org.apache.logging.log4j.Level.WARN, org.apache.logging.log4j.Level.ERROR}) {
            assertEquals(Filter.Result.NEUTRAL, filter.filter(log4j(level, SOURCE, TEXT, null)));
        }
        assertEquals(Filter.Result.NEUTRAL, filter.filter(log4j(org.apache.logging.log4j.Level.INFO, SOURCE, TEXT, new RuntimeException("boom"))));
        var embedded = Log4jLogEvent.newBuilder().setLoggerName(SOURCE).setLevel(org.apache.logging.log4j.Level.INFO)
                .setMessage(new ParameterizedMessage(TEXT, new Object[] {new RuntimeException("boom")})).build();
        assertEquals(Filter.Result.NEUTRAL, filter.filter(embedded));
        assertEquals(Filter.Result.NEUTRAL, filter.filter(null, org.apache.logging.log4j.Level.INFO, null, TEXT,
                new Object[] {new RuntimeException("boom")}));
    }

    @Test void julFormatsParametersBeforeMatchingAndDoesNotInspectParametersSeparately() {
        enable();
        JulQueueFilter jul = new JulQueueFilter(config, manager);
        LogRecord match = record(Level.INFO, "Disconnecting {0} (/{1}:16236): {2}");
        match.setParameters(new Object[] {"KenBlackLive", "192.0.2.1", REASON});
        assertFalse(jul.isLoggable(match));
        LogRecord other = record(Level.INFO, "Some unrelated message {0}");
        other.setParameters(new Object[] {TEXT});
        assertTrue(jul.isLoggable(other));
    }

    @Test void ipv6AddressMatchesExactly() {
        enable();
        manager.recordQueueDisconnect("KenBlackLive", "2001:db8:0:0:0:0:0:1", REASON);
        assertTrue(ConsoleFilterManager.isQueueMessage(SOURCE,
                "Disconnecting KenBlackLive (/2001:db8:0:0:0:0:0:1:16236): " + REASON, config, manager));
    }

    @Test void defaultConfigMessageKeepsPositionWithoutRemovedEtaLines() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(input);
            String content = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(content.contains("Thời gian giữ vị trí:"));
            assertFalse(content.contains("Ước tính chờ:"));
            assertTrue(content.contains("Bạn đang ở vị trí:"));
        }
    }
    @Test void log4jObjectMessageAndParameterizedOverloadsRespectSourceAndSkipWarningFormatting() {
        enable();
        var logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(SOURCE);
        var filter = new Log4jQueueFilter(config, manager);
        assertEquals(Filter.Result.DENY, filter.filter(logger, org.apache.logging.log4j.Level.INFO, null, (Object) TEXT, null));
        assertEquals(Filter.Result.DENY, filter.filter(logger, org.apache.logging.log4j.Level.INFO, null, new SimpleMessage(TEXT), null));
        assertEquals(Filter.Result.DENY, filter.filter(logger, org.apache.logging.log4j.Level.INFO, null,
                "Disconnecting {} (/{}:16236): {}", "KenBlackLive", "192.0.2.1", REASON));
        Object unsafeToFormat = new Object() { @Override public String toString() { throw new AssertionError("Do not format warnings/unknown sources"); } };
        assertEquals(Filter.Result.NEUTRAL, filter.filter(logger, org.apache.logging.log4j.Level.WARN, null, unsafeToFormat, null));
        assertEquals(Filter.Result.NEUTRAL, filter.filter(null, org.apache.logging.log4j.Level.INFO, null, unsafeToFormat, null));
        assertEquals(Filter.Result.NEUTRAL, filter.filter(logger, org.apache.logging.log4j.Level.INFO, null,
                TEXT, new Object[] {new RuntimeException("boom")}));
    }

}
