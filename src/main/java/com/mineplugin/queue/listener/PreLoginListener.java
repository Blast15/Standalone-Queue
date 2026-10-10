package com.mineplugin.queue.listener;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.QueueManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Async admission, followed by exact-token cleanup if another listener cancels this event. */
public class PreLoginListener implements Listener {
    private final QueueManager queueManager;
    private final QueueConfig config;
    private final Map<AsyncPlayerPreLoginEvent, UUID> accepted = new ConcurrentHashMap<>();

    public PreLoginListener(QueueManager queueManager, QueueConfig config) {
        this.queueManager = queueManager;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        String ip = event.getAddress().getHostAddress();
        QueueManager.LoginDecision decision = queueManager.handlePreLogin(event.getUniqueId(), event.getName(), ip);
        Component message;
        AsyncPlayerPreLoginEvent.Result result = AsyncPlayerPreLoginEvent.Result.KICK_FULL;
        switch (decision.type()) {
            case ALLOWED_DIRECT, ALLOWED_RESERVED, ALLOWED_BYPASS -> {
                if (decision.attemptId() != null) accepted.put(event, decision.attemptId());
                return;
            }
            case RATE_LIMITED -> {
                result = AsyncPlayerPreLoginEvent.Result.KICK_OTHER;
                message = config.formatKickRateLimited();
            }
            case QUEUE_FULL -> message = config.formatKickQueueFull(queueManager.getQueueSize());
            case EXPIRED_AND_REQUEUED -> message = config.formatKickReservationExpired(decision.position(),
                    queueManager.getOnlinePlayerCount(), queueManager.getEffectiveMaxPlayers());
            case IN_QUEUE -> message = config.formatKickInQueue(decision.position(),
                    queueManager.getOnlinePlayerCount(), queueManager.getEffectiveMaxPlayers());
            default -> throw new IllegalStateException("Unsupported queue decision: " + decision.type());
        }
        event.disallow(result, message);
        queueManager.recordQueueDisconnect(event.getName(), ip, PlainTextComponentSerializer.plainText().serialize(message));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPreLoginComplete(AsyncPlayerPreLoginEvent event) {
        UUID token = accepted.remove(event);
        // Paper never emits PlayerConnectionCloseEvent for a cancelled AsyncPlayerPreLoginEvent.
        if (token != null && event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            queueManager.cancelPreLogin(event.getUniqueId(), token);
        }
    }
}
