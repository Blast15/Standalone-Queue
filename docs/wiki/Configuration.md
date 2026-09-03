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

Permission and OP checks are captured while a player is on the main server thread and reused on reconnect. A player who has never joined since plugin startup has no safe async permission snapshot, so their first queued attempt uses normal priority and no bypass; this avoids unsafe permission-provider calls from the asynchronous pre-login event.
