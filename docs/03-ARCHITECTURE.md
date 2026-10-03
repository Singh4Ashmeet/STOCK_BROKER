# Architecture

## 1. Context diagram

```
                +--------------------+
  Browser  <--->|  Next.js (web)     |
  (user)        +---------+----------+
                          | REST (HTTPS)      WebSocket (direct)
                          v                         |
                +--------------------+              |
                |  Java API (api)    |<-------------+
                |  auth, portfolio,  |
                |  paper trading,    |------> Postgres (users, ledger, orders)
                |  WS fan-out        |------> Redis (latest state, limits, tickets)
                +----+----------+----+
                     |          |  HTTP (jobs)
        gRPC stream  |          v
                     |   +--------------------+
                     |   | Python (research)  |<--- Parquet / ClickHouse
                     |   | PyO3 Rust book     |
                     |   +--------------------+
                     v
                +--------------------+        +--------------------+
  Binance  ---->|  Rust feed         |------->| Event log (zstd)   |--> compactor --> Parquet
  WS + REST     |  book + metrics    |        +--------------------+
                +--------------------+------> Prometheus / Grafana
```

## 2. Containers and responsibilities

| Container | Owns | Never does |
|---|---|---|
| Rust feed | Venue connectivity, order books, metrics, event log, replay, gRPC server | Users, money, persistence beyond the log |
| Java API | Identity, accounts, portfolios, orders, fills, alerts, fan-out | Recompute market metrics, talk to exchanges |
| Python research | Backtests, reports, job runs | Serve end users directly; maintain its own order book |
| Next.js web | UI, auth cookie handling, marketing pages | Proxy the live WebSocket stream |

## 3. Primary flows

### 3.1 Live market data
1. Rust connector receives a venue frame, stamps `recv_ts`.
2. Worker parses, normalizes, applies to the book, updates metrics.
3. Worker publishes `BookUpdate`/`Trade`/`Metrics` (with `publish_ts`) to the gRPC broadcaster and to the writer thread.
4. Java gateway receives, updates in-memory state, stamps gateway time, fans out to subscribed sessions through bounded per-session queues.
5. Browser worker parses the frame, batches it, and the main thread draws once per animation frame; latency overlay computes `now - exchange_ts` and per-stage deltas.

### 3.2 Client subscribe
1. Client calls `POST /api/v1/ws/ticket` (authenticated) and gets a single-use, 30 s ticket.
2. Client opens `wss://.../ws/v1?ticket=...`; server validates and consumes the ticket.
3. Client sends `subscribe`; server replies with a snapshot (current state) then deltas with `epoch/seq`.
4. On gap or `epoch` change, client discards local state and waits for/requests a snapshot.

### 3.3 Paper order
1. `POST /api/v1/orders` with `Idempotency-Key`.
2. API validates, routes the command to the account's single writer.
3. Writer checks balance/limits, reads the latest valid book, walks levels, produces fills and ledger entries in **one DB transaction**.
4. Resting limit orders are re-evaluated on each market update for that symbol (per-symbol index of open orders).
5. Order and portfolio events are pushed to the user's private WebSocket channels.

### 3.4 Backtest
1. `POST /api/v1/backtests` (strategy, symbol, range, params).
2. Java stores a `backtest_runs` row (status QUEUED) and calls the Python job API.
3. Python validates data, runs the engine over events using the PyO3 Rust book, writes artifacts and `run.json`.
4. Java polls/receives completion, updates the row, serves results; UI renders curves and the cost breakdown.

### 3.5 Replay
`feed --replay <files>`: same workers, clock driven by recorded `recv_ts`; outputs go to a file/null sink or to a local gRPC server for demos. Used for tests, benchmarks, demos, and parity checks.

## 4. State machines

### 4.1 Per-symbol book (Rust)
`Disconnected -> Buffering -> Syncing -> Live`, and `Live -> Invalid -> Buffering` on gap/error/reconnect. Only `Live` books are served as valid. Detail: `06-ORDER-BOOK-SPEC.md`.

### 4.2 Order (Java)
`NEW -> ACCEPTED -> (PARTIALLY_FILLED) -> FILLED`, with `ACCEPTED/PARTIALLY_FILLED -> CANCELED` and `NEW -> REJECTED`. Terminal states are immutable. Detail: `07-PAPER-TRADING-SPEC.md`.

### 4.3 WebSocket session
`CONNECTING -> AUTHENTICATED -> SUBSCRIBED -> (LAGGING -> RESYNCING) -> CLOSED`.

## 5. Failure modes and behavior

| Failure | Detection | Behavior |
|---|---|---|
| Exchange socket drops | Read error, missed heartbeats | Reconnect with backoff+jitter; epoch++; books Invalid until resynced |
| Sequence gap in venue diffs | Update id check | Mark Invalid, resnapshot, epoch++ |
| Snapshot fetch fails/rate-limited | HTTP error | Retry with backoff; stay Invalid; alarm after threshold |
| Event log writer slow/disk full | Queue depth, IO error | Never block workers; drop log records with counter and alarm; operator action |
| gRPC consumer slow | Queue full | Conflate to snapshot with new epoch |
| Java loses gRPC link | Channel state | Reconnect; mark market state stale; clients see `stale` flag and resync on recovery |
| Client slow | Per-session queue full | Conflate; if persistently lagging, close with `lagging` reason |
| Postgres unavailable | Health check | Reject new orders (503); market data continues |
| Redis unavailable | Health check | Rate limiting falls back to local in-memory buckets; WS tickets fall back to local store (single instance only); log warning |
| Python job crashes | Job status | Mark FAILED with error; no partial results served |
| Clock jump | Offset metric | Alarm; latency numbers flagged unreliable |

## 6. Scaling notes (not needed for v1, documented for the write-up)

- Rust: shard symbols across worker threads; one process per exchange region if needed.
- Java: stateless except the in-memory market cache and per-account writers; scale out by account hash if ever required.
- Fan-out: shared subscriptions mean cost grows with users rather than symbols.

## 7. Deployment views

- **Dev:** services run natively; infra in compose.
- **Demo VM:** compose with `core`, `analytics`, `obs` profiles; Caddy or Nginx for TLS; feed and API on the same host (loopback gRPC) to keep latency numbers meaningful.
- **CI:** build and test each language; spin up compose for integration and replay tests.

## 8. Cross-cutting concerns map

| Concern | Where documented |
|---|---|
| Contracts | `04-API-SPEC.md`, `proto/` |
| Data | `05-DATA-MODEL.md` |
| Security | `09-SECURITY.md` |
| Testing | `10-TESTING-STRATEGY.md` |
| Latency and metrics | `11-PERFORMANCE-AND-OBSERVABILITY.md` |
| Ops | `12-DEVOPS-DEPLOYMENT.md` |
