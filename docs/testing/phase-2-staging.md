# Phase 2 verification and Paper staging

## Automated layer

Run on Java 21:

```sh
mvn -B clean verify
```

The production listeners receive **real Paper 1.21.4 API event objects** in `PaperLoginPipelineIT`. A guarded capacity source fails if pre-login/cleanup/close/reload touches a main-thread-only server read. Authenticated-identity and LuckPerms API/storage responses are controlled fixtures, not real account authentication. There is no live server/event bus or real backend in this layer.

| Acceptance | Automated coverage |
|---|---|
| T-009: late handshake | Deadline −1/exact/+1 ms; timeout retains slot; late login rejects; accepted login can join late; transport close releases. |
| T-011: reload under load | 500 waiting players with concurrent reload, reconnect, cleanup and detached readers; stable lease IDs and oldest-first shrink. |
| T-012: failed reload | Invalid ranges/types/YAML, unreadable file, provider failure/timeout/staleness, changed membership, newer request and shutdown preserve last good state. |
| T-013: source-aware logging | Both backends; opt-in default; source/name/IP/reason/TTL matching; WARN/ERROR and embedded throwables survive; unknown/plugin/chat/ban logs stay. |
| SQ-012: message state | Expired notice once through actual pre-login event; new position and MiniMessage/legacy placeholders. |
| SQ-016: login pipeline | Online-mode identity fixture with production LuckPerms adapter; offline ordinary rights; full override; ban/whitelist; late pre-login/login rejection; join/quit/close; canary reload. |

The existing phase 1 correctness/permission/FIFO coverage is retained. Phase 1 release-token test fixtures now supply exact tokens and close a connection before reusing its UUID; expiry assertions now expect the explicit expired decision.

## Build the staging companion

The companion is independent from the production JAR. It does not arm any delay/rejection by default.

```sh
mvn -B clean install
mvn -B clean verify -f staging-probe/pom.xml
```

Install `target/StandaloneQueue-1.1.0-SNAPSHOT.jar` and `staging-probe/target/StandaloneQueue-StagingProbe-1.1.0-SNAPSHOT.jar` into an **isolated** Paper 1.21.4 / Java 21 staging server's `plugins` directory. It depends on StandaloneQueue, so its HIGHEST fixtures register after the queue listener. Do not put the probe on production; remove it after testing. Use console for administrative commands so the test accounts remain ordinary.

Back up config and record Paper build, Java version, LuckPerms version/backend, other login plugins, online mode and both JAR SHA-256 values. Use two legitimate authenticated clients for online tests, or ordinary local clients for the isolated offline matrix. This procedure does not automate account login, change EULA acceptance or claim that offline identity is secure.

Start with capacity 1, rate-limit 0, keep-alive 300, reservation 60, `debug-log=true`, anti-bot disabled for this small controlled test, and `in-flight-timeout-seconds: 5`. The 5-second timeout is only a fixture to stay below Paper transport limits; restore 30 seconds and tune from actual observations afterward.

## Real-server acceptance

| Case | Action | Expected trace / outcome |
|---|---|---|
| Normal admission | Ordinary A connects to an empty server, then B connects. | A pre-login → login → join; B waits. In-flight becomes zero on A join; online=1. |
| Reservation handoff | A quits; B reconnects before 60 seconds. | A quit → close; B owns lease, admitted login → join; no ready kick, no double capacity. |
| Login within deadline | Console: `sqprobe delay A 2000`; A connects to an empty server. | One targeted async delay after grant; A completes login/join while its slot stays counted. |
| Timeout safety | Console: `sqprobe delay A 6000`; A connects, B tries during delay. Run `queue status`. | A's slot stays occupied after 5s; B waits; A either reaches rejected late login or Paper closes transport. After failure/close, no in-flight leak. Paper closing earlier is a transport outcome, not proof of late-login success. |
| Failed pre-login | Console: `sqprobe deny-pre A`; A connects to an empty server. | Late HIGHEST denial; queue MONITOR cancels that token; no close is required and no in-flight record remains. |
| Failed login | Console: `sqprobe deny-login A`; A connects. | Final login rejection frees in-flight; UUID ownership persists only until Paper close. Probe's rejection reason remains visible with suppression enabled. |
| Ban/whitelist | Try genuinely banned and non-whitelisted accounts while capacity is full. | Original rejection stays; an owned attempt, if any, releases on final failure/close. |
| Rapid duplicate | Try a second connection for A while its delay or joined connection remains alive. | Duplicate is rejected without releasing or replacing A's token. Reconnect after old close succeeds according to queue policy. |
| Priority reload | With occupied slot, queue B then VIP C; change C's weight and run `queue reload`. | Success publishes all weights/order together. B's existing live lease, if any, retains ID/deadline. |
| Capacity reload | Give B/C two leases, lower capacity to one and reload. | Older lease survives, newest is revoked; FIFO sequence otherwise retained. |
| Invalid reload | Set negative duration, scalar section or broken YAML; run reload. | Explicit failure; old settings/order/leases and runtime anti-bot value stay active. Restore file afterward. |
| Runtime override | Set `queue antibot off`, then valid/invalid reloads. | Successful reload returns to file value and clears attempt windows; failed reload leaves override/history unchanged. |
| Logging | Enable suppression; repeat queue, ban and probe rejection cases; inspect other plugins' WARN/ERROR. | Only exact recognized recent queue disconnect noise can disappear. Queue debug traces, warning/error/exception and unrelated reasons remain. Unknown format stays visible. |

Repeat with **online-mode=true plus real LuckPerms** (fresh VIP/admin UUIDs, explicit OP bypass denial, revoke and slow/unavailable backend). For the second matrix, set `online-mode=false` only on the isolated server and verify OP/name reuse yields zero pre-login bypass/priority. Also repeat on the intended production Paper version and with its actual login plugins before claiming compatibility.

The probe emits `uuid`, `attempt`, event/result, phase, in-flight/online/reserved counts and thread name. Async delay is one-shot and name-targeted, maximum 45 seconds. `sqprobe reset` disarms the next fixture; it does not interrupt a delay already running. There are no main-thread sleeps. A delayed join after accepted login needs a genuine configuration/transport stall or the production login plugin; the probe only delays async pre-login. Record that scenario separately; the API harness covers its engine accounting.

## Results and release gate

| Layer | Status on 2026-10-10 |
|---|---|
| Unit/regression plus Paper API harness | Run by local Maven and PR CI; see reports for the exact revision/count. |
| Staging probe packaging | Built locally and by PR CI; not bundled into production. |
| Actual online/offline Paper clients, real LuckPerms storage, production login plugin, delayed join | **Pending — not executed in this workspace.** |
| Independent review, production rollout, release tag | **Pending.** |

Store staging logs, screenshots of late-login and expired-slot messages, server versions, JAR hashes and reviewer sign-off with the release record. G2 is complete only after the real-server matrix passes, including accepted-login late join. Restore normal protection/timeouts, remove the probe and restart before promotion.

## Paper contracts used

- [PlayerConnectionCloseEvent](https://jd.papermc.io/paper/1.21.4/com/destroystokyo/paper/event/player/PlayerConnectionCloseEvent.html): pre/post-world close; after quit for joined players; never concurrent with that connection's async pre-login; not emitted when async pre-login is cancelled; carries UUID/name/IP only.
- [PlayerLoginEvent](https://jd.papermc.io/paper/1.21.4/org/bukkit/event/player/PlayerLoginEvent.html): final login result and logical/real address.
- [AsyncPlayerPreLoginEvent](https://jd.papermc.io/paper/1.21.4/org/bukkit/event/player/AsyncPlayerPreLoginEvent.html): asynchronous admission and rejection.
