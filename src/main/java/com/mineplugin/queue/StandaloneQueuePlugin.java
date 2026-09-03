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
 * Main class cho StandaloneQueue Plugin.
 *
 * @author BlastSama
 */
public final class StandaloneQueuePlugin extends JavaPlugin {

    private QueueConfig queueConfig;
    private QueueManager queueManager;
    private QueueCleanupTask cleanupTask;
    private com.mineplugin.queue.logging.ConsoleFilterManager consoleFilterManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.queueConfig = new QueueConfig(getConfig());

        this.queueManager = new QueueManager(this, queueConfig);
        this.queueManager.refreshServerCapacity();

        this.consoleFilterManager = new com.mineplugin.queue.logging.ConsoleFilterManager(queueConfig, queueManager);
        this.consoleFilterManager.register();

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PreLoginListener(queueManager, queueConfig), this);
        pm.registerEvents(new PlayerConnectionListener(queueManager), this);

        PluginCommand queueCmd = getCommand("queue");
        if (queueCmd != null) {
            QueueCommand executor = new QueueCommand(this, queueManager, queueConfig);
            queueCmd.setExecutor(executor);
            queueCmd.setTabCompleter(executor);
        }

        this.cleanupTask = new QueueCleanupTask(queueManager);
        this.cleanupTask.runTaskTimerAsynchronously(this, 20L, 20L);

        if (queueConfig.isDebugLog()) {
            getLogger().info("===============================================");
            getLogger().info(" StandaloneQueue Plugin da duoc bat! (DEBUG LOG: ON)");
            getLogger().info(" Keep-Alive Timeout: " + queueConfig.getKeepAliveSeconds() + " giay (5 phut)");
            getLogger().info(" Slot Reservation: " + queueConfig.getSlotReservationSeconds() + " giay");
            getLogger().info(" Rate Limit: " + queueConfig.getRateLimitSeconds() + " giay");
            getLogger().info("===============================================");
        } else {
            getLogger().info("StandaloneQueue v" + getPluginMeta().getVersion() + " đã kích hoạt thành công.");
        }
    }

    @Override
    public void onDisable() {
        if (consoleFilterManager != null) {
            consoleFilterManager.unregister();
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        if (queueManager != null) {
            queueManager.clearRuntimeState();
        }
        getLogger().info("StandaloneQueue Plugin da duoc tat.");
    }

    public com.mineplugin.queue.logging.ConsoleFilterManager getConsoleFilterManager() {
        return consoleFilterManager;
    }

    public QueueConfig getQueueConfig() {
        return queueConfig;
    }

    public QueueManager getQueueManager() {
        return queueManager;
    }
}
