package com.mineplugin.queue.listener;

import com.mineplugin.queue.manager.QueueManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Lắng nghe các sự kiện đăng nhập, vào game và thoát game của người chơi.
 */
public class PlayerConnectionListener implements Listener {

    private final QueueManager queueManager;

    public PlayerConnectionListener(QueueManager queueManager) {
        this.queueManager = queueManager;
    }

    /**
     * Bắt sự kiện PlayerLoginEvent.
     * Nếu Bukkit cố gắng kick người chơi vì KICK_FULL nhưng người chơi đã được plugin cấp slot/bypass,
     * chúng ta sẽ cho phép người chơi đăng nhập (allow).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();

        if (event.getResult() == PlayerLoginEvent.Result.KICK_FULL) {
            if (player.hasPermission("queue.bypass") || queueManager.hasBypassPermission(player.getUniqueId())) {
                event.allow();
                return;
            }

            // Nếu người chơi đang trong quá trình handshake đã được duyệt qua PreLogin
            if (queueManager.isInFlight(player.getUniqueId())) {
                event.allow();
                return;
            }

            // Nếu số người online thực tế chưa vượt override sức chứa
            int online = event.getPlayer().getServer().getOnlinePlayers().size();
            int maxCap = queueManager.getEffectiveMaxPlayers();
            if (online < maxCap) {
                event.allow();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        queueManager.onPlayerJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        queueManager.onPlayerQuit(event.getPlayer());
    }
}
