# Product Requirements Document (PRD)

**Product:** stock_broker - Market Data Platform
**Version:** 0.1 (planning) | **Type:** Personal portfolio project | **Cost constraint:** free to build and run

## 1. Summary

A multi-user, real-time market data platform for crypto (stocks later). It ingests live exchange data, maintains correct order books, computes market metrics at low latency, lets users track portfolios and **paper trade** against the real live order book, and lets the author run honest backtests on the same code that runs live. It is built as a polyglot system (Rust, Java, Python, Next.js) to demonstrate the split between low latency, research, and product engineering.

## 2. Problem and opportunity

- Free market-data tools typically hide how fresh their data is, ignore transaction costs in backtests, or provide no proof their order books are correct.
- Paper-trading tools often fill at mid price, which overstates results.
- As a portfolio project, the goal is to show deep engineering in three specific, measurable areas rather than breadth.

## 3. Goals

| ID | Goal | Measure of success |
|---|---|---|
| G1 | **Real-time latency** (top priority) | Per-stage and end-to-end latency published as p50/p99/p99.9; live latency overlay in UI |
| G2 | **Order-book correctness** | Zero mismatches vs exchange snapshots in a 24 h soak; automatic resync on gaps |
| G3 | **Honest backtesting** | Fees, slippage, latency modeled; results shown gross vs net; identical replay of any recorded day |
| G4 | **Production-quality product layer** | Multi-user auth, isolation tests, load-tested fan-out |
| G5 | **Portfolio presentation** | README with charts, architecture write-up, demo video, public repo |

## 4. Non-goals (v1)

- Real-money trading, brokerage integration, or investment advice (regulatory risk; see `09-SECURITY.md` section 11).
- Stocks and Indian markets (post-v1; data licensing and market-hours complexity).
- Co-location, kernel-bypass networking, FPGA, or nanosecond-level claims.
- Mobile apps, social features, payments.
- Competing with commercial terminals on breadth of data.

## 5. Users and personas

| Persona | Description | Needs |
|---|---|---|
| **P1 Reviewer** | Recruiter or engineer viewing the portfolio | Understand the project in 2 minutes: live demo or video, clear README, architecture diagram, published numbers |
| **P2 Paper trader** | Registered user practicing trades | Live book, realistic fills, portfolio and P&L, alerts |
| **P3 Researcher** (the author) | Runs strategies | Replay any day, backtest with realistic costs, reproducible reports |
| **P4 Admin** (the author) | Operates the system | Health page, latency histograms, resync counts, user management basics |

## 6. User stories and functional requirements

Priority uses MoSCoW: **M** must, **S** should, **C** could, **W** won't (v1).

### 6.1 Market data (Rust)

| ID | Requirement | Pri |
|---|---|---|
| FR-MD-01 | Connect to Binance public WebSocket and REST; reconnect automatically with backoff | M |
| FR-MD-02 | Normalize venue messages into internal Trade, BookDelta, BookSnapshot with exchange and receive timestamps | M |
| FR-MD-03 | Maintain a correct local order book per symbol using snapshot plus diff synchronization; detect sequence gaps and resync automatically | M |
| FR-MD-04 | Compute metrics: best bid/ask, spread, mid, microprice, depth at N levels, imbalance, rolling VWAP, trade-flow imbalance | M |
| FR-MD-05 | Stream books, trades and metrics to Java over gRPC with snapshot-then-delta semantics and sequence numbers | M |
| FR-MD-06 | Persist all normalized events to a raw event log for replay | M |
| FR-MD-07 | Replay mode: re-run a recorded log through the same code path at original or accelerated speed, deterministically | M |
| FR-MD-08 | Slow consumers are conflated to a snapshot; they never slow the feed | M |
| FR-MD-09 | Periodically cross-check the local book against the exchange REST snapshot and report mismatches | S |
| FR-MD-10 | Additional exchange connectors (Coinbase, Kraken) via the connector trait | C |
| FR-MD-11 | Equities connector (Alpaca/broker API) | W |

### 6.2 Accounts and security (Java)

| ID | Requirement | Pri |
|---|---|---|
| FR-AU-01 | Register and log in with email and password (Argon2id hashing) | M |
| FR-AU-02 | Short-lived access tokens plus rotating refresh tokens with reuse detection and revocation | M |
| FR-AU-03 | Strict per-user data isolation, enforced in one layer and covered by tests | M |
| FR-AU-04 | Per-user and per-IP rate limiting; login throttling | M |
| FR-AU-05 | Roles USER and ADMIN | M |
| FR-AU-06 | Email verification and password reset | S |
| FR-AU-07 | OAuth login (Google) | C |

### 6.3 Live data to clients (Java)

| ID | Requirement | Pri |
|---|---|---|
| FR-LV-01 | WebSocket fan-out of books, trades, metrics to authenticated clients | M |
| FR-LV-02 | One upstream subscription per symbol, shared by all clients | M |
| FR-LV-03 | Per-client bounded queues with conflation; one slow client never affects others | M |
| FR-LV-04 | Gap detection on the client stream and automatic resnapshot | M |
| FR-LV-05 | Per-user connection and subscription caps | M |

### 6.4 Portfolio and paper trading (Java)

| ID | Requirement | Pri |
|---|---|---|
| FR-PT-01 | Each user has a paper account with a starting cash balance | M |
| FR-PT-02 | Market and limit orders; market orders walk the live book level by level with partial fills | M |
| FR-PT-03 | Configurable maker/taker fees; same fee model as the backtester | M |
| FR-PT-04 | Append-only double-entry ledger; balances derived from it; periodic reconciliation | M |
| FR-PT-05 | Idempotency keys on order submission; one writer per account to prevent overspend | M |
| FR-PT-06 | Positions, realized and unrealized P&L, transaction history | M |
| FR-PT-07 | Order cancel; open-order and fill views | M |
| FR-PT-08 | Watchlists | S |
| FR-PT-09 | Alerts on price, spread and imbalance thresholds | S |
| FR-PT-10 | IOC/FOK time-in-force, stop orders | C |

### 6.5 Research (Python)

| ID | Requirement | Pri |
|---|---|---|
| FR-RS-01 | Load recorded events, validate (gaps, duplicates, ordering) and refuse unsafe ranges | M |
| FR-RS-02 | Event-driven backtest engine on the Rust book via PyO3; strategies implement `on_event(state) -> orders` | M |
| FR-RS-03 | Execution model: book-walking fills, latency, fees, slippage; documented queue-position assumption | M |
| FR-RS-04 | Engine-enforced no-lookahead; tests that attempt to cheat must fail | M |
| FR-RS-05 | Baselines (buy and hold, random) and at least one real strategy (order-book imbalance) | M |
| FR-RS-06 | Reports: return, vol, Sharpe, Sortino, max drawdown, hit rate, turnover; gross vs net cost breakdown | M |
| FR-RS-07 | Walk-forward evaluation and parameter sensitivity | S |
| FR-RS-08 | Job API (submit, poll, fetch) used by Java; every run is reproducible | M |
| FR-RS-09 | ML signal with time-based splits | C |

### 6.6 Frontend (Next.js)

| ID | Requirement | Pri |
|---|---|---|
| FR-UI-01 | Live market page: order book, depth chart, trades tape, candles, metrics panel | M |
| FR-UI-02 | Latency overlay showing live exchange-to-screen latency | M |
| FR-UI-03 | Trade ticket with slippage preview that walks the live book | M |
| FR-UI-04 | Portfolio, orders, fills, ledger pages | M |
| FR-UI-05 | Research page: launch and compare backtests with cost breakdown | M |
| FR-UI-06 | System health page: per-stage latency histograms, resync counts, connection state | M |
| FR-UI-07 | Marketing site: landing, architecture, benchmark charts | S |
| FR-UI-08 | Watchlist and alerts pages | S |
| FR-UI-09 | Light/dark theme, keyboard accessibility | C |

## 7. Non-functional requirements

Targets are goals to validate, not guarantees. Measurement methods are in `11-PERFORMANCE-AND-OBSERVABILITY.md`.

| ID | Category | Requirement (target) |
|---|---|---|
| NFR-01 | Latency (internal) | Rust receive-to-publish p99 under 1 ms; parse under 100 us p99; book+metrics update under 50 us p99 |
| NFR-02 | Latency (Java) | Gateway receive-to-WebSocket-send p99 under 5 ms with 1,000 simulated clients |
| NFR-03 | Correctness | 0 book mismatches vs exchange REST snapshots over 24 h; every induced gap triggers resync within 2 s |
| NFR-04 | Determinism | Replaying the same log yields byte-identical book states and metrics across runs |
| NFR-05 | Throughput | Sustain at least 10x the observed live message rate per core in replay |
| NFR-06 | Availability | Auto-reconnect; no manual restart after network drops; graceful degradation (book marked invalid, never wrong) |
| NFR-07 | Resource | No memory growth over a 24 h soak; fits on a 4 vCPU / 8 GB machine for 5 symbols |
| NFR-08 | Security | OWASP ASVS L1 baseline for the API; no critical/high dependency vulnerabilities at release |
| NFR-09 | Isolation | Automated tests prove no cross-user read/write on every user-owned resource |
| NFR-10 | Frontend | 60 fps during full-rate book stream; main-thread blocking per update under 4 ms (target) |
| NFR-11 | Cost | Zero paid services required to run locally; demo hosting on free tiers |
| NFR-12 | Reproducibility | Every benchmark/backtest stores commit, config, data range, hardware |

## 8. Success metrics (portfolio "scorecard")

Published in the README and on the marketing site, each with a chart and method:

1. Per-stage and end-to-end latency (p50/p99/p99.9).
2. Order-book update time: oracle vs optimized (ns/update).
3. Book correctness: mismatches vs exchange over 24 h, resync count.
4. Parity: live vs replay vs Python backtest identical.
5. Fan-out: added latency at 1,000 simulated clients.
6. Backtest honesty: gross vs net-of-costs, walk-forward vs baselines.

## 9. Scope and release plan

| Release | Contents | Roadmap phases |
|---|---|---|
| **R1 Core feed** | Rust feed, replay, benchmarks, published latency and correctness numbers | 0 to 3 |
| **R2 Product** | Java auth, fan-out, portfolios, paper trading | 4 to 5 |
| **R3 Research** | Python engine, backtests, results API | 6 to 7 |
| **R4 Polish** | Alerts, load/soak tests, frontend, marketing site, demo | 8 to 9 |

Each release must be demonstrable on its own (a README section and a short recording).

## 10. Assumptions

- Binance public market-data endpoints are reachable from the author's location (**VERIFY**: availability can be restricted by region; alternatives are Coinbase, Kraken, or Binance's market-data-only endpoints).
- A developer laptop (8 cores, 16 GB) is sufficient for development and benchmarking.
- Public feeds continue to be free and keyless.

## 11. Constraints

- Free to build and run; no paid APIs.
- No fixed deadline; quality over speed.
- Single developer; AI coding agents assist, guided by `CLAUDE.md` and `skills/`.
- Paper trading only.

## 12. Dependencies

Binance public API; Docker; Rust, JDK 21, Python 3.12, Node LTS toolchains; open-source libraries listed in the TRD.

## 13. Risks (summary)

Scope creep, exchange terms and regional restrictions, order-book edge cases, misleading latency claims, PyO3 build complexity. Full list: `15-RISK-REGISTER.md`.

## 14. Acceptance criteria for v1

- All **M** requirements implemented and tested.
- All NFR targets either met with published measurements, or documented with the measured value and an explanation.
- A reviewer can clone the repo, run one command to start the stack with docker-compose, and see live data in the UI.
- README includes the scorecard charts, architecture diagram, and a link to a demo video.

## 15. Open questions

1. Which free host (if any) will serve the public demo, or is a recorded demo sufficient?
2. Include a second exchange in v1 to prove the connector abstraction, or defer?
3. Starting paper balance and fee defaults (proposal: 100,000 USDT; 10 bps maker/taker, configurable).
