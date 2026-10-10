package com.mineplugin.queue;

import com.mineplugin.queue.permission.PermissionResolver;
import com.mineplugin.queue.permission.PermissionSnapshot;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class FakePermissionResolver implements PermissionResolver {
    private final Clock clock;
    private final ConcurrentHashMap<UUID, PermissionSnapshot> rights = new ConcurrentHashMap<>();
    FakePermissionResolver(Clock clock) { this.clock = clock; }
    void set(UUID uuid, boolean bypass, int priority) {
        rights.put(uuid, new PermissionSnapshot(bypass, priority, clock.millis(), "test"));
    }
    @Override public CompletableFuture<PermissionSnapshot> resolve(UUID uuid, String name) {
        var current = rights.getOrDefault(uuid, PermissionSnapshot.none(clock.millis()));
        return CompletableFuture.completedFuture(new PermissionSnapshot(current.bypass(), current.priorityWeight(), clock.millis(), "test"));
    }
}
