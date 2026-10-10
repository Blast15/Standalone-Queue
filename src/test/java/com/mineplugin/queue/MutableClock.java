package com.mineplugin.queue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

final class MutableClock extends Clock {
    private final AtomicLong now = new AtomicLong(10_000L);
    void advance(long millis) { now.addAndGet(millis); }
    @Override public long millis() { return now.get(); }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
}
