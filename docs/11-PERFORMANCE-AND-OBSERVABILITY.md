# Performance and Observability

Goal 1 (real-time latency) is the top priority, and its credibility depends on **how it is measured**. This document defines the measurement points, budgets, methods, and dashboards.

## 1. Latency definitions

Measurement points (monotonic timestamps within a process; wall-clock UTC for cross-process):

| Point | Where | Meaning |
|---|---|---|
| T0 | Venue | Exchange event time (`exchange_ts`; Binance provides milliseconds) |
| T1 | Rust reader | Frame received from the socket (`recv_ts`) |
| T2 | Rust worker | Parse finished |
| T3 | Rust worker | Book and metrics updated |
| T4 | Rust broadcaster | Message handed to the gRPC stream (`publish_ts`) |
| T5 | Java gateway | Message received from gRPC |
| T6 | Java session writer | Frame written to the WebSocket (`gateway_send_ts`) |
| T7 | Browser | Message parsed in the worker |
| T8 | Browser | Frame painted (after `requestAnimationFrame`) |

Derived stage latencies:

| Stage | Formula | Clock domain | Precision |
|---|---|---|---|
| Parse | T2 - T1 | Rust monotonic | ns |
| Book + metrics | T3 - T2 | Rust monotonic | ns |
| Queue + publish | T4 - T3 | Rust monotonic | ns |
| **Rust internal (headline internal)** | T4 - T1 | Rust monotonic | ns |
| gRPC hop | T5 - T4 | Cross-process, same host: wall clock (or monotonic if same host/boot) | us (same host) |
| Java fan-out | T6 - T5 | Java monotonic | us |
| **Receive-to-WebSocket-send** | T6 - T1 | Same-host wall clock | us |
| Exchange-to-receive | T1 - T0 | Different hosts: depends on NTP offset, ms resolution | **ms, indicative only** |
| Browser | T8 - T6 | Different hosts: clock offset | ms, indicative only |

**Honesty rules:**
- The **precise** headline numbers are same-clock measurements (Rust internal, and receive-to-WebSocket-send on one host). Cross-host numbers (exchange-to-receive, server-to-browser) include network latency and clock offset and are reported as indicative, with measured clock offset when available.
- Binance timestamps are millisecond resolution; never report exchange-based latency with microsecond precision.
- Always state: hardware, OS, CPU governor, network location, build profile, message rate, and whether loopback was used.
- Report p50, p90, p99, p99.9, and max; plus sample counts. No averages alone.

## 2. Budgets (targets, to be validated)

| Stage | p50 | p99 |
|---|---|---|
| Parse (Rust) | under 20 us | under 100 us |
| Book + metrics | under 5 us | under 50 us |
| Rust internal (T4 - T1) | under 100 us | under 1 ms |
| gRPC hop (same host) | under 200 us | under 1 ms |
| Java fan-out (T6 - T5), 1,000 clients | under 1 ms | under 5 ms |
| Receive-to-WS-send (T6 - T1) | under 2 ms | under 5 ms (see NFR-02) |
| Browser frame (T8 - T7) | under 8 ms | under 16 ms |

If a stage misses its budget, the profile and fix go into the write-up; a documented miss with a root cause is better than an unexplained pass.

## 3. How to measure

- **Rust:** monotonic clock (`Instant`) at stage boundaries; store deltas in `hdrhistogram` (per stage, per symbol group); expose summaries as Prometheus histograms/gauges. Timestamps are plain integers written into the message struct; no heap allocation. Sampling (e.g. 1 in N messages) is allowed if overhead is measured and reported.
- **Java:** `System.nanoTime()` deltas for in-process stages; wall-clock difference for cross-process; Micrometer timers with explicit percentile histograms (not client-side summaries) for aggregability.
- **Browser:** `performance.now()` for local stages; server timestamps included in the envelope; latency overlay shows medians over a rolling window.
- **Clock sync:** run chrony/NTP on all hosts; export offset as a metric; flag measurements taken when offset exceeds a threshold (e.g. 5 ms).
- **Overhead check:** compare throughput/latency with instrumentation on and off; publish the delta.

## 4. Benchmark methodology

- **Micro (Rust criterion):** parse, apply, metrics, each with realistic recorded inputs; compare oracle vs optimized, serde_json vs simd-json, and allocator choices.
- **Macro (replay):** feed a recorded day at max speed to measure throughput (events/s/core) and per-stage latencies; also at 1x for steady-state live-like behavior.
- **Load (Java):** open-loop constant-rate generator; 100, 500, 1,000, 2,000 simulated clients; report added latency, drops/conflations, CPU, memory, GC pauses.
- **Environment control:** performance governor, isolated cores if possible, no background load, fixed Rust and JVM flags; run N repetitions; report variance.
- **Profiling tools:** `perf`, flamegraph, `samply`, `cargo-asm`/`cargo-show-asm`, DHAT or heaptrack for allocations, `async-profiler`/JFR for Java, Chrome DevTools performance panel for the frontend.
- **Build profile (Rust):** `lto = "fat"`, `codegen-units = 1`, `opt-level = 3`; consider `panic = "abort"` for the feed binary (not for `pybook`); allocator comparison (system vs mimalloc/jemalloc); `target-cpu=native` only for local benchmarks (never for distributed artifacts).
- **JVM flags (Java):** document GC (G1 default; try ZGC/Generational ZGC), heap size, `-XX:+AlwaysPreTouch`; record JFR for GC and allocation hot spots.

## 5. Metrics catalogue

### Rust feed (Prometheus)
| Metric | Type | Labels |
|---|---|---|
| `feed_messages_total` | counter | venue, symbol, kind |
| `feed_stage_latency_seconds` | histogram | stage (parse, book, publish, internal) |
| `feed_book_valid` | gauge | symbol |
| `feed_resync_total` | counter | symbol, reason |
| `feed_epoch` | gauge | symbol |
| `feed_book_mismatch_total` / `feed_book_checks_total` | counter | symbol |
| `feed_reconnect_total` | counter | venue |
| `feed_queue_depth` | gauge | queue (reader, writer, subscriber) |
| `feed_conflation_total` | counter | symbol |
| `feed_log_dropped_total` | counter | reason |
| `feed_clock_offset_seconds` | gauge | - |
| `feed_recenter_total`, `feed_far_level_count` | counter, gauge | symbol, side |

### Java API
| Metric | Notes |
|---|---|
| `gateway_stage_latency_seconds` | histogram: grpc->send |
| `ws_sessions`, `ws_subscriptions` | gauges |
| `ws_queue_depth`, `ws_conflations_total`, `ws_closed_total{reason}` | fan-out health |
| `http_server_requests` | Micrometer standard with percentile histograms |
| `orders_total{status}`, `fills_total`, `order_latency_seconds` | trading |
| `ledger_reconciliation_diff` | gauge; non-zero is an alarm |
| `auth_failures_total`, `refresh_reuse_total`, `rate_limited_total` | security signals |
| JVM metrics | GC pause, heap, threads (including virtual thread pinning events) |

### Python
`backtest_jobs{status}`, `backtest_duration_seconds`, `replay_events_per_second`, `data_gaps_detected_total`.

### Frontend (sent to Java in sampled beacons or computed in the overlay only)
fps, dropped frames, main-thread long tasks, WS reconnects, resync events.

## 6. Dashboards (Grafana, provisioned from files in `infra/grafana/`)

1. **Latency:** per-stage heatmaps and p50/p99/p99.9 lines; receive-to-send; budget thresholds shown as lines.
2. **Feed health:** connection state, book validity, resyncs, mismatch counter, queue depths, message rates.
3. **Fan-out:** sessions, subscriptions, queue depth, conflations, drops, per-stage latency.
4. **Trading:** orders by status, reject reasons, fill counts, reconciliation diff.
5. **Security:** login failures, lockouts, token reuse, 429s.
6. **Resources:** CPU, memory, GC, disk (event-log growth), network.

The **System Health** page in the UI embeds a curated subset (from Java `/system/latency`) so the demo does not require Grafana access.

## 7. Alerting (Alertmanager or Grafana alerts)

| Alert | Condition |
|---|---|
| Feed disconnected | `up == 0` or reconnect rate high for 2 min |
| Book invalid too long | `feed_book_valid == 0` for over 30 s |
| Any mismatch | `increase(feed_book_mismatch_total[1h]) > 0` |
| Latency budget breach | p99 internal over budget for 5 min |
| Conflation spike | conflation rate above threshold |
| Event log dropping | `feed_log_dropped_total` increasing |
| Disk low | free space under 15 percent |
| Ledger diff | `ledger_reconciliation_diff != 0` |
| Auth anomaly | failure rate spike |

## 8. Logging

- Structured JSON, one line per event; fields: `ts`, `level`, `service`, `trace_id`, `request_id`, `user_id` (when applicable), `msg`, context fields.
- **Rust hot path:** no per-message logging; use counters and sampled debug events. Warnings/errors for state transitions (reconnect, resync, invalid).
- Retention: local files with rotation; ship to Loki only if desired (optional).

## 9. Tracing

OpenTelemetry for Java and Python; trace ids propagated across HTTP and gRPC for control-path flows (orders, backtests). **Do not trace per market-data message**; use timestamps in messages for the live path. For Rust, optional spans on control operations (resync, reconnect, snapshot fetch) only.

## 10. Publishing results

- Each benchmark writes `benchmarks/results/<date>-<commit>/{raw.json, summary.json, env.json}`.
- Charts generated by a script from `summary.json` (committed script, ignored images or committed small PNG/SVG).
- README scorecard links to the exact result directory so numbers are traceable to a commit and environment.
