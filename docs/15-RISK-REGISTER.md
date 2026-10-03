# Risk Register

Likelihood (L) and Impact (I): 1 low to 5 high. Score = L x I.

| ID | Risk | L | I | Score | Mitigation | Trigger / early signal |
|---|---|---|---|---|---|---|
| R01 | **Scope creep** across four languages stalls the project | 4 | 5 | 20 | Phase gates with exit criteria; finish Phases 1 to 2 before anything else; backlog parking lot | Starting UI work before R1 is published |
| R02 | Binance endpoints restricted or unavailable in the author's region | 3 | 4 | 12 | Verify early (Phase 1 day 1); use market-data-only endpoints; connector trait enables Coinbase/Kraken; use public historical dumps for tests | Connection/HTTP 451 errors |
| R03 | Order-book edge cases cause silent drift | 3 | 5 | 15 | Oracle + property tests; fault-injection replays; live cross-check; invalid-until-resynced policy | Any non-zero mismatch counter |
| R04 | Latency claims are misleading (clock skew, loopback, ms timestamps) | 3 | 4 | 12 | Measurement rules in doc 11; publish methodology; same-clock headline metrics | Cross-host numbers presented as precise |
| R05 | PyO3/maturin build complexity delays research | 3 | 3 | 9 | Keep boundary coarse; CI wheel build early (Phase 3); fallback: gRPC/replay-to-Parquet batch path | Wheel fails on a platform |
| R06 | Optimized book bugs (window re-center, far levels) | 3 | 4 | 12 | Oracle differential tests; fuzzing; budgeted re-center; benchmark with volatile days | Oracle mismatch in property tests |
| R07 | JVM jitter/GC inflates fan-out latency | 3 | 3 | 9 | Allocation-light design; ZGC trial; measure Java stage separately; JFR | p99 Java stage over budget |
| R08 | Multi-user security flaw (IDOR, token handling) | 2 | 5 | 10 | Isolation tests; security checklist; dependency scans; code review focus on authz | Missing `user_id` filter in review |
| R09 | Ledger inconsistency (overspend, duplicate fills) | 2 | 5 | 10 | Single writer; idempotency; transactions; reconciliation job; property tests | Reconciliation diff non-zero |
| R10 | Backtest results misleading (overfitting, lookahead, cost omission) | 3 | 4 | 12 | Engine-enforced no-lookahead; walk-forward; deflated Sharpe; baselines; limitations section | Results too good vs baselines |
| R11 | Regulatory/legal misunderstanding about trading features | 2 | 4 | 8 | Paper trading only; disclaimers; no advice; exchange ToS review; no data redistribution | Feature requests for real orders |
| R12 | Free hosting limits (CPU, bandwidth, policy) break the demo | 3 | 3 | 9 | Local-first; recorded demo; read-only demo mode; document VM sizing | VM reclaimed or throttled |
| R13 | Disk fills from event logs | 3 | 2 | 6 | Retention policy; disk alerts; writer pauses below threshold | Disk over 85 percent |
| R14 | Developer time/motivation (solo, part-time) | 4 | 4 | 16 | Small releases, visible progress per phase, recorded demos, AI agent assistance with strict specs | Phase slipping more than 2x estimate |
| R15 | AI-generated code introduces subtle bugs (float money, hot-path allocs, lookahead) | 3 | 4 | 12 | `CLAUDE.md` rules; skills; review checklist; tests that detect violations (allocation test, lookahead test) | Unexplained benchmark regressions |
| R16 | Dependency/CVE problems | 3 | 3 | 9 | Automated audits; Dependabot/Renovate; pin versions; minimal deps in hot crates | Audit failures in CI |
| R17 | Clock jumps/NTP issues corrupt timing or ordering | 2 | 3 | 6 | Monotonic clocks for intervals; offset metric; marker records | Offset alarms |
| R18 | Exchange API changes break parsing | 3 | 3 | 9 | Version-tolerant parsing; raw-frame recording; parser regression fixtures; alerts on parse error rate | Spike in `parse_error_total` |
| R19 | Browser rendering cannot keep up with full-rate stream | 2 | 3 | 6 | Worker + rAF batching + canvas; server-side conflation; configurable depth | Dropped frames in perf tests |
| R20 | Public demo abuse (DoS, spam accounts, backtest abuse) | 3 | 3 | 9 | Rate limits, quotas, invite/read-only mode, resource limits | Traffic spikes |

## Review cadence
Review at the start of each phase; update scores; add new risks discovered during implementation; close risks with evidence.
