---
name: observability-latency-measurement
description: Measuring and reporting latency honestly and instrumenting services: stage timestamps, HdrHistogram, Prometheus metrics naming, Grafana dashboards, alerts, structured logging, tracing off the hot path, clock synchronization, benchmark methodology and result publishing. Use this skill whenever adding metrics, timers, dashboards or alerts, measuring or reporting latency numbers, writing benchmarks, building the System Health page, or reviewing performance claims.
---

# Observability and latency measurement

Spec: `docs/11-PERFORMANCE-AND-OBSERVABILITY.md`. Goal 1 is only as credible as its measurement.

## Measurement points
T0 exchange event time -> T1 Rust recv -> T2 parsed -> T3 book+metrics done -> T4 published -> T5 Java recv -> T6 Java WS send -> T7 browser parsed -> T8 painted.
Stage latencies come from **same-clock** deltas wherever possible (monotonic clock within a process; same-host wall clock across processes). Cross-host numbers (T1-T0, T8-T6) include network + clock offset and are **indicative only**.

## Honesty rules (always)
1. Headline numbers = same-clock measurements (Rust internal T4-T1; same-host T6-T1). Label others "indicative".
2. Exchange timestamps are milliseconds (Binance) - never present exchange-based latency with micro/nanosecond precision.
3. Report p50, p90, p99, p99.9, max and sample counts - never averages alone.
4. State hardware, OS, CPU governor, build profile, message rate, loopback vs network, clock offset.
5. A documented miss with root cause beats an unexplained pass. Never claim "better than X" without a measured, like-for-like comparison.

## Instrumentation patterns
- **Rust:** `Instant` at stage boundaries; deltas into pre-allocated `hdrhistogram`s (per stage, per worker); aggregation/export on a separate thread; counters `AtomicU64` (Relaxed). No allocation, no logging per message. Optional sampling (1/N) with measured overhead.
- **Java:** `System.nanoTime()` for in-process deltas; Micrometer `Timer` with **percentile histograms** (bucketed, aggregatable) not client-side percentiles; virtual-thread and GC metrics.
- **Browser:** `performance.now()` locally; server timestamps in the envelope; overlay shows rolling medians.
- **Clock sync:** chrony/NTP on all hosts; export offset gauge; flag results taken when offset exceeds threshold.
- **Overhead check:** compare with instrumentation on/off and publish the delta.

## Metric naming and design (Prometheus)
`snake_case`, base units in the name (`_seconds`, `_bytes`), counters end with `_total`, low-cardinality labels only (symbol, stage, reason; **never** user_id or request id). Use histograms with explicit buckets tuned for the expected range (microseconds to tens of ms). Define metrics once in a catalogue (doc 11 section 5); keep names stable.

## Dashboards and alerts
Provision Grafana from files in `infra/grafana/`: Latency (per-stage heatmaps + p50/p99/p99.9 with budget lines), Feed health, Fan-out, Trading, Security, Resources. Alerts: feed disconnected, book invalid > 30 s, any mismatch, p99 over budget, conflation spike, log drops, disk low, ledger diff != 0, auth anomalies. Each alert links to a runbook entry.

## Logging and tracing
Structured JSON with `ts, level, service, trace_id, request_id, user_id (when applicable), msg`; redact secrets; no per-message logs on the hot path (state transitions only). OpenTelemetry traces for control flows (orders, backtests, resyncs) with sampling; **never trace per market-data message** - rely on in-message timestamps.

## Benchmark methodology
Controlled environment (performance governor, no background load), warm-up, multiple repetitions, report variance; **open-loop** load generators (avoid coordinated omission); store `raw.json`, `summary.json`, `env.json` under `benchmarks/results/<date>-<commit>/`; generate charts from `summary.json` with a committed script; README scorecard links to the exact result directory. CI benchmarks are regression signals only; publish numbers from a controlled machine.

## Profiling toolbox
Rust: `perf`, flamegraph, samply, cargo-show-asm, DHAT/heaptrack. Java: JFR, async-profiler, GC logs. Frontend: Chrome Performance panel, Long Tasks API. System: `perf stat`, `pidstat`, `ss`, `tcpdump` (when diagnosing network).

## Anti-patterns
Averages as headlines; mixing clock domains silently; unbounded label cardinality; measuring in debug builds; timing inside a tight loop with a syscall-heavy clock without checking overhead; hiding conflations/drops (they are part of latency truth); publishing numbers without methodology.

## Review checklist
- [ ] New stage has a timer and a counter before it is "done"
- [ ] Clock domain of each number stated; indicative numbers labeled
- [ ] Metrics follow naming/cardinality rules; catalogue and dashboards updated
- [ ] Benchmark results saved with env metadata and commit
