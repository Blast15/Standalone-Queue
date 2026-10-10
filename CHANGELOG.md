# Changelog

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
