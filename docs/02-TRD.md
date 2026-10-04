# Technical Requirements Document (TRD)

**Version:** 0.1 | Companion to the PRD. Details live in docs 03 to 12; this document is the integrated technical design.

## 1. System overview

Four deployable units plus infrastructure:

| Unit | Tech | Responsibility |
|---|---|---|
| `feed` | Rust | Connectors, normalizer, order book, metrics, event log, replay, gRPC server |
| `api` | Java 21, Spring Boot | Auth, accounts, portfolios, paper trading, WebSocket fan-out, REST |
| `research` | Python 3.12 | Backtest engine (Rust book via PyO3), strategies, job service (FastAPI) |
| `web` | Next.js, TypeScript | Dashboard, trading ticket, research, health, marketing site |
| infra | Docker | Postgres, Redis, ClickHouse, Prometheus, Grafana, optional Redpanda |

**Live path (latency-critical):** Exchange -> `feed` -> gRPC -> `api` -> WebSocket -> `web`.
**Storage path (not latency-critical):** `feed` -> event log files -> (compactor) Parquet -> ClickHouse / `research`.
**Control path:** `web` -> `api` REST -> Postgres / `research` job API.

## 2. Technology choices and rationale

| Concern | Choice | Why | Alternatives considered |
|---|---|---|---|
| Fast feed | Rust (tokio) | No GC pauses, memory safety, great perf tooling | C++ (more risk), Go (GC pauses), Java (GC) |
| Product API | Java 21 + Spring Boot | Mature security, ORM/SQL tooling, virtual threads, industry standard for fintech back offices | Kotlin (also fine), Node |
| Research | Python 3.12 (Polars, DuckDB, NumPy, Numba) | Ecosystem for quant research | R, Julia |
| Python <-> Rust | PyO3 + maturin | One order-book implementation | Re-implement in Numba (drift risk), gRPC to Rust (too slow for backtests) |
| Frontend | Next.js (App Router) + TypeScript | SSR marketing site + client-only live app | Vite SPA |
| Service contracts | Protobuf + gRPC (buf) | Typed, versioned, streaming | JSON/REST between services (slower, looser) |
| Client streaming | WebSocket, JSON v1 | Browser-native, easy to debug | SSE (one-way only), binary protobuf (v2) |
| Relational | Postgres | ACID ledger, mature | MySQL |
| Time series | ClickHouse | Excellent for ticks and aggregations | TimescaleDB |
| Cache/state | Redis | Latest snapshots, rate limits | In-memory only |
| Raw log | Length-delimited Protobuf + zstd, compacted to Parquet | Lossless, replay-native, fast writes; Parquet for analytics | Parquet only (slow small appends) |
| Bus | Optional (Redpanda/NATS) | Not needed for v1; files are the source of truth | Kafka (heavy for a laptop) |
| Metrics | Prometheus + Grafana | Free, standard | - |
| Tracing | OpenTelemetry (sampled, off hot path) | Cross-service latency | - |

Pin exact versions in lockfiles at scaffold time. Use current stable releases; do not hard-code versions in docs.

## 3. Cross-cutting design rules

1. **Contracts first.** `proto/` is the source of truth. `buf lint` and `buf breaking` run in CI.
2. **Fixed-point everywhere** across boundaries (see `04-API-SPEC.md` section 1). Doubles only for derived analytics.
3. **Time:** UTC nanoseconds as int64. Every event has `exchange_ts`, `recv_ts`, and (when streamed) `publish_ts`. Hosts run NTP/chrony; clock offset is exposed as a metric.
4. **Backpressure policy:** bounded queues; on overflow, **conflate to latest state** for market data; **reject** for commands. Never unbounded buffers.
5. **Fail safe:** an invalid book is flagged, never served as valid. Consumers must check `book_valid` and `epoch`.
6. **Determinism:** the live path and replay path share one code path (`book`, `metrics`). No wall-clock or randomness inside them; time is an input.
7. **Observability by default:** each stage has a timer and a counter before it is considered done.
8. **Idempotency:** all state-changing API calls that can be retried accept an idempotency key.
9. **Least privilege & isolation:** every user-owned query is scoped by `user_id` in one layer.

## 4. Data flow and sequence numbers

- The feed assigns `epoch` (increments on any rebuild: reconnect, resync, config change) and `seq` (increments by 1 per message within an epoch, per symbol per stream type).
- Consumers: on `epoch` change expect a snapshot next; on `seq` gap (same epoch) request a fresh snapshot or resubscribe.
- Exchange-native sequence ids (e.g., Binance `U`/`u`) are internal to the feed's book synchronizer and are not part of the consumer contract.

## 5. Rust feed service

### 5.1 Workspace and crates
`sb-core`, `sb-book`, `sb-metrics`, `sb-connectors`, `sb-feed`, `sb-replay`, `sb-pybook` (directories: `core/`, `book/`, `metrics/`, `connectors/`, `server/`, `replay/`, `pybook/`). `sb-core`, `sb-book`, `sb-metrics` are pure (no I/O, no async, no allocation in hot functions) so they are usable from Python. See ADR-0012 for the `sb-` package name prefix.

### 5.2 Threading model
- One async (tokio) task per exchange connection: read frames, stamp `recv_ts`, push raw bytes into a bounded SPSC ring buffer.
- **Book workers:** N dedicated OS threads (optionally core-pinned), each owning a fixed set of symbols and their books/metrics. A worker parses, normalizes, applies updates, computes metrics, and pushes outputs to: (a) the gRPC broadcaster, (b) the writer thread. No locks on the hot path.
- **Broadcaster:** per-subscriber bounded queues with conflation (see 5.6).
- **Writer thread:** batches events to the raw log; failure or slowness never blocks workers (drop-oldest into the writer queue with a counter, or apply backpressure to an offline-recording mode only; live service prefers to drop log events and alarm over delaying the book. See ADR-0006 for the trade-off).
- Parsing happens on the worker thread (not in the async reader) so the reader is just a socket pump. Benchmark moving parse to the reader if worker threads are the bottleneck.

### 5.3 Connector trait (sketch)
```
trait Exchange {
    fn venue(&self) -> &'static str;
    fn instruments(&self) -> Vec<InstrumentSpec>;
    fn subscribe_urls(&self, symbols: &[Symbol]) -> Vec<StreamSpec>;
    fn parse(&self, frame: &[u8], out: &mut EventBuf) -> Result<(), ParseError>; // no alloc
    fn fetch_snapshot(&self, symbol: &Symbol) -> impl Future<Output = Result<RawSnapshot>>;
}
```
Binance specifics (stream names, update ids, snapshot endpoint, limits) are in `06-ORDER-BOOK-SPEC.md` and must be verified against current official docs.

### 5.4 Order book and metrics
Specified in `06-ORDER-BOOK-SPEC.md`: state machine (Disconnected, Buffering, Syncing, Live, Invalid), optimized dense-window book, oracle book, invariants, incremental metrics.

### 5.5 Event log and replay
- Raw log: length-delimited Protobuf `LogRecord { schema_version, venue, symbol, recv_ts_ns, exchange_ts_ns, payload_kind, payload }` compressed with zstd, rotated per symbol per hour. Raw venue frames may also be recorded for parser regression tests.
- Replay: `--replay <path|glob> [--speed 1x|max]` drives the same workers with recorded `recv_ts` as the clock input. Output must be identical across runs (NFR-04).
- Compactor (Python or Rust): hourly job converting closed log files to Parquet partitions (`05-DATA-MODEL.md`).

### 5.6 gRPC server
Per `proto/stockbroker/market/v1/market.proto`. Each subscriber has a bounded queue (default 1,024 messages per symbol). If full: drop queued deltas, enqueue a fresh snapshot with a new `epoch`, increment `conflation_total`. Deadlines and keepalive configured; reflection enabled in dev only.

### 5.7 Config (TOML)
`[exchange]`, `[[symbols]]` (symbol, tick, window_ticks, depth_levels), `[workers]` (count, pin_cores), `[queues]`, `[log]` (dir, rotation, compression), `[grpc]` (bind addr), `[http]` (health/metrics addr), `[snapshot_check]` (interval, depth). All values validated at startup.

### 5.8 Error handling
Typed errors (`thiserror`); no `unwrap` on external input; parser never panics (fuzzed). Reconnect uses exponential backoff with jitter and a max cap; every reconnect increments `epoch` and triggers resync. Panics in a worker are caught at the thread boundary, logged, metered, and the symbol's book is marked invalid and rebuilt.

## 6. Java API service

### 6.1 Structure
Modular monolith (single deployable), packages by capability: `auth`, `market`, `portfolio`, `trading`, `alerts`, `research`, `admin`, `common`. Layers per module: `api` (controllers) -> `service` -> `repo`. No cross-module repo access.

### 6.2 Market gateway
- gRPC client (channel with keepalive, retry with backoff) subscribed to the union of symbols currently needed; refcounted subscriptions.
- In-memory market state per symbol (latest book, metrics, recent trades ring); mirrored to Redis at a throttled rate for restart/shared reads.
- **Fan-out hub:** each WebSocket session has one writer (virtual thread or Netty event loop) draining a bounded per-session queue; conflation by symbol/channel; metrics for queue depth and drops.
- Java never computes market metrics; it forwards them and adds `gateway_publish_ts`.

### 6.3 Auth and security
Spring Security; Argon2id; JWT access token (about 15 min, signed with asymmetric key, `kid` header for rotation); opaque rotating refresh token stored as a hash, with reuse detection (reuse revokes the token family). Rate limits via Bucket4j + Redis. See `09-SECURITY.md`.

### 6.4 Persistence
Postgres with Flyway migrations. Use JDBC/jOOQ (or Spring Data JDBC) for ledger and trading code where SQL control matters; JPA is acceptable for simple CRUD. Money columns are `NUMERIC(38,18)`; Java uses `BigDecimal`. Isolation: `READ COMMITTED` with explicit row locks or `SERIALIZABLE` for balance-affecting transactions, plus the single-writer-per-account rule (`07-PAPER-TRADING-SPEC.md`).

### 6.5 Paper trading engine
Single-writer per account via a keyed executor (hash of `account_id` to a small pool of single-thread executors, or per-account mailbox). Commands: submit order, cancel order, on-market-update (for resting limits). Fill model and ledger entries are specified in `07-PAPER-TRADING-SPEC.md`; golden test vectors shared with Python.

### 6.6 Research integration
Java calls the Python job service (HTTP, service-to-service token), stores job metadata in `backtest_runs`, and serves results (artifacts stored on disk or object storage; metadata in Postgres).

### 6.7 API conventions
REST under `/api/v1`, JSON, `application/problem+json` errors (RFC 9457), cursor pagination, `Idempotency-Key` header on POST /orders, OpenAPI via springdoc, request validation with Bean Validation. Details: `04-API-SPEC.md`.

## 7. Python research service

- **Package:** `sb_research` with `data`, `engine`, `execution`, `strategies`, `reports`, `service`.
- **Engine:** event loop over recorded events via `pybook.EventReader` (batches of N events with book state per event, returned as Arrow/NumPy arrays). Strategy callback receives an immutable state view; orders go to the execution simulator.
- **Execution simulator:** latency queue, book-walking fills, fees, optional impact; identical rules to Java (shared golden vectors in `testdata/`).
- **No-lookahead guard:** the engine only exposes data with `recv_ts <= now`; tests include a strategy that tries to read future data and must raise.
- **Job service (FastAPI):** `POST /jobs`, `GET /jobs/{id}`, `GET /jobs/{id}/result`; jobs run in a process pool; each run writes `run.json` (git commit, wheel version, params, data range, seed) plus artifacts (Parquet trade log, equity curve, report JSON).
- **Data:** Polars/DuckDB over Parquet; ClickHouse client for aggregates.

## 8. Frontend

- **Next.js App Router**, TypeScript strict. Route groups `(marketing)`, `(app)`, `(auth)`.
- **Live app is client-only** (`"use client"`, `dynamic(..., { ssr: false })` for the live widgets).
- **WebSocket in a Web Worker**; messages batched and posted to the main thread at most once per animation frame; Zustand store with selector-based subscriptions to avoid rerenders.
- **Rendering:** order book and depth chart on `<canvas>`; candles/equity with lightweight-charts.
- **Auth:** access token in memory; refresh token in an `httpOnly`, `Secure`, `SameSite=Strict` cookie handled by Next.js route handlers/middleware; WebSocket obtains a single-use ticket via REST, then connects directly to Java.
- **Generated API client** from the Java OpenAPI spec; shared WS message types from a single TS module generated or hand-maintained from `04-API-SPEC.md`.
- **Budgets:** marketing routes under 150 KB JS (gzip) target; app shell lazy-loads charts and canvas code.

## 9. Storage summary

| Store | Data | Retention (default) |
|---|---|---|
| Event log files | Normalized events (source of truth) | 7 days local (configurable); selected days archived as test fixtures |
| Parquet | Compacted events for research | Keep what research needs; disk-bound |
| ClickHouse | Trades, 1 s metrics, candles | 30 days raw trades, 1 year candles (configurable) |
| Postgres | Users, tokens, ledger, orders, fills, watchlists, alerts, runs | Indefinite; backups nightly |
| Redis | Latest state, rate limits, WS tickets | Ephemeral (TTL) |

Schemas: `05-DATA-MODEL.md`.

## 10. Configuration and secrets

12-factor config via env vars (`SB_` prefix) and config files; `.env.example` committed, `.env` ignored. Secrets: JWT private key, DB passwords, service tokens. Local dev uses generated throwaway keys; production-like deployments use the host's secret store or Docker secrets.

## 11. Deployment topology

- **Local/dev:** `docker compose --profile core --profile obs up` plus services run natively for fast iteration.
- **Demo:** single VM (free tier, **VERIFY** current terms) running compose; frontend on a free static/serverless host; WebSocket and API on the VM behind a reverse proxy with TLS. See `12-DEVOPS-DEPLOYMENT.md`.

## 12. Capacity assumptions (to be validated)

- 5 symbols, Binance depth stream at 100 ms update speed plus trades: low thousands of messages per second in total.
- 1,000 simulated WebSocket clients for the fan-out load test.
- Event log volume: measure in Phase 2, then set retention. Do not trust estimates.

## 13. Technical risks and mitigations (summary)

| Risk | Mitigation |
|---|---|
| Book window edge cases | Oracle book + property tests + replay with injected faults |
| PyO3 boundary overhead | Coarse batches, Arrow/NumPy zero-copy |
| JVM GC/jitter in Java path | Allocation-light fan-out, G1/ZGC tuning, measure and publish Java-stage latency separately |
| Clock skew skews latency numbers | NTP/chrony; report receive-to-publish (single-clock) latency as the precise metric |
| Free-hosting limits | Local-first design; recorded demo fallback |

## 14. Open technical questions

1. Parser on the reader task or on the worker thread? (benchmark in Phase 1)
2. Per-account mailbox vs keyed executor pool in Java? (decide in Phase 5)
3. Compactor in Rust or Python? (leaning Python/DuckDB for simplicity)
4. gRPC vs Unix domain socket or shared memory between Rust and Java when co-located? (post-v1 experiment, only if Java stage dominates latency)
