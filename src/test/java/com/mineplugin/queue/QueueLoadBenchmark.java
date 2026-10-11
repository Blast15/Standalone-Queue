package com.mineplugin.queue;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.CapacitySource;
import com.mineplugin.queue.manager.QueueManager;
import com.mineplugin.queue.permission.PermissionSnapshot;
import org.bukkit.configuration.file.YamlConfiguration;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Manual identical workload against phase-2 and phase-3 classpaths; not a production capacity claim. */
public final class QueueLoadBenchmark {
    public static void main(String[] args) throws Exception {
        boolean antiBot = args.length > 0 && Boolean.parseBoolean(args[0]);
        var yaml = new YamlConfiguration();
        yaml.set("queue.max-players-override", 1); yaml.set("queue.rate-limit-seconds", 0);
        yaml.set("queue.keep-alive-seconds", 3600); yaml.set("queue.max-queue-size", 500);
        yaml.set("queue.anti-bot.enabled", antiBot); yaml.set("queue.anti-bot.window-seconds", 1);
        yaml.set("queue.anti-bot.max-attempts-per-ip", 4096); yaml.set("queue.anti-bot.max-queued-per-ip", 500);
        yaml.set("queue.anti-bot.global-attempts-per-window", 4096);
        var clock = Clock.systemUTC();
        var manager = new QueueManager(null, new QueueConfig(yaml), (id, name) -> CompletableFuture.completedFuture(
                new PermissionSnapshot(false, name.endsWith("0") ? 10 : 0, clock.millis(), "benchmark")), clock,
                () -> new CapacitySource.Snapshot(1, 1));
        manager.refreshServerCapacity();
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 500; i++) { var id = UUID.nameUUIDFromBytes(("User" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ids.add(id); manager.handlePreLogin(id, "User" + i, "ip" + i); }
        var executor = Executors.newFixedThreadPool(50);
        try {
            run(manager, ids, executor, antiBot ? 20 : 80);
            int loops = antiBot ? 40 : 200;
            long started = System.nanoTime(); var sample = run(manager, ids, executor, loops);
            double seconds = (System.nanoTime() - started) / 1e9;
            if (manager.getQueueSize() != 500 || manager.getInFlightCount() != 0) throw new AssertionError("capacity changed");
            System.out.printf(Locale.ROOT, "antibot=%s calls=%d seconds=%.4f callsPerSecond=%.1f p50ms=%.4f p95ms=%.4f p99ms=%.4f results=%s%n",
                    antiBot, loops * 50, seconds, loops * 50 / seconds,
                    percentile(sample.times(), .5), percentile(sample.times(), .95), percentile(sample.times(), .99), sample.counts());
        } finally { executor.shutdownNow(); }
    }
    private record Sample(Map<String, Long> counts, long[] times) {}
    private static double percentile(long[] times, double fraction) {
        return times[Math.min(times.length - 1, (int)Math.ceil(times.length * fraction) - 1)] / 1e6;
    }
    private static Sample run(QueueManager manager, List<UUID> ids, ExecutorService executor, int loops) throws Exception {
        long[] times = new long[loops * 50];
        List<Callable<Map<String, Long>>> tasks = new ArrayList<>();
        for (int worker = 0; worker < 50; worker++) {
            final int index = worker;
            tasks.add(() -> { Map<String, Long> counts = new TreeMap<>();
                for (int i = 0; i < loops; i++) {
                    int player = (index * 10 + i % 10) % ids.size();
                    long started = System.nanoTime();
                    var type = manager.handlePreLogin(ids.get(player), "User" + player, "ip" + player).type();
                    times[index * loops + i] = System.nanoTime() - started;
                    counts.merge(type.name(), 1L, Long::sum);
                } return counts; });
        }
        var merged = new TreeMap<String, Long>();
        for (var task : executor.invokeAll(tasks)) task.get().forEach((key, value) -> merged.merge(key, value, Long::sum));
        Arrays.sort(times);
        return new Sample(merged, times);
    }
}
