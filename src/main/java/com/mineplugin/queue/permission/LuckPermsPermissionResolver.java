package com.mineplugin.queue.permission;

import com.mineplugin.queue.config.QueueConfig;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.util.Tristate;

import java.time.Clock;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/** No local rights cache: each admitted lookup reloads the UUID from LuckPerms storage. */
public final class LuckPermsPermissionResolver implements PermissionResolver {
    private final LuckPerms api;
    private final QueueConfig config;
    private final OperatorPermissionResolver operators;
    private final Clock clock;
    // Timed-out calls retain their permit until storage actually completes.
    private final Semaphore pending = new Semaphore(64);

    public LuckPermsPermissionResolver(LuckPerms api, QueueConfig config,
                                       OperatorPermissionResolver operators, Clock clock) {
        this.api = api;
        this.config = config;
        this.operators = operators;
        this.clock = clock;
    }

    @Override
    public CompletableFuture<PermissionSnapshot> resolve(UUID uuid, String name) {
        return resolve(uuid, name, config.getPermissionPriorities());
    }

    @Override
    public CompletableFuture<PermissionSnapshot> resolve(UUID uuid, String name, Map<String, Integer> priorities) {
        if (!operators.hasAuthenticatedIdentities()) return operators.resolve(uuid, name);
        if (!pending.tryAcquire()) {
            return CompletableFuture.completedFuture(PermissionSnapshot.none(clock.millis()));
        }
        try {
            // Never resolve identity by username. Static contexts are the only supported pre-login contexts.
            return api.getUserManager().loadUser(uuid).thenApplyAsync(user -> {
                try {
                    if (!operators.hasAuthenticatedIdentities()) {
                        return PermissionSnapshot.none(clock.millis());
                    }
                    var data = user.getCachedData().permissionData().calculate(api.getContextManager().getStaticQueryOptions());
                    var bypass = data.checkPermission("queue.bypass");
                    boolean allowed = bypass == Tristate.TRUE || (bypass == Tristate.UNDEFINED
                            && operators.resolve(uuid, name).join().bypass());
                    int priority = 0;
                    for (var entry : priorities.entrySet()) {
                        if (data.checkPermission(entry.getKey()).asBoolean()) {
                            priority = Math.max(priority, entry.getValue());
                        }
                    }
                    return new PermissionSnapshot(allowed, priority, clock.millis(), "luckperms-storage");
                } finally {
                    api.getUserManager().cleanupUser(user);
                }
            }).whenComplete((result, error) -> pending.release());
        } catch (RuntimeException error) {
            pending.release();
            return CompletableFuture.failedFuture(error);
        }
    }
}
