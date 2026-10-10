# Operations Runbook

```text
/queue status
/queue list
/queue clear
/queue kick <player>
/queue antibot status
/queue antibot on|off
/queue reload
```

During a join-bot burst, keep anti-bot enabled, tune `global-attempts-per-window`, and keep `max-queue-size` bounded. If the host is saturated before Paper can process login events, apply network-layer controls as well.


`/queue status` includes in-flight, overdue in-flight and total timeout counts. Turn on `queue.debug-log` for token/UUID/phase lifecycle traces. A timeout still holding capacity means Paper has not confirmed login failure or close; the plugin does not drop that slot early. Check login plugins and server transport logs.

Reload succeeds only after validation and complete permission refresh/reconciliation. On failure, the command reports the cause and keeps the old generation and anti-bot override. Retry after queue membership stabilizes or fix provider/config errors. Successful reload clears UUID/IP/global window history and resets runtime anti-bot to file value. No restart-free provider discovery or authentication-mode change is supported.

Console suppression is a separate opt-in setting, `logging.suppress-queue-noise: false` by default. Unknown log sources/formats and warnings/errors/throwables remain visible. Follow the [phase 2 staging runbook](../testing/phase-2-staging.md) before release; remove the separate probe after testing.
