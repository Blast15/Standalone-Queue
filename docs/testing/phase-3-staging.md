# Phase 3 — G3 staging acceptance

Status: automated code checks/local synthetic comparison available; **real Paper/client/provider/network tests and operator-approved anti-bot/performance thresholds pending**. Retain the [phase 2 event/probe matrix](phase-2-staging.md); phase 3 does not waive G2 compatibility tests.

Use separate baseline and candidate instances with the same Paper version, JDK 21, heap, capacity, LuckPerms backend, permissions, client arrival schedule and network latency. Use isolated test accounts/IPs with a genuine NAT client group; an API string pretending to be an IP does not test network authentication. Run online-mode plus the documented restricted offline mode, ban/whitelist and max-player override combinations. Do not run the staging probe on production.

## Automated evidence

`mvn -B clean install` runs Surefire and Failsafe, including `PhaseThreeRegressionTest`, phase 1/2 deadlines and 500/50 concurrency, plus the Paper API login pipeline. `mvn -B clean verify -f staging-probe/pom.xml` builds the independent probe after installing the parent. CI uploads both JARs and the reports.

The phase 3 regression covers separate reason counters, UUID cooldown longer than 60 s and exact expiry, NAT 50/bot IP 200, exact lease headroom/different-IP denial, IP enforcement on owners, 4096 tracking/expiry, strict ceilings, pause/resume/in-flight continuity, safe move, private inspect/metrics, successful JOIN-only rolling history/EWMA/no-data ETA, granular permission/tab completion, audit sanitization, and concurrent metrics/cleanup/admin mutations. Synthetic 500-entry/50-worker attempts retain non-bypass capacity. Tests are not proof of a deployed anti-bot threshold.

## Real staging matrix

| Case | Procedure and required evidence |
|---|---|
| T-014 NAT/bot | First capture default IP/quota rejects; then use controlled IP 60/global 150/quota 60. Run 50 legitimate clients behind NAT and 200 bot attempts from another IP. Capture counter deltas, accepted/denied legitimate accounts and exact reason; legitimate group must not be wholly locked out by the other IP. |
| Lease burst | Fill ordinary global budget while a live authenticated lease owner reconnects from original IP; verify it redeems through protected budget. Repeat changed-IP, UUID cooldown, IP quota and exhausted protected-budget variants. No wrong owner/expired lease may redeem. |
| T-016 500/50 VIP | Queue 500 clients across priority tiers, issue 50 concurrent attempts while varying capacity and bypass. Record online + in-flight + live lease ownership; non-bypass overfill/double admission must be zero. |
| ETA | Drain queue at steady, slower, burst and idle rates. Record successful queued JOIN timestamps, 5/15-minute counts and screen ETA. Include failed/late login, quits without JOIN, direct/bypass traffic and uncompleted redeemed admissions. No fake ETA/NaN; pause/insufficient/stale samples show updating. |
| Admin | Grant only one granular node per operator. Verify dispatch, help/tab and Bukkit command permission. Move within tier succeeds; crossing tier/lease fails. Pause preserves in-flight and existing lease; resume allocates. Capture actor/action/result audit, with console suppression both on/off. |
| Privacy/lifecycle | Inspect both waiting and accepted connection; metrics contain no identity labels. Verify counters/history/pause reset on restart, pause persists across reload, and no HTTP listener is opened. |
| Saturation | Apply UUID/IP floods beyond 4096 tracking identities and IP/global window ceilings. Heap profile must stabilize; each accepted IP timestamp is backed by one of two bounded global budgets. Measure O(n) queue scans at configured queue sizes. |

Take `/queue stats` snapshots before/after each labeled scenario. Decision counters distinguish blocked traffic from waiting decisions; grant is not JOIN success. Calculate legitimate false positives as rejected legitimate attempts / all labeled legitimate attempts, separated by reason and NAT group. Do not infer false positives from aggregate server counters alone.

Measure request latency p50/p95/p99, accepted/waiting/rejected throughput, permission provider wait, CPU/TPS and heap/GC on baseline and candidate using identical arrival schedules. Repeat enough to establish variance; set allowable regression and memory/false-positive ceilings with operations **before** evaluating the candidate. Local results are [diagnostic evidence](phase-3-benchmark.md), not a Paper performance SLA.

## G3 record (pending)

| Requirement | State |
|---|---|
| Automated invariant/regression/event API suite | Run in local build and CI; attach final reports |
| Reproducible local baseline/candidate workload | Recorded separately |
| Paper dashboard matches real JOIN/capacity/reject events | Pending |
| Real NAT false-positive rates and approved anti-bot thresholds | Pending |
| ETA useful during real demand changes | Pending |
| Staging latency/CPU/heap within approved baseline targets | Pending |
| Human review and release decision | Pending |

Keep the PR draft until these records are complete. This phase does not merge, tag or deploy a production release automatically.
