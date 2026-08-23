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
