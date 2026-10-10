# Reproducing the phase 1 defects on the audit baseline

Baseline: `9f220edafaab135873c374beca0d0619bd677585`. Original suite: 33 passing tests. The four additional tests below were run on Java 21 on 2026-10-10 and each failed at the expected assertion (4 failures, 0 errors). They use reflection only to control the old code, which had no injectable clock/resolver. The candidate uses proper fake-clock/resolver tests instead.

In a detached checkout of the baseline, save the following as `src/test/java/com/mineplugin/queue/PhaseOneLegacyReproductionTest.java`, then run:

```sh
mvn -B test -Dtest=PhaseOneLegacyReproductionTest
```

Expected red assertions:

| Reproduction | Expected | Baseline actual |
|---|---|---|
| New VIP versus live lease | `IN_QUEUE` | `ALLOWED_RESERVED` |
| Expired lease before cleanup | `IN_QUEUE` | `ALLOWED_RESERVED` |
| Expired heartbeat before cleanup | Position 2 | Position 1 |
| Mutate returned list entry | Internal priority 0 | Internal priority 999 |

```java
package com.mineplugin.queue;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.QueueManager;
import com.mineplugin.queue.model.QueuedPlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.mineplugin.queue.manager.QueueManager.LoginResultType.*;

class PhaseOneLegacyReproductionTest {
    final QueueManager manager = new QueueManager(null, new QueueConfig(YamlConfiguration.loadConfiguration(new StringReader("""
        queue:
          max-players-override: 1
          rate-limit-seconds: 0
          keep-alive-seconds: 300
          slot-reservation-seconds: 60
        """))));
    UUID attempt(String name) {
        UUID id = UUID.randomUUID();
        manager.handlePreLogin(id, name, "192.0.2.1");
        return id;
    }
    QueuedPlayer player(UUID id) {
        return manager.getQueueList().stream().filter(p -> p.getUuid().equals(id)).findFirst().orElseThrow();
    }
    void setTime(QueuedPlayer p, String field, long time) throws Exception {
        var f = QueuedPlayer.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setLong(p, time);
    }
    @Test void expiredReservationMustLoseItsOldTurnBeforeCleanup() throws Exception {
        UUID occupant = attempt("Occupant");
        UUID first = attempt("First");
        UUID second = attempt("Second");
        manager.releaseInFlight(occupant);
        manager.cleanupTick();
        setTime(player(first), "slotReservedUntilTimestamp", System.currentTimeMillis() - 1);
        assertEquals(IN_QUEUE, manager.handlePreLogin(first, "First", "192.0.2.1").type());
        assertEquals(second, manager.getQueueList().getFirst().getUuid());
    }
    @Test void expiredHeartbeatMustLoseItsOldTurnBeforeCleanup() throws Exception {
        attempt("Occupant");
        UUID first = attempt("First");
        attempt("Second");
        setTime(player(first), "lastPingTimestamp", System.currentTimeMillis() - 300_001);
        assertEquals(2, manager.handlePreLogin(first, "First", "192.0.2.1").position().position());
    }
    @Test void callersMustNotMutateQueueThroughListSnapshot() {
        attempt("Occupant");
        UUID first = attempt("First");
        player(first).setPriority(999);
        assertEquals(0, player(first).getPriority());
    }
    @Test @SuppressWarnings("unchecked") void newVipMustNotStealLiveLease() throws Exception {
        UUID occupant = attempt("Occupant");
        UUID first = attempt("First");
        manager.releaseInFlight(occupant);
        manager.cleanupTick();
        // Set the existing supported permission cache, exactly as a previous join did in v1.0.1.
        var cacheField = QueueManager.class.getDeclaredField("permissionCache");
        cacheField.setAccessible(true);
        var configField = QueueManager.class.getDeclaredField("config");
        configField.setAccessible(true);
        QueueConfig config = (QueueConfig) configField.get(manager);
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("queue.max-players-override", 1);
        cfg.set("queue.rate-limit-seconds", 0);
        cfg.set("priorities.vip.permission", "queue.priority.vip");
        cfg.set("priorities.vip.weight", 20);
        config.load(cfg);
        UUID vip = UUID.randomUUID();
        ((Map<UUID, Set<String>>) cacheField.get(manager)).put(vip, Set.of("queue.priority.vip"));
        assertEquals(IN_QUEUE, manager.handlePreLogin(vip, "NewVip", "192.0.2.2").type());
        assertTrue(player(first).hasActiveReservation());
    }
}
```
