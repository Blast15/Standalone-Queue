package com.mineplugin.queue;

import com.mineplugin.queue.command.QueueCommand;
import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.listener.PlayerConnectionListener;
import com.mineplugin.queue.listener.PreLoginListener;
import com.mineplugin.queue.manager.QueueManager;
import com.mineplugin.queue.task.QueueCleanupTask;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main class của StandaloneQueue Plugin.
 * Plugin hàng chờ tự động không cần Proxy cho máy chủ Paper.
 */
public final class StandaloneQueuePlugin extends JavaPlugin {

    private QueueConfig queueConfig;
    private QueueManager queueManager;
    private QueueCleanupTask cleanupTask;

    @Override
    public void onEnable() {
        // 1. Tải và lưu cấu hình mặc định
        saveDefaultConfig();
        this.queueConfig = new QueueConfig(getConfig());

        // 2. Khởi tạo Engine quản lý hàng chờ
        this.queueManager = new QueueManager(this, queueConfig);

        // 3. Đăng ký các sự kiện (Events)
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PreLoginListener(queueManager, queueConfig), this);
        pm.registerEvents(new PlayerConnectionListener(queueManager), this);

        // 4. Đăng ký lệnh điều khiển
        PluginCommand queueCmd = getCommand("queue");
        if (queueCmd != null) {
            QueueCommand executor = new QueueCommand(this, queueManager, queueConfig);
            queueCmd.setExecutor(executor);
            queueCmd.setTabCompleter(executor);
        }

        // 5. Khởi động Task định kỳ quét timeout và cập nhật slot reservation (chạy mỗi 1 giây / 20 ticks)
        this.cleanupTask = new QueueCleanupTask(queueManager);
        this.cleanupTask.runTaskTimerAsynchronously(this, 20L, 20L);

        getLogger().info("===============================================");
        getLogger().info(" StandaloneQueue Plugin da duoc bat thanh cong!");
        getLogger().info(" Keep-Alive Timeout: " + queueConfig.getKeepAliveSeconds() + " giay (5 phut)");
        getLogger().info(" Slot Reservation: " + queueConfig.getSlotReservationSeconds() + " giay");
        getLogger().info(" Rate Limit: " + queueConfig.getRateLimitSeconds() + " giay");
        getLogger().info("===============================================");
    }

    @Override
    public void onDisable() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        if (queueManager != null) {
            queueManager.clearQueue();
        }
        getLogger().info("StandaloneQueue Plugin da duoc tat.");
    }

    public QueueConfig getQueueConfig() {
        return queueConfig;
    }

    public QueueManager getQueueManager() {
        return queueManager;
    }
}
