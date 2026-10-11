# Configuration

The main settings live under `queue` in `config.yml`.

## Anti-joinbot

```yaml
queue:
  anti-bot:
    enabled: true
    window-seconds: 10
    max-attempts-per-ip: 20
    max-queued-per-ip: 5
    global-attempts-per-window: 100
```

Runtime control is available with `/queue antibot on|off|status`; a successful `/queue reload` returns to the file value and clears attempt-window history; a failed reload preserves runtime state. These limits do not replace firewall or DDoS protection.

`max-queue-size` bounds retained players and must be positive; invalid or zero values fall back to 500 at startup but are rejected during reload. `keep-alive-seconds` and `slot-reservation-seconds` must also be positive, with startup fallbacks and strict reload validation. Priority weights are configured under `priorities`.

## Pre-login permissions (1.0.2)

```yaml
permissions:
  resolve-timeout-ms: 1000 # valid 1..5000 ms; startup clamps, reload rejects invalid values
```

On a standalone server with `online-mode=true`, LuckPerms 5.4+ is optional and is discovered at startup. Each eligible attempt loads the authenticated UUID from LuckPerms, calculates permissions using static server contexts, and discards the result after admission. There is no join/quit rights cache. At most 64 storage lookups remain outstanding; a timeout does not free its permit until storage finishes. Timeout, failure, saturation, or a snapshot aged 2 seconds grants only ordinary rights. Late results cannot upgrade a completed decision.

Without LuckPerms, a main-thread operator snapshot is refreshed every 20 ticks and expires at 2 seconds. It supports OP bypass from the first connection. Arbitrary permission attachments from other plugins are not available at pre-login. With LuckPerms, an explicit `queue.bypass=false` denies OP bypass; an undefined value falls back to the fresh operator snapshot. World/player-specific contexts are intentionally unsupported before joining.

`online-mode=false` grants neither bypass nor priority from names/offline UUIDs, even with LuckPerms or OP. Authenticated proxy forwarding is not auto-detected in this standalone release. See the [migration and staging guide](../releases/1.1.0.md).

## In-flight, reload and logging (1.1.0)

```yaml
queue:
  in-flight-timeout-seconds: 30 # valid 5..300; startup clamps, reload rejects
logging:
  suppress-queue-noise: false
```

The in-flight deadline never frees a live handshake by time alone. Pre-login reaching final login after its deadline is rejected; a login accepted before the deadline keeps its slot through a delayed join. Only one accepted connection per UUID may remain until Paper close. Existing attempt deadlines are frozen across reload.

Reload reads and validates YAML off-thread, refreshes every queued player's permissions under the candidate weights, then publishes settings/order/lease reconciliation atomically. Unverified/stale/slow provider, changed membership or a newer request rejects the candidate and asks the admin to retry. Successful reload resets anti-bot override and rate/IP/global windows. Active lease owner/ID/deadline stays stable unless capacity shrinks (revoke newest leases first). Lower queue size keeps current entries; shorter heartbeat can expire waiting entries at commit.

Suppression is independent of `debug-log` and defaults off. When on, only known Paper network sources and exact recent plugin-generated name/IP/reason disconnects are matched. Unknown sources/formats and WARN/ERROR/throwable messages survive. Debug adds lifecycle token/UUID/phase traces. Status adds in-flight/timeout counts.

`kick-reservation-expired` supports the same placeholders as `kick-in-queue`, including the player's new `<position>/<total>`. `<time>` retains the current configured reservation duration for old templates. `kick-slot-ready` is deprecated and unused. New login-timeout/admission-required and admin reload/status templates have fallbacks for old files. See [migration](../releases/1.1.0.md) for all policies and [staging](../testing/phase-2-staging.md) for compatibility checks.

## Phase 3 windows and messages (1.2.0)

`queue.rate-limit-seconds` is UUID cooldown (0 disables it); IP/global limits count attempts in a sliding `window-seconds` window. UUID/IP rejects are checked before charging global budget. IP/global attempt settings must be 1..4096 on reload; legacy startup clamps higher values. Tracking is capped at 4096 UUIDs/IPs; two bounded global budgets back at most 8192 accepted IP timestamps. Queue IP quota remains separately configurable.

A live lease matching original UUID/IP gets protected global headroom, still constrained by UUID/IP limits. This does not authenticate offline users. Defaults IP 20/quota 5 can reject legitimate shared-NAT traffic; measure labeled-client false positives and tune, rather than treating these defaults as universal. See the [NAT scenario and migration](../releases/1.2.0.md).

`messages.kick-paused` is optional with a Vietnamese fallback. `<eta>` now uses successful queued JOIN observations, reports an approximate range, and shows `Đang cập nhật` with insufficient/stale traffic. No fixed per-position time remains. Pause is runtime-only, survives reload and resets on restart.
