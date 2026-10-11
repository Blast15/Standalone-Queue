# Đối chiếu lộ trình — ứng viên 2.0.0

Nguồn: hai tài liệu người dùng cung cấp, `StandaloneQueue_LoTrinhNangCap(4).md` và `StandaloneQueue_TongHopVanDe(4).md`, lập từ v1.0.1 @ `9f220ed`. Audit hiện tại bắt đầu ở `main` @ `b64d22d`. Các mốc 1.0.2/1.1.0/1.2.0/1.3.0 trong roadmap là đề xuất; phiên bản tổng hợp theo yêu cầu là **2.0.0**.

| Phần | Trạng thái khi bắt đầu | Kết quả ở ứng viên 2.0.0 |
|---|---|---|
| G0: invariants, fake clock/resolver, baseline | Đã có | Giữ contract/harness, thêm recovery và benchmark so với main |
| G1: quyền, lease, TTL, snapshot | Đã gộp PR #1 | Giữ test hồi quy, kiểm tra cùng build mới |
| G2: lifecycle, reload, logging, messages | Đã gộp PR #2 | Giữ integration test và probe; persistence config có restart policy |
| G3: anti-bot, ETA, quản trị, metrics | Đã gộp PR #3 | Bổ sung expiry/permission counters và wait histogram |
| G4: storage/recovery/migration | Chưa có | Snapshot atomic async, ownership, validation, recovery suite, hướng dẫn backup/rollback |
| G5: tích hợp tùy chọn | Chưa chốt scope | Deferred theo chính tài liệu; không đưa Discord/HTTP/đa ngôn ngữ mới vào 2.0.0 |
| Gate Paper/canary/SLO và review độc lập | Chưa nghiệm thu | Blocked: cần server/client/provider thật và người review |

## Ma trận SQ-001…SQ-016

| Issue | Triển khai và bằng chứng |
|---|---|
| SQ-001, SQ-003 | PermissionResolver trước admission, OP snapshot TTL, LuckPerms async; PhaseOneRegressionTest/LuckPermsPermissionResolverTest |
| SQ-002, SQ-004 | Lease theo owner/token, expiry dưới lock, thu hồi mới nhất khi co capacity; PhaseOneRegressionTest |
| SQ-005 | Kiểm TTL trước heartbeat, deadline nhất quán; PhaseOneRegressionTest |
| SQ-006 | Attempt token + Player identity, timeout không giải phóng handshake còn sống; PhaseTwoRegressionTest/PaperLoginPipelineIT |
| SQ-007 | Sorted cache chỉ ghi dưới write lock, detached queue views; PhaseOneRegressionTest concurrency |
| SQ-008 | Immutable settings, bounded resolve ngoài lock, nguyên tử khi reload; QueueSettingsReloadTest |
| SQ-009 | Disconnect provenance, bảo toàn WARN/ERROR/exception, suppress mặc định tắt; ConsoleFilterTest |
| SQ-010 | Reject reasons, bounded budgets, budget riêng lease-owner, NAT/load tests; PhaseThreeRegressionTest |
| SQ-011 | Successful queued JOIN history/EWMA, không đủ dữ liệu thì không đoán ETA; PhaseThreeRegressionTest |
| SQ-012 | EXPIRED_AND_REQUEUED/message timeout/paused, ready template deprecated; PhaseTwoRegressionTest |
| SQ-013 | Statistics/Prometheus text API, expiry/failure counters, wait histogram; PhaseThreeRegressionTest |
| SQ-014 | QueueSnapshotStore/QueuePersistence, waiting-only restore, quyền hiện tại, giữ file lỗi; QueueRecoveryTest |
| SQ-015 | inspect/stats/pause/resume/move, granular permissions/tab/audit; QueueCommandTest/PhaseThreeRegressionTest |
| SQ-016 | Fake clock/provider/capacity, concurrent regressions, Paper event doubles, recovery suite, CI Maven verify + probe |

T-001…T-008/T-010…T-012 nằm trong phase 1/2 suites; T-009 trong lifecycle tests; T-013 logging; T-014 NAT; T-015 recovery; T-016 500 waiting/50 workers và simultaneous admission tests. Test permission thật/EventBus/network vẫn thuộc staging, không được suy diễn từ doubles.

## Điều kiện còn phải nghiệm thu

1. [Paper offline network matrix](testing/real-paper.md) đã chạy với ban/whitelist, đăng nhập chậm, late rejection và handoff. Còn online-mode với Mojang account và LuckPerms/backend/revoke thật.
2. Tải network thật 500 waiting/50 online và shared-IP quota đã kiểm tra; còn [G3 NAT/ETA](testing/phase-3-staging.md), đo false-positive/heap/p95/p99 và chốt ngưỡng vận hành.
3. Persistence bật trên staging: graceful restart, SIGKILL sau snapshot hoàn tất và corrupt snapshot đã kiểm tra. Còn kill giữa flush/syscall, power-loss filesystem và thay đổi quyền thật trong downtime.
4. Review độc lập, canary và rollback rehearsal trước tag/release production. Chưa merge/release chỉ vì suite dùng doubles xanh.

Mã triển khai giai đoạn 0–4 đã có ứng viên để review; **không đánh dấu toàn bộ exit gate đã Done** khi chưa có bằng chứng staging.

## Nguồn GitHub

- Repository: https://github.com/Blast15/Standalone-Queue
- Baseline audit: https://github.com/Blast15/Standalone-Queue/commit/b64d22d
- G1 PR: https://github.com/Blast15/Standalone-Queue/pull/1
- G2 PR: https://github.com/Blast15/Standalone-Queue/pull/2
- G3 PR: https://github.com/Blast15/Standalone-Queue/pull/3
- Mốc tài liệu gốc: https://github.com/Blast15/Standalone-Queue/tree/9f220edafaab135873c374beca0d0619bd677585
