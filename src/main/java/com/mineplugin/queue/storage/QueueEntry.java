package com.mineplugin.queue.storage;

import java.util.UUID;

/** Durable waiting identity. Permissions, IPs and connection/lease tokens are never persisted. */
public record QueueEntry(UUID uuid, String name, long sequence, long firstJoinedAt, long lastPingAt) {}
