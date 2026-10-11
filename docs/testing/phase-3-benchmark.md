# Phase 3 — local baseline comparison

Measured 2026-10-11 in the shared coding container, Temurin 21.0.12.1+1, 9 visible logical CPUs, three separate JVM forks per variant, default heap/GC. Baseline is merged phase 2 `bc72811e4c385fee78a7664baacd076f74be5143`; candidate is the phase 3 code in this PR. No actual Paper process, socket/client, LuckPerms backend, exporter, join throughput or production network is exercised.

## Workload and interpretation

Both classpaths run the identical `QueueLoadBenchmark`: 500 waiting identities, 50 workers, every tenth name assigned tier 10, effective capacity 1 already occupied, UUID cooldown disabled, immediately completed verified permission doubles. Workers retry existing queue identities; all measured results must be `IN_QUEUE`, no in-flight/capacity increase. Anti-bot-off warms 4,000 attempts and measures 10,000; anti-bot-on warms 1,000 and measures 2,000 after initial enrollment, using IP/global 4096/window 1 second/quota 500 so the sample does not turn into cheap rejected requests. Fork order alternates baseline/candidate. Decision latency uses `nanoTime` around the manager API; throughput includes scheduling/aggregation/sorting.

These samples are short and the host is shared. They establish a reproducible smoke comparison, not statistical significance or G3 performance acceptance. Ordinary throughput medians declined about 7.2% (anti-bot off) and 3.3% (on); p95 medians increased, with substantial tail/ordering variance between forks. **No “no regression” claim is made.** Longer identical sustained real Paper runs, provider latency and GC/heap profiles are required before deciding an acceptable budget. The benchmark is deliberately retained so these results can be challenged/repeated.

## Median of three forks

| Anti-bot | Variant | Attempts/s | Decision p50 ms | p95 ms | p99 ms |
|---|---|---:|---:|---:|---:|
| false | baseline | 54586.4 | 0.0152 | 0.0454 | 21.8410 |
| false | phase3 | 50653.4 | 0.0154 | 0.1359 | 16.0286 |
| true | baseline | 40923.7 | 0.0184 | 0.1041 | 16.7042 |
| true | phase3 | 39590.8 | 0.0195 | 0.2636 | 19.7906 |

## Raw forks

| Anti-bot | Variant | Fork | Calls | Seconds | Attempts/s | p50 ms | p95 ms | p99 ms |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| false | baseline | 1 | 10000 | 0.1642 | 60892.0 | 0.0114 | 0.0530 | 21.8410 |
| false | phase3 | 1 | 10000 | 0.1974 | 50653.4 | 0.0154 | 0.1359 | 16.0286 |
| false | phase3 | 2 | 10000 | 0.2765 | 36166.5 | 0.0209 | 0.0658 | 41.5582 |
| false | baseline | 2 | 10000 | 0.1841 | 54312.0 | 0.0152 | 0.0454 | 22.8812 |
| false | baseline | 3 | 10000 | 0.1832 | 54586.4 | 0.0161 | 0.0400 | 20.1559 |
| false | phase3 | 3 | 10000 | 0.1776 | 56299.3 | 0.0114 | 0.1456 | 11.9708 |
| true | baseline | 1 | 2000 | 0.0643 | 31110.1 | 0.0184 | 0.1331 | 26.4832 |
| true | phase3 | 1 | 2000 | 0.0505 | 39590.8 | 0.0191 | 0.2636 | 19.7906 |
| true | phase3 | 2 | 2000 | 0.0642 | 31130.0 | 0.0206 | 0.1475 | 28.8415 |
| true | baseline | 2 | 2000 | 0.0489 | 40923.7 | 0.0188 | 0.0784 | 16.1227 |
| true | baseline | 3 | 2000 | 0.0480 | 41677.9 | 0.0168 | 0.1041 | 16.7042 |
| true | phase3 | 3 | 2000 | 0.0501 | 39922.4 | 0.0195 | 1.3292 | 16.3902 |

## Reproduction

JDK 21 and Python 3 standard library only. Run on an otherwise quiet host; preserve reports and classpaths. From the candidate checkout:

```bash
git worktree add --detach ../queue-phase2-baseline bc72811e4c385fee78a7664baacd076f74be5143
mvn -B -f ../queue-phase2-baseline/pom.xml -DskipTests test-compile
mvn -B clean install
python3 scripts/compare-queue-load.py --baseline-classes ../queue-phase2-baseline/target/classes --forks 3
mvn -B clean verify -f staging-probe/pom.xml
```

The comparison script reads the successful Surefire classpath, compiles the same manual harness once, and puts each variant's manager classes first in a fresh JVM. Do not run `clean`, compile another build, or run the probe build concurrently with the benchmark. Changing limits/workload/classpaths invalidates a direct comparison. The smoke regression separately stresses 500 queued identities/50 concurrent attempts with metrics reads; it checks correctness, not an absolute speed threshold. See [G3 runbook](phase-3-staging.md) for labeled NAT false positives, real admission ETA, and operator-approved latency/heap gates.
