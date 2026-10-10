package com.mineplugin.queue.model;

import java.util.UUID;

/** One accepted connection. The UUID remains owned until Paper's close event. */
public record LoginAttempt(UUID id, UUID playerUuid, String ipAddress, long startedAt,
                           long deadline, Phase phase, Object playerIdentity, boolean timeoutObserved,
                           boolean fullOverrideGranted) {
    public enum Phase { PRELOGIN_ACCEPTED, LOGIN_ACCEPTED, TIMED_OUT, JOINED, FAILED }

    public boolean occupiesSlot() {
        return phase == Phase.PRELOGIN_ACCEPTED || phase == Phase.LOGIN_ACCEPTED || phase == Phase.TIMED_OUT;
    }

    public LoginAttempt transition(Phase next, Object identity) {
        return new LoginAttempt(id, playerUuid, ipAddress, startedAt, deadline, next, identity, timeoutObserved, fullOverrideGranted);
    }

    public LoginAttempt observeTimeout() {
        return new LoginAttempt(id, playerUuid, ipAddress, startedAt, deadline,
                phase == Phase.PRELOGIN_ACCEPTED ? Phase.TIMED_OUT : phase, playerIdentity, true, fullOverrideGranted);
    }
}
