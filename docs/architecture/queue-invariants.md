# Queue behavior contract — phases 1–2

This contract applies while the queue is enabled. All deadlines use the injected `Clock` and `now >= deadline` means expired. Each queue transition samples time under the write lock. Rates are checked before permission resolution; rejected attempts do not refresh heartbeat.

## Admission and capacity

For normal admission, the engine uses the main-thread capacity snapshot and requires `online + inFlight < effectiveMaxPlayers`. One UUID can hold at most one in-flight admission. Verified bypass intentionally permits exceeding capacity, but still creates only one in-flight record per UUID. Lease ownership is exchanged atomically for an in-flight record, so the same slot is not counted twice.

Grant budget is `max(0, capacity - online - inFlight) - validLeases`. For example, with two free physical slots and one live lease, only one additional lease may be granted. A waiting VIP must not use the existing lease's slot. In-flight deadlines mark timeout without reducing occupied capacity; only a final login failure, pre-login cancellation or connection close releases a pending slot. Accepted-login attempts keep their slot through a delayed join. See the lifecycle contract below.

## Reservation ownership and ordering

A lease consists of the queued UUID owner, a monotonically increasing lease ID, and an exclusive deadline. Priority decides **new** grants, not ownership of a live lease. Redeeming requires physical capacity and atomically removes the owner's queue entry. Concurrent reconnects of one owner can redeem only once.

If available physical capacity shrinks, reconcile before admission: retain oldest leases, revoke newest leases first. This includes capacity used by bypass. Revocation leaves the owner waiting with their current FIFO sequence. Reconciliation occurs on pre-login, cleanup, quit and the atomic reload commit. Existing accepted handshakes are grandfathered if capacity shrinks; newest waiting leases are revoked first.

Expired leases move behind existing peers of the same priority, preserving the old relative order when several leases expire together. They may immediately receive a **new** lease if they are still first in that tier and no eligible higher/equal-priority peer precedes them. New high-priority entrants can still precede lower-priority waiting players. Example: A and B at equal priority; A's lease expires, so B gets the available slot before A even if cleanup has not run.

## Heartbeat and deadlines

Waiting entries expire before `recordPing` can renew them. At the exact heartbeat deadline a reconnect becomes a new queue entry and loses the old sequence. A live reservation remains valid until its own deadline even if heartbeat expires earlier. At lease expiry, an already expired heartbeat removes the entry; otherwise it requeues. Administrative clear/kick also explicitly cancels ownership.

Clock rollback clamps heartbeat elapsed time to zero; forward jumps expire deadlines. This uses wall-clock semantics, not persistence or a distributed clock. Fake-clock tests cover deadline −1 ms, exact deadline and +1 ms.

## Permissions and identity

`PermissionResolver` returns immutable `PermissionSnapshot(bypass, priorityWeight, loadedAt, sourceVersion)` before the manager takes the state lock. The resolver method must return promptly; provider storage work completes asynchronously. Waiting for it is bounded by `permissions.resolve-timeout-ms` (default 1000, clamp 1–5000). No provider I/O or future wait occurs under the manager lock/config monitor. Snapshots aged 2 seconds or more, failures, null results and timeouts fall back to ordinary rights. Late futures never alter a decision. A concurrent reload retries lookup once within the original total wait budget; two generation changes request reconnect without changing queue state.

- Standalone `online-mode=true` with LuckPerms: reload the authenticated UUID for every eligible attempt and calculate static-context permissions; no local join/quit rights cache. At most 64 lookups remain outstanding, including those whose login wait timed out. Cleanup the provider user when the lookup completes. `sourceVersion` is a provider provenance label, not a database revision.
- Without LuckPerms: capture the server's operator UUIDs on the main thread every 20 ticks; accept only snapshots younger than 2 seconds. Main-thread stalls fail closed at the age limit. Default OP bypass is supported; other plugins' permission attachments are not.
- LuckPerms explicit bypass denial takes precedence over the OP fallback. Only undefined bypass may use a fresh OP snapshot. Before login, world/player contexts are unavailable; configure queue rights with global/static server contexts.
- Offline-mode UUIDs and usernames do not prove account ownership: no pre-login elevation. Authenticated proxy forwarding is not auto-detected in this standalone phase.

The HIGHEST `PlayerLoginEvent` listener binds UUID/IP to the exact Player identity and only overrides `KICK_FULL` for a granted, non-expired attempt. It never overrides ban/whitelist/other rejection. MONITOR completes that exact token with the final result. See [phase 2 migration](../releases/1.1.0.md) for the login state table and the Paper close contract.

## Threading and snapshots

Bukkit online/capacity/operator reads run on the server thread. Queue mutations and internal sorted-cache publication require the queue write lock. Lock order is config monitor, then queue lock; resolver calls happen outside both. Logging filters read the immutable settings generation without the config monitor and only inspect their own bounded disconnect-reason cache; tracing under the queue lock cannot invert the lock order. The read-only `/queue list` path creates a sorted, unmodifiable list of detached point-in-time copies under the read lock and does not mutate the engine cache. Changes to returned `QueuedPlayer` copies cannot change engine state.

## Reload transaction and connection ownership

Settings generations and their maps are immutable. Strict YAML/range validation happens before provider work. Reload snapshots membership, resolves candidate weights in bounded batches outside locks, then checks freshness, membership identity and latest request before publishing. The commit holds config monitor then queue write lock. It swaps settings, refreshes every queue weight, sorts, expires entries and reconciles leases together. A failed prepare or obsolete commit leaves the active generation/overrides/leases untouched. Successful reload resets runtime anti-bot to file value and clears attempt-window history. Original live lease and attempt deadlines remain unchanged.

`PRELOGIN_ACCEPTED` and `TIMED_OUT` occupy one slot until final login failure or close. `LOGIN_ACCEPTED` occupies one slot even beyond its deadline until join or close. Join atomically moves it to online accounting. `JOINED` and `FAILED` retain connection identity, but no in-flight slot, until the UUID/IP-only Paper close event. There is one accepted connection per UUID and a 4096-record ceiling, preventing a close from an old connection from removing a newer one. Cancelled async pre-login has no close event; its event-owned token is removed at MONITOR. A rejected duplicate owns no token and cannot cancel the original attempt.

All lifecycle changes use the write lock. Async close, cleanup, pre-login and reload never read online player lists, Bukkit max-player settings or operator state. Join and scheduled refresh capture capacity on the main thread. Other plugins must respect final results and MONITOR semantics; supported Paper versions and transport/plugin interactions require real staging.

## Automated coverage

`PhaseOneRegressionTest` covers SQ-001/002/003/004/005/007 and the phase 1 portion of SQ-016: first-login rights, revoke, deadlines, stable leases, capacity shrink, duplicate UUID, and 500 queued players with 50 concurrent reader/writer tasks. `LuckPermsPermissionResolverTest` checks adapter calls, denial/OP fallback, provider-user cleanup, offline identities and the outstanding-lookup bound. `PhaseTwoRegressionTest`, `QueueSettingsReloadTest` and console/command tests add SQ-006/008/009/012 regression coverage. Failsafe runs `PaperLoginPipelineIT` through actual API event types and production listeners, with controlled identity/storage/capacity fixtures. These tests do not replace a real Paper/LuckPerms staging run; use the [staging probe and matrix](../testing/phase-2-staging.md).

## Phase 3 operations and estimates

UUID cooldown is checked before IP/global sliding windows. IP rejection never spends global budget. A live UUID/original-IP lease owner can use a separate, equally bounded global window; both windows and all IP timestamps are bounded. This equality is not identity authentication. Reasons and lifecycle counters are explicit, immutable snapshots; text rendering takes place outside locks.

Pause stops new pre-login/lease allocation but preserves existing lease redemption and in-flight completion. Expiry/capacity reconciliation continue; reload preserves pause. Administrative move cannot cross priority tiers, leases (including unreconciled expired ones), or tracked connections, and preserves heartbeat. Granular permissions are enforced in command dispatch and tab completion; queue.admin stays an umbrella.

Only exact successful queued JOIN transitions feed a fixed 900-second bucket history and inter-arrival EWMA. Quit/pre-login/cancelled/failed/direct/bypass traffic cannot advertise queue throughput. Cached ETA accounts for queue rank plus unfinished redeemed queued admissions, reports a heuristic range and withholds stale/insufficient estimates. See [phase 3 policy](../releases/1.2.0.md) for exact thresholds and caveats.
