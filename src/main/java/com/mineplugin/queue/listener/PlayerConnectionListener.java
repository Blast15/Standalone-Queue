package com.mineplugin.queue.listener;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.QueueManager;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.IdentityHashMap;
import java.util.Map;

/** Main-thread login/join/quit and UUID/IP-only close events, with one live connection per UUID. */
public class PlayerConnectionListener implements Listener {
    private final QueueManager queueManager;
    private final QueueConfig config;
    private final Map<PlayerLoginEvent, QueueManager.LoginPermit> permits = new IdentityHashMap<>();

    public PlayerConnectionListener(QueueManager queueManager, QueueConfig config) {
        this.queueManager = queueManager;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        String ip = event.getAddress().getHostAddress();
        QueueManager.LoginPermit permit = queueManager.beginLogin(player.getUniqueId(), ip, player);
        permits.put(event, permit);
        // Only capacity rejection can be overridden. Ban, whitelist and other-plugin rejection survive.
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED && event.getResult() != PlayerLoginEvent.Result.KICK_FULL) return;
        if (permit.admitted()) {
            if (event.getResult() == PlayerLoginEvent.Result.KICK_FULL && permit.fullOverrideGranted()) event.allow();
        } else if (permit.attemptId() != null || queueManager.isEnabled()) {
            var message = permit.timedOut() ? config.formatKickLoginTimeout() : config.formatKickAdmissionRequired();
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, message);
            queueManager.recordQueueDisconnect(player.getName(), ip, PlainTextComponentSerializer.plainText().serialize(message));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLoginComplete(PlayerLoginEvent event) {
        QueueManager.LoginPermit permit = permits.remove(event);
        if (permit != null && permit.attemptId() != null) {
            queueManager.completeLogin(event.getPlayer().getUniqueId(), permit.attemptId(),
                    permit.admitted() && event.getResult() == PlayerLoginEvent.Result.ALLOWED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) { queueManager.onPlayerJoin(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) { queueManager.onPlayerQuit(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        // May run asynchronously: no Bukkit player/server API reads here.
        queueManager.onConnectionClose(event.getPlayerUniqueId(), event.getIpAddress().getHostAddress());
    }
}
