# Roadmap and Milestones

No fixed deadline. Effort is given in rough T-shirt sizes (S about 1 to 3 days, M about 1 to 2 weeks, L about 2 to 4 weeks, at part-time student pace; treat as relative, not promises). Do **not** start a phase until the previous phase's exit criteria are met.

## Phase 0 - Foundations (S) ✅
- [x] Monorepo scaffold with `proto/` (buf, lint exceptions documented in ADR-0011)
- [x] CI skeletons (proto.yml, rust.yml, java.yml, python.yml, web.yml, security.yml, dependabot.yml)
- [x] docker-compose.yml with profiles (core, analytics, obs, bus, app) at repo root
- [x] `.env.example` documenting all SB_* variables
- [x] `./scripts/bootstrap.sh` (idempotent, generates .env, JWT Ed25519 keypair, ClickHouse SHA256 passwords)
- [x] Makefile with targets: bootstrap, up/up-core/up-bus/down/ps/logs/reset-data, proto-*, lint, test
- [x] Pre-commit config (trailing-whitespace, end-of-file-fixer, check-yaml, gitleaks, shellcheck, commitizen)
- [x] EditorConfig, .gitattributes (LF enforcement), SECURITY.md, .trivyignore
- [x] ADR-0011 (proto codegen strategy) created
- [x] Grafana provisioning (datasources + dashboards: Latency, Feed health, Fan-out, Trading)
- [x] All acceptances met: `docker compose --profile core --profile obs up` works; `buf lint` passes; CI workflows defined and run green on empty projects

**Exit criteria met.**

## Phase 0.2 - Protobuf tooling & CI (S) ✅
- [x] `proto/stockbroker/market/v1/market.proto` (package `stockbroker.market.v1`)
- [x] `buf lint` passes with documented exceptions (RPC_REQUEST_RESPONSE_UNIQUE, RPC_REQUEST_STANDARD_NAME, RPC_RESPONSE_STANDARD_NAME, SERVICE_SUFFIX, RPC_NO_*_STREAMING)
- [x] `buf format -d --exit-code` passes
- [x] `buf build` passes
- [x] `buf breaking` against main (skips gracefully if no baseline)
- [x] Python compile check with `grpcio-tools` passes
- [x] Makefile targets: `proto`, `proto-lint`, `proto-format`, `proto-format-check`, `proto-build`, `proto-breaking`, `proto-compile-check`
- [x] CI workflows: `proto.yml`, `rust.yml`, `java.yml`, `python.yml`, `web.yml`, `security.yml`, `dependabot.yml`
- [x] PR template with DoD + security/proto checkboxes
- [x] Pre-commit hooks, EditorConfig, .gitattributes, SECURITY.md, .trivyignore

**Exit criteria met.**

## Phase 1 - Rust core (L)
1. `core` types, fixed-point, timestamps, instrument metadata.
2. Binance connector + normalizer (prints events); recorded-feed capture tool.
3. Oracle book, optimized book, property tests, sequence/fault tests.
4. Metrics engine; criterion benchmarks; allocation tests; parser fuzz target.
- **Exit:** property tests pass at 100k+ cases; book equals oracle; benchmarks published (parse, apply, metrics); zero hot-path allocations verified.

## Phase 2 - Rust service (M to L)
5. Event log writer and replay driver; compactor (basic).
6. gRPC server with conflation, epoch/seq; health and Prometheus endpoints.
7. Latency instrumentation; live snapshot cross-check; 24 h soak.
- **Exit:** live and replay produce identical output; soak shows 0 mismatches and no memory growth; first latency numbers published (with methodology).
- **Release R1 (Core feed)** - README section + short recording.

## Phase 3 - PyO3 bridge (M)
- `pybook` wheel via maturin; coarse-grained `EventReader` and `OrderBook` API; Arrow/NumPy interchange; CI wheel build.
- **Exit:** parity test (Rust server vs binding) passes on recorded days.

## Phase 4 - Java foundation (L)
1. Spring Boot skeleton, Flyway, Testcontainers, shared proto codegen.
2. Auth: register, login, refresh rotation with reuse detection, rate limits; isolation test harness.
3. gRPC client + in-memory market state + Redis mirror.
4. WebSocket fan-out: tickets, snapshot-then-delta, bounded queues, conflation, caps.
- **Exit:** a logged-in test client receives a live book with correct `epoch/seq` handling; slow-client test passes; auth/security tests pass.

## Phase 5 - Paper trading (L)
5. Accounts, ledger, reconciliation job.
6. Order lifecycle, fill model against live book, fees, idempotency, single-writer, resting limit logic, preview endpoint.
7. Portfolio/P&L, watchlists.
- **Exit:** golden fill vectors pass; concurrency tests show no overspend; reconciliation clean after property tests.
- **Release R2 (Product)**.

## Phase 6 - Python research (L)
1. Data loader and validator; data quality report.
2. Engine skeleton on `pybook`; baselines; lookahead guard tests; golden scenarios.
3. Execution model (latency, fees, resting rules) sharing golden vectors with Java.
4. Imbalance strategy; metrics and reports (cost waterfall).
- **Exit:** parity test (backtest vs Java paper engine on a recorded day) passes; reports reproducible.

## Phase 7 - Research integration (M)
5. Walk-forward, sensitivity, deflated Sharpe.
6. FastAPI job service; Java `/backtests` endpoints; artifact storage.
- **Exit:** backtest from API to result view works end to end and is reproducible from `run.json`.
- **Release R3 (Research)**.

## Phase 8 - Hardening (M)
- Alerts feature, rate limits tuning, load test (1,000 clients), 24 h soak on the full stack, security checklist, backup/restore test, runbooks, dashboards finalized.
- **Exit:** all NFR targets measured and recorded; security release gate checklist complete.

## Phase 9 - Frontend (L)
1. App skeleton, auth flow, generated API client.
2. WS worker, store, resync handling; canvas order book, depth, tape.
3. Candles, metrics panel, latency overlay; trade ticket with slippage preview; portfolio pages.
4. Research pages (cost waterfall), system health page, watchlist/alerts.
5. Marketing site (landing, architecture, benchmarks), Playwright E2E, performance pass, demo video.
- **Exit:** 60 fps under full-rate replay; E2E green; README scorecard complete.
- **Release R4 (Polish)**.

> Note: UI-heavy work can start earlier against replay data if motivation requires it, but keep the order of *completion* so latency and correctness work is not starved.

## Post-v1 backlog (ideas)
- Second and third exchange connectors; cross-venue best-bid-offer view.
- Equities via broker API (with licensing review); Indian market data.
- Binary Protobuf over WebSocket (v2) and a latency comparison.
- Shared-memory or UDS transport between Rust and Java; measure the gain.
- ML signals with proper evaluation; feature store from Parquet.
- Order modification, stop orders, shorting, margin simulation.
- Public read-only API keys and rate-limited API for third parties.
- Kubernetes manifests (only as a learning exercise, not needed for the demo).

## Tracking
Use GitHub Projects (free) with columns per phase. Each milestone is a GitHub milestone with exit criteria copied from this file. Record measured results into `benchmarks/` at each exit.
