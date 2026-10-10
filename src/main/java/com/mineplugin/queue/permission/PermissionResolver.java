package com.mineplugin.queue.permission;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface PermissionResolver {
    /** Must return promptly; storage work belongs on the provider's async executor. */
    CompletionStage<PermissionSnapshot> resolve(UUID uuid, String name);

    /** Reload supplies candidate weights without publishing them to active admission. */
    default CompletionStage<PermissionSnapshot> resolve(UUID uuid, String name, Map<String, Integer> priorities) {
        return resolve(uuid, name);
    }
}
