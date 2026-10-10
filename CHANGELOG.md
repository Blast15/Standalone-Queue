# Changelog

## 1.1.0-SNAPSHOT — phase 2 (unreleased)

### Fixed

- SQ-006: track login tokens, phase and frozen deadlines; time alone never frees a live handshake slot. Handle final login rejection, late pre-login cancellation and Paper connection close without releasing a newer attempt.
- SQ-008: validate an immutable candidate off-thread; refresh all waiting priorities before atomically publishing settings, ordering and capacity reconciliation. Failures preserve the previous generation and runtime overrides.
- SQ-009: opt-in, source-aware console suppression; preserve unknown/unrelated logs, warnings, errors and throwable parameters.
- SQ-012: deliver expired-reservation decisions with the new queue position once; share MiniMessage/legacy placeholders and deprecate the unused ready-kick template.

### Changed

- New `queue.in-flight-timeout-seconds` (30, valid 5–300) and `logging.suppress-queue-noise` (false). Debug lifecycle traces and in-flight/timeout status counters.
- One accepted connection per UUID remains tracked until Paper close, including joined and failed attempts; a rapid reconnect must wait for old close.
- Successful reload resets anti-bot runtime override and rate/IP/global attempt windows; active lease and in-flight deadlines stay frozen. Invalid reloads now report an error rather than normalizing silently.
- Failsafe Paper API event/listener integration harness; independent staging probe for targeted async delays/rejections and event traces. CI builds both JARs and uploads test reports.

See [migration and rollback](docs/releases/1.1.0.md) and [staging acceptance](docs/testing/phase-2-staging.md). Real server/client/backend testing and review remain pending; G2 is not declared complete.

## 1.0.2-SNAPSHOT — phase 1 (unreleased)

### Fixed

- SQ-001/003: resolve first-login rights before admission, remove the indefinite join/quit permission cache, bound lookup wait and fail closed on unverified rights.
- SQ-002: preserve UUID-owned live leases across priority changes; revoke newest leases first when physical capacity shrinks; atomically exchange leases for in-flight admissions.
- SQ-004/005: process lease and heartbeat expiration before reconnect, with a single injected clock and an exclusive deadline.
- SQ-007: prevent writes to the sorted cache under read locks; return detached queue views so callers cannot mutate engine entries.

### Changed

- Optional LuckPerms 5.4+ integration using static contexts on online-mode servers. Without LuckPerms, only fresh main-thread OP snapshots grant pre-login elevation.
- Offline mode grants ordinary pre-login rights regardless of username, offline UUID or OP status.
- New `permissions.resolve-timeout-ms` setting (default 1000, range 1–5000); maximum 64 outstanding LuckPerms lookups; no extra local rights cache.
- Preserve active leases until their own deadline even when heartbeat expires first; admin removals and capacity shrink remain explicit cancellation paths.
- Java 21 Maven `clean verify` is the authoritative build. Added deterministic deadline, permission-adapter and concurrency regression coverage (SQ-016).

See [migration, staging and rollback](docs/releases/1.0.2.md). The release requires real Paper staging and independent review; no production rollout or release tag is included.
