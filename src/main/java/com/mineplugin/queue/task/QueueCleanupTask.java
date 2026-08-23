package com.mineplugin.queue.task;

import com.mineplugin.queue.manager.QueueManager;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Task định kỳ quét timeout và xử lý slot reservation.
 *
 * @author BlastSama
 */
public class QueueCleanupTask extends BukkitRunnable {

    private final QueueManager queueManager;

    public QueueCleanupTask(QueueManager queueManager) {
        this.queueManager = queueManager;
    }

    @Override
    public void run() {
        try {
            queueManager.cleanupTick();
        } catch (Exception e) {
            // Đảm bảo task không bị dừng nếu gặp lỗi bất ngờ
            e.printStackTrace();
        }
    }
}
