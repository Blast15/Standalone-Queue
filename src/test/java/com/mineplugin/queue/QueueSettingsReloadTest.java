package com.mineplugin.queue;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.config.QueueSettings;
import com.mineplugin.queue.model.QueuePosition;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class QueueSettingsReloadTest {
    @ParameterizedTest @CsvSource({
            "queue.max-players-override,0", "queue.keep-alive-seconds,0", "queue.slot-reservation-seconds,-1",
            "queue.rate-limit-seconds,-1", "queue.max-queue-size,0", "queue.anti-bot.window-seconds,0",
            "queue.anti-bot.max-attempts-per-ip,0", "permissions.resolve-timeout-ms,5001",
            "queue.in-flight-timeout-seconds,4", "queue.in-flight-timeout-seconds,301"})
    void invalidReloadNeverMutatesOldConfigOrRuntimeOverride(String path, long value) {
        var yaml = PhaseTwoRegressionTest.settings(2);
        QueueConfig config = new QueueConfig(yaml);
        var previous = config.snapshot();
        config.setAntiBotRuntimeOverride(true);
        yaml.set(path, value);
        var error = assertThrows(IllegalArgumentException.class, () -> config.load(yaml));
        assertTrue(error.getMessage().contains(path));
        assertSame(previous, config.snapshot());
        assertTrue(config.isAntiBotEnabled());
    }

    @ParameterizedTest @ValueSource(strings = {"queue", "logging", "messages.admin", "queue.enabled", "logging.suppress-queue-noise", "messages.kick-in-queue"})
    void malformedTypesAreRejectedInsteadOfSilentlyUsingDefaults(String path) {
        var yaml = PhaseTwoRegressionTest.settings(2);
        yaml.set(path, 123);
        assertThrows(IllegalArgumentException.class, () -> QueueSettings.parse(yaml));
    }

    @Test void duplicatePermissionsNegativeWeightsAndFractionalDurationsAreRejected() {
        var yaml = PhaseTwoRegressionTest.settings(2);
        yaml.set("priorities.a.permission", "Queue.Priority.VIP");
        yaml.set("priorities.a.weight", 10);
        yaml.set("priorities.b.permission", "queue.priority.vip");
        yaml.set("priorities.b.weight", 20);
        assertThrows(IllegalArgumentException.class, () -> QueueSettings.parse(yaml));
        yaml.set("priorities.b", null);
        yaml.set("priorities.a.weight", -1);
        assertThrows(IllegalArgumentException.class, () -> QueueSettings.parse(yaml));
        yaml.set("priorities", null);
        yaml.set("queue.in-flight-timeout-seconds", 30.5);
        assertThrows(IllegalArgumentException.class, () -> QueueSettings.parse(yaml));
    }

    @Test void generationsAndMapsAreImmutableAndBoundaryTimeoutsAreValid() {
        var yaml = PhaseTwoRegressionTest.settings(2);
        yaml.set("priorities.vip.permission", "queue.priority.vip");
        yaml.set("priorities.vip.weight", 10);
        yaml.set("queue.in-flight-timeout-seconds", 5);
        var snapshot = QueueSettings.parse(yaml);
        yaml.set("priorities.vip.weight", 50);
        yaml.set("queue.in-flight-timeout-seconds", 300);
        assertEquals(10, snapshot.permissionPriorities().get("queue.priority.vip"));
        assertEquals(5, snapshot.inFlightTimeoutSeconds());
        assertEquals(300, QueueSettings.parse(yaml).inFlightTimeoutSeconds());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.permissionPriorities().put("bad", 1));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.messages().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.adminMessages().clear());
    }

    @ParameterizedTest @ValueSource(strings = {"<red>", "&c", "§c"})
    void expiredAndWaitingTemplatesResolveIdenticalPlaceholdersWithAllColorFormats(String color) {
        var yaml = PhaseTwoRegressionTest.settings(2);
        String template = color + "#<position>/<total> <online>/<max> <time_left> p=<priority> eta=<eta> t=<time>";
        yaml.set("messages.kick-in-queue", template);
        yaml.set("messages.kick-reservation-expired", template);
        QueueConfig config = new QueueConfig(yaml);
        QueuePosition position = new QueuePosition(2, 10, 240, false, 20);
        var serializer = PlainTextComponentSerializer.plainText();
        String waiting = serializer.serialize(config.formatKickInQueue(position, 1, 2));
        String expired = serializer.serialize(config.formatKickReservationExpired(position, 1, 2));
        assertEquals(waiting, expired);
        assertTrue(expired.startsWith("#2/10 1/2 4m 00s p=20 eta="));
        assertTrue(expired.endsWith(" t=60"));
        assertFalse(expired.contains("<"));
    }
    @Test void malformedYamlAndMissingFileCannotBecomeAnEmptySuccessfulReload(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var file = directory.resolve("config.yml");
        java.nio.file.Files.writeString(file, "queue: [unterminated");
        assertThrows(org.bukkit.configuration.InvalidConfigurationException.class, () -> QueueSettings.read(file.toFile()));
        assertThrows(java.io.IOException.class, () -> QueueSettings.read(directory.resolve("missing.yml").toFile()));
    }

}
