package com.mineplugin.queue.permission;

/** Rights verified for one attempt; never reused as a join/quit permission cache. */
public record PermissionSnapshot(boolean bypass, int priorityWeight, long loadedAt, String sourceVersion) {
    public static final long MAX_AGE_MS = 2_000L;

    public static PermissionSnapshot none(long now) {
        return new PermissionSnapshot(false, 0, now, "unverified");
    }

    public boolean isFresh(long now) {
        return now >= loadedAt && now - loadedAt < MAX_AGE_MS;
    }
}
