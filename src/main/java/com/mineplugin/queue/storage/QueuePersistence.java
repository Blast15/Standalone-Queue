package com.mineplugin.queue.storage;

import com.mineplugin.queue.manager.QueueManager;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Single scheduled writer: no disk access on the login path and no unbounded flush backlog. */
public final class QueuePersistence {
    private final QueueManager manager;
    private final Logger logger;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "StandaloneQueue-persistence"); thread.setDaemon(true); return thread;
    });
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private QueueSnapshotStore store; // worker-owned
    private ScheduledFuture<?> periodic;
    private volatile boolean closing;
    private volatile String status = "recovering";

    public QueuePersistence(QueueManager manager, Path directory, long intervalSeconds, Logger logger) {
        this.manager = manager; this.logger = logger;
        manager.beginRecovery();
        worker.execute(() -> {
            try {
                store = new QueueSnapshotStore(directory);
                int restored = manager.restoreEntries(store.read());
                status = "active";
                logger.info("Queue persistence: restored " + restored + " valid waiting entries");
                if (!closing) periodic = worker.scheduleWithFixedDelay(this::flush, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
            } catch (Exception error) {
                fail(error); // Preserve unreadable/unsupported source; never replace it with an empty queue.
            } finally { manager.finishRecovery(); ready.complete(null); }
        });
    }

    public CompletionStage<Void> ready() { return ready.minimalCompletionStage(); }

    public String status() { return status; }

    private void flush() {
        if (store == null) return;
        try { store.write(manager.persistenceSnapshot()); }
        catch (Exception error) { fail(error); }
    }

    private void fail(Exception error) {
        status = "failed (memory-only; snapshot preserved)";
        logger.log(Level.SEVERE, "Queue persistence disabled for this session; inspect queue-state.bin before restarting", error);
        closeStore();
    }
    private void closeStore() {
        if (store == null) return;
        try { store.close(); }
        catch (Exception error) { logger.log(Level.WARNING, "Cannot close queue snapshot ownership", error); }
        finally { store = null; }
    }

    /** Bounded shutdown wait; one final write follows any running flush on the same owner worker. */
    public void close(List<QueueEntry> finalSnapshot) {
        closing = true;
        if (periodic != null) periodic.cancel(false);
        Future<?> finalWrite = worker.submit(() -> {
            try { if (store != null) store.write(finalSnapshot); }
            catch (Exception error) { fail(error); }
            finally { closeStore(); status = "closed"; }
        });
        worker.shutdown();
        try { finalWrite.get(5, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); logger.warning("Queue persistence shutdown interrupted"); }
        catch (ExecutionException | TimeoutException error) {
            logger.log(Level.WARNING, "Queue persistence final flush did not finish within shutdown deadline; last atomic snapshot remains recoverable", error);
        }
    }
}
