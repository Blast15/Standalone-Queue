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

`max-queue-size` bounds retained players. `keep-alive-seconds` removes inactive entries. `slot-reservation-seconds` protects the next slot for the first queued player. Priority weights are configured under `priorities`.
