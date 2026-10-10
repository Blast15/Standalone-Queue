# Queue behavior contract — phase 1

This contract applies while the queue is enabled. All deadlines use the injected `Clock` and `now >= deadline` means expired. Each queue transition samples time under the write lock. Rates are checked before permission resolution; rejected attempts do not refresh heartbeat.

## Admission and capacity

For normal admission, the engine uses the main-thread capacity snapshot and requires `online + inFlight < effectiveMaxPlayers`. One UUID can hold at most one in-flight admission. Verified bypass intentionally permits exceeding capacity, but still creates only one in-flight record per UUID. Lease ownership is exchanged atomically for an in-flight record, so the same slot is not counted twice.

Grant budget is `max(0, capacity - online - inFlight) - validLeases`. For example, with two free physical slots and one live lease, only one additional lease may be granted. A waiting VIP must not use the existing lease's slot. The 15-second in-flight timeout is unchanged (SQ-006, phase 2): this contract does not guarantee capacity safety if a previously timed-out handshake later joins.

## Reservation ownership and ordering

A lease consists of the queued UUID owner, a monotonically increasing lease ID, and an exclusive deadline. Priority decides **new** grants, not ownership of a live lease. Redeeming requires physical capacity and atomically removes the owner's queue entry. Concurrent reconnects of one owner can redeem only once.

If available physical capacity shrinks, reconcile before admission: retain oldest leases, revoke newest leases first. This includes capacity used by bypass. Revocation leaves the owner waiting with their current FIFO sequence. Reconciliation occurs on pre-login, cleanup or quit; it is not an atomic configuration reload (SQ-008 remains phase 2).

Expired leases move behind existing peers of the same priority, preserving the old relative order when several leases expire together. They may immediately receive a **new** lease if they are still first in that tier and no eligible higher/equal-priority peer precedes them. New high-priority entrants can still precede lower-priority waiting players. Example: A and B at equal priority; A's lease expires, so B gets the available slot before A even if cleanup has not run.

## Heartbeat and deadlines

Waiting entries expire before `recordPing` can renew them. At the exact heartbeat deadline a reconnect becomes a new queue entry and loses the old sequence. A live reservation remains valid until its own deadline even if heartbeat expires earlier. At lease expiry, an already expired heartbeat removes the entry; otherwise it requeues. Administrative clear/kick also explicitly cancels ownership.

Clock rollback clamps heartbeat elapsed time to zero; forward jumps expire deadlines. This uses wall-clock semantics, not persistence or a distributed clock. Fake-clock tests cover deadline −1 ms, exact deadline and +1 ms.

## Permissions and identity

`PermissionResolver` returns immutable `PermissionSnapshot(bypass, priorityWeight, loadedAt, sourceVersion)` before the manager takes the state lock. The resolver method must return promptly; provider storage work completes asynchronously. Waiting for it is bounded by `permissions.resolve-timeout-ms` (default 1000, clamp 1–5000). No provider I/O or future wait occurs under the manager lock/config monitor. Snapshots aged 2 seconds or more, failures, null results and timeouts fall back to ordinary rights. Late futures never alter a decision.

- Standalone `online-mode=true` with LuckPerms: reload the authenticated UUID for every eligible attempt and calculate static-context permissions; no local join/quit rights cache. At most 64 lookups remain outstanding, including those whose login wait timed out. Cleanup the provider user when the lookup completes. `sourceVersion` is a provider provenance label, not a database revision.
- Without LuckPerms: capture the server's operator UUIDs on the main thread every 20 ticks; accept only snapshots younger than 2 seconds. Main-thread stalls fail closed at the age limit. Default OP bypass is supported; other plugins' permission attachments are not.
- LuckPerms explicit bypass denial takes precedence over the OP fallback. Only undefined bypass may use a fresh OP snapshot. Before login, world/player contexts are unavailable; configure queue rights with global/static server contexts.
- Offline-mode UUIDs and usernames do not prove account ownership: no pre-login elevation. Authenticated proxy forwarding is not auto-detected in this standalone phase.

The second `PlayerLoginEvent` listener only overrides `KICK_FULL` for an existing in-flight admission. It never resurrects bypass from a stale permission cache and never overrides a ban/whitelist result.

## Threading and snapshots

Bukkit online/capacity/operator reads run on the server thread. Queue mutations and internal sorted-cache publication require the queue write lock. Lock order is config monitor, then queue lock; resolver calls happen outside both. The read-only `/queue list` path creates a sorted, unmodifiable list of detached point-in-time copies under the read lock and does not mutate the engine cache. Changes to returned `QueuedPlayer` copies cannot change engine state.

## Automated coverage

`PhaseOneRegressionTest` covers SQ-001/002/003/004/005/007 and the phase 1 portion of SQ-016: first-login rights, revoke, deadlines, stable leases, capacity shrink, duplicate UUID, and 500 queued players with 50 concurrent reader/writer tasks. `LuckPermsPermissionResolverTest` checks adapter calls, denial/OP fallback, provider-user cleanup, offline identities and the outstanding-lookup bound. These tests do not replace a real Paper/LuckPerms staging run.
