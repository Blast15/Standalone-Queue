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

Runtime control is available with `/queue antibot on|off|status`; `/queue reload` returns to the file value. These limits do not replace firewall or DDoS protection.

`max-queue-size` bounds retained players and must be positive; invalid or zero values fall back to 500. `keep-alive-seconds` and `slot-reservation-seconds` must also be positive and fall back to safe defaults. Priority weights are configured under `priorities`.

## Pre-login permissions (1.0.2)

```yaml
permissions:
  resolve-timeout-ms: 1000 # clamped to 1..5000 ms
```

On a standalone server with `online-mode=true`, LuckPerms 5.4+ is optional and is discovered at startup. Each eligible attempt loads the authenticated UUID from LuckPerms, calculates permissions using static server contexts, and discards the result after admission. There is no join/quit rights cache. At most 64 storage lookups remain outstanding; a timeout does not free its permit until storage finishes. Timeout, failure, saturation, or a snapshot aged 2 seconds grants only ordinary rights. Late results cannot upgrade a completed decision.

Without LuckPerms, a main-thread operator snapshot is refreshed every 20 ticks and expires at 2 seconds. It supports OP bypass from the first connection. Arbitrary permission attachments from other plugins are not available at pre-login. With LuckPerms, an explicit `queue.bypass=false` denies OP bypass; an undefined value falls back to the fresh operator snapshot. World/player-specific contexts are intentionally unsupported before joining.

`online-mode=false` grants neither bypass nor priority from names/offline UUIDs, even with LuckPerms or OP. Authenticated proxy forwarding is not auto-detected in this standalone release. See the [migration and staging guide](../releases/1.0.2.md).
