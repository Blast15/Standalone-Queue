package com.mineplugin.queue.permission;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Main-thread operator snapshot for servers without a pre-login permission provider. */
public final class OperatorPermissionResolver implements PermissionResolver {
    private record State(Set<UUID> operators, boolean authenticated, long sampledAt) {}

    private final Clock clock;
    private volatile State state = new State(Set.of(), false, Long.MIN_VALUE);

    public OperatorPermissionResolver(Clock clock) {
        this.clock = clock;
    }

    public void refresh(Set<UUID> operators, boolean onlineMode) {
        state = new State(Set.copyOf(operators), onlineMode, clock.millis());
    }

    public boolean hasAuthenticatedIdentities() {
        State current = state;
        long now = clock.millis();
        return current.authenticated && now >= current.sampledAt
                && now - current.sampledAt < PermissionSnapshot.MAX_AGE_MS;
    }

    @Override
    public CompletableFuture<PermissionSnapshot> resolve(UUID uuid, String name) {
        State current = state;
        long now = clock.millis();
        boolean fresh = current.authenticated && now >= current.sampledAt
                && now - current.sampledAt < PermissionSnapshot.MAX_AGE_MS;
        return CompletableFuture.completedFuture(fresh
                ? new PermissionSnapshot(current.operators.contains(uuid), 0, current.sampledAt, "bukkit-operators")
                : PermissionSnapshot.none(now));
    }
}
