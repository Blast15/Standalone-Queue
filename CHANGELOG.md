# Changelog

## [2.0.0] — ứng viên review/staging

- Hoàn thiện SQ-014 bằng snapshot atomic async, schema/checksum/bounds, ownership lock và waiting-only recovery có kiểm tra quyền/TTL/capacity.
- Thêm recovery regressions, persistence status và restart-only configuration; giữ snapshot lỗi và fallback memory-only.
- Bổ sung expiry/permission counters và histogram thời gian chờ đến queued JOIN.
- Đồng bộ Maven/Gradle/staging probe 2.0.0; cập nhật audit roadmap, backup/migration/rollback và release notes.
- Nâng API tối thiểu lên Paper 1.21.11, sửa version descriptor JAR, giữ bytecode Java 21; kiểm tra runtime Java 21/25.
- Thêm automated loopback network/recovery matrix, SIGKILL, corrupt snapshot, ban/whitelist và bài tải thực 500 waiting/50 online; CI release chờ matrix hoàn tất.
- Còn gate online-mode/LuckPerms thật, canary và filesystem power-loss; xem `docs/releases/2.0.0.md` và `docs/roadmap-status.md`.


## 1.2.0-SNAPSHOT — phase 3 (unreleased)

- SQ-010: distinct UUID/IP/global/quota/full reasons and counters; IP/UUID rejects do not spend global budget; exact live UUID/IP lease owners use a separately bounded protected budget. Preserve long UUID cooldowns during cleanup; hard 4096 tracking/attempt ceilings and NAT regression.
- SQ-011: rolling successful queued JOIN history and EWMA approximate ETA range; fixed-size history/cache, no position × 20 s guess, updating fallback with low/stale data.
- SQ-013: immutable stats and identity-free Prometheus text API, JOIN/login failure/cancellation/timeout counters and pre-login average/max latency; no HTTP listener.
- SQ-015: granular inspect/stats/manage permissions, safe pause/resume/same-tier move, private inspect, filtered help/tab completion and persistent-visible admin audit.
- Java/Maven/Gradle/probe metadata 1.2.0-SNAPSHOT; phase 3 load harness, regression and migration/staging records.

See [migration](docs/releases/1.2.0.md), [staging G3](docs/testing/phase-3-staging.md) and [local comparison](docs/testing/phase-3-benchmark.md). G3 remains pending real staging data and review.

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
