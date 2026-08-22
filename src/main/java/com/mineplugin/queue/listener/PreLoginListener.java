package com.mineplugin.queue.listener;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.QueueManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.net.InetAddress;
import java.util.UUID;

/**
 * Lắng nghe sự kiện kết nối bất đồng bộ (AsyncPlayerPreLoginEvent).
 * Can thiệp trước khi người chơi vào thế giới để kiểm tra vị trí hàng chờ và quyết định kick / cho phép vào.
 */
public class PreLoginListener implements Listener {

    private final QueueManager queueManager;
    private final QueueConfig config;

    public PreLoginListener(QueueManager queueManager, QueueConfig config) {
        this.queueManager = queueManager;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        // Nếu sự kiện đã bị plugin khác từ chối (ví dụ: bị ban, whitelist) thì bỏ qua
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }

        UUID uuid = event.getUniqueId();
        String name = event.getName();
        InetAddress address = event.getAddress();
        String ip = address != null ? address.getHostAddress() : "0.0.0.0";

        QueueManager.LoginDecision decision = queueManager.handlePreLogin(uuid, name, ip);

        switch (decision.type()) {
            case ALLOWED_DIRECT, ALLOWED_RESERVED, ALLOWED_BYPASS -> {
                event.allow();
            }
            case RATE_LIMITED -> {
                Component msg = config.formatKickRateLimited();
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, msg);
            }
            case QUEUE_FULL -> {
                int total = queueManager.getQueueSize();
                Component msg = config.formatKickQueueFull(total);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_FULL, msg);
            }
            case IN_QUEUE -> {
                if (decision.position() != null) {
                    if (decision.position().isSlotReady()) {
                        Component msg = config.formatKickSlotReady();
                        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_FULL, msg);
                    } else {
                        int online = Bukkit.getOnlinePlayers().size();
                        int maxCap = queueManager.getEffectiveMaxPlayers();
                        Component msg = config.formatKickInQueue(decision.position(), online, maxCap);
                        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_FULL, msg);
                    }
                } else {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_FULL, Component.text("Bạn đang trong hàng chờ."));
                }
            }
        }
    }
}
