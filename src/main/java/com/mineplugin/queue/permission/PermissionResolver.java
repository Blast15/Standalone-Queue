package com.mineplugin.queue.permission;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface PermissionResolver {
    /** Must return promptly; storage work belongs on the provider's async executor. */
    CompletionStage<PermissionSnapshot> resolve(UUID uuid, String name);
}
