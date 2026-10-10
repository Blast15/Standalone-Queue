package com.mineplugin.queue.manager;

/** Called only from the server thread. Async admission reads the manager's published copy. */
@FunctionalInterface
public interface CapacitySource {
    record Snapshot(int onlinePlayers, int maxPlayers) {}
    Snapshot snapshot();
}
