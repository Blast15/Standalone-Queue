package com.mineplugin.queue.manager;

/** Fixed-size rolling JOIN history. All methods are called under the queue write lock. */
public final class AdmissionHistory {
    private final long[] seconds = new long[900];
    private final long[] counts = new long[900];
    private long lastJoin = -1;
    private double ewmaSeconds;
    public record Snapshot(long joins5m, long joins15m, double secondsPerJoin) {}

    public void record(long now) {
        long second = Math.floorDiv(now, 1000);
        int index = Math.floorMod(second, 900);
        if (seconds[index] != second) { seconds[index] = second; counts[index] = 0; }
        counts[index]++;
        if (lastJoin >= 0 && now > lastJoin) {
            double interval = (now - lastJoin) / 1000.0;
            ewmaSeconds = ewmaSeconds == 0 ? interval : .25 * interval + .75 * ewmaSeconds;
        }
        lastJoin = now;
    }

    public Snapshot snapshot(long now) {
        long second = Math.floorDiv(now, 1000), five = 0, fifteen = 0;
        // ponytail: O(900), fixed memory, cached by QueueManager once per second.
        for (int i = 0; i < 900; i++) {
            long age = second - seconds[i];
            if (age >= 0 && age < 900) fifteen += counts[i];
            if (age >= 0 && age < 300) five += counts[i];
        }
        double rate = five >= 3 && lastJoin >= 0 && now - lastJoin < 120_000 && ewmaSeconds > 0
                ? Math.max(ewmaSeconds, 300.0 / five) : 0;
        return new Snapshot(five, fifteen, rate);
    }
}
