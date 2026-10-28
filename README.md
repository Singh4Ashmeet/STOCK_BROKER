# stock_broker

A stock and crypto **market data platform** built as a portfolio project. It is deliberately polyglot, and each language owns the part it is best at:

| Service | Language | Owns |
|---|---|---|
| Fast feed | **Rust** | Exchange connectors, order book, metrics, replay, latency timers |
| Product API | **Java** (Spring Boot) | Multi-user accounts, portfolios, paper trading, live fan-out to clients |
| Research | **Python** | Honest backtesting on the same Rust order book (via PyO3), strategy research |
| Frontend | **Next.js** (TypeScript) | Live dashboard, trading ticket, research pages, health page, marketing site |

**Status:** Phase 1-4 complete — Rust core, Java API, Python research, and Frontend integrated.
**Scope of v1:** crypto only (Binance), 3 to 5 symbols, **paper trading only**, free to run.

## The three goals (in priority order)

1. **Real-time latency** - measured per stage, published as p50 / p99 / p99.9.
2. **Order-book correctness** - the book never silently drifts; gaps trigger an automatic resync.
3. **Honest backtesting** - fees, slippage and latency modeled; any recorded day replays identically.

## Repository map

```
stock_broker/
  CLAUDE.md        Instructions for AI coding agents working in this repo
  docs/            PRD, TRD, architecture, specs, security, testing, ADRs  (start at docs/README.md)
  skills/          Best-practice skills for backend, frontend, security, etc. (see skills/README.md)
  proto/           Shared Protobuf contracts (single source of truth for all languages)
  rust/            Cargo workspace (Phase 1-2)
  java/            Spring Boot service (Phase 4)
  python/          Research + backtest + job service (Phase 6)
  frontend/        Next.js app (Phase 9)
  main.py          Native development launcher
  testdata/        Recorded feeds, golden vectors (small files only)
  benchmarks/      Benchmark results and chart sources
  scripts/         Dev helper scripts (bootstrap, verify)
```

## Features

### Core Infrastructure
- **Multi-language architecture**: Rust (feed), Java (API), Python (research), TypeScript/Next.js (frontend)
- **Protobuf contracts**: Single source of truth for all inter-service communication
- **Native launcher**: `main.py` starts all services with proper environment configuration
- **Service dashboard**: Local monitoring at http://127.0.0.1:8080

### Rust Feed Service
- **Order book**: Lock-free, allocation-free hot path with BTreeMap oracle for verification
- **Exchange connectors**: Binance WebSocket + REST with automatic reconnection and gap detection
- **Metrics engine**: Incremental BBO, depth, VWAP, trade flow, microprice, imbalance
- **Event log**: zstd-compressed protobuf with schema versioning, deterministic replay
- **Prometheus exporter**: Dedicated port 9100 (configurable via `SB_FEED_PROMETHEUS_PORT`), loopback-only binding
- **Health endpoint**: `/health` with per-symbol status, epoch, resync count, latency

### Java Product API (Spring Boot 3.3, Java 21)
- **Virtual threads** for high-concurrency request handling
- **JWT authentication**: RS256 access tokens (15min) + rotating refresh tokens (30d, HttpOnly cookies)
- **gRPC client**: Connects to Rust feed with keepalive, reconnection backoff, Redis mirror
- **WebSocket fan-out**: Per-client bounded queues with conflation, ticket-based auth
- **Paper trading**: Order management, portfolio, ledger with double-entry accounting
- **PostgreSQL + Flyway**: Schema migrations, jOOQ for type-safe queries
- **Redis**: Session cache, rate limiting (Bucket4j), market data mirror
- **OpenAPI/Swagger**: Auto-generated at `/api/swagger-ui.html`

### Python Research (PyO3 + FastAPI)
- **Shared order book**: Uses same Rust `sb-book` crate via PyO3 bindings (`pybook` package)
- **Event-driven backtest engine**: No-lookahead enforcement, realistic fill simulation
- **Golden vectors**: Deterministic replay with JSONL fixtures for regression testing
- **Walk-forward testing**: Rolling/anchored window parameter optimization
- **Parameter sensitivity**: Grid/random/Sobol sampling with correlation analysis
- **Deflated Sharpe ratio**: Probabilistic Sharpe Ratio (PSR) and Deflated Sharpe Ratio (DSR)
- **Data quality**: Parquet validation with schema, ordering, gap, and timestamp checks
- **Fixed path resolution**: Tests resolve `testdata/` from repository root regardless of CWD

### Frontend (Next.js 14, TypeScript, React 18)
- **App Router** with client-only live components
- **WebSocket worker**: Off-main-thread message processing, auto-reconnect with backoff
- **Live dashboard**: Order book depth, trades tape, candles, metrics panels
- **Trading ticket**: Order entry with preview, idempotency keys
- **Portfolio/P&L**: Real-time positions, fills, ledger
- **Research pages**: Backtest submission, walk-forward, sensitivity, deflated Sharpe

---

## Quickstart (native development)

```bash
# 1. Clone and bootstrap (generates .env, JWT keys)
git clone <repo> && cd stock_broker
./scripts/bootstrap.sh

# 2. Install range-constrained Python research API dependencies into .venv
#    (creates .venv automatically if it does not exist)
python main.py install

# 3. Start Rust, Python, Java, and Next.js together
#    PostgreSQL and Redis must already be installed and running locally.
python main.py start
# Local service dashboard: http://127.0.0.1:8080
# JSON status: http://127.0.0.1:8080/api/status
# Stop all services: press Ctrl+C in the same terminal.

# 4. Verify CI configuration (action pins)
make ci-lint

# 5. Check protobuf tooling
make proto

# 6. Run pre-commit hooks
pre-commit install --install-hooks -t pre-commit -t commit-msg
pre-commit run --all-files
```

### Environment variable precedence

Explicit shell variables override values loaded from `.env`. For example,
`SB_API_PORT=9090 python main.py start` uses port 9090 regardless of what `.env`
contains.

### Key environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SB_API_PORT` | 8081 | Java API HTTP port |
| `SB_FEED_GRPC_PORT` | 50151 | Rust feed gRPC port |
| `SB_FEED_HTTP_PORT` | 9090 | Rust feed HTTP health/metrics port |
| `SB_FEED_PROMETHEUS_PORT` | 9100 | Prometheus exporter port |
| `SB_RESEARCH_PORT` | 8000 | Python research API port |
| `SB_API_PORT` | 8081 | Java API port |
| `SB_WEB_PORT` | 3000 | Next.js frontend port |
| `SB_POSTGRES_PORT` | 5432 | PostgreSQL port |
| `SB_REDIS_PORT` | 6379 | Redis port |
| `SB_JWT_PRIVATE_KEY_PATH` | file:./infra/secrets/jwt_private.key | JWT signing key |
| `SB_JWT_PUBLIC_KEY_PATH` | file:./infra/secrets/jwt_public.key | JWT verification key |

---

## Key Commands

```bash
# Development
make start          # Start all services (Rust, Python, Java, Next.js)
make status         # Check service status via supervisor
make stop           # Stop (Ctrl+C in terminal running 'make start')

# Testing
make rust-test      # cargo test --workspace
make python-test    # pytest python/backtest/tests
make python-supervisor-test  # pytest tests/test_supervisor.py
make java-test      # ./mvnw test (requires PostgreSQL + Redis)
make web-lint       # npm run lint (frontend)
make web-build      # npm run build (frontend)

# Code quality
make proto          # buf lint + format-check + build + compile-check
make ci-lint        # bash scripts/check-action-pins.sh

# Protobuf
make proto-lint     # buf lint proto
make proto-format   # buf format -w proto
make proto-build    # buf build proto
```

---

## Architecture Highlights

- **Single order book implementation**: Rust `sb-book` crate used by Java (via gRPC) and Python (via PyO3)
- **Fixed-point arithmetic**: No floats for money/quantities across language boundaries
- **Honest backtesting**: Same order book, fees, slippage, latency model in research and production
- **Deterministic replay**: Event logs with schema versioning, exact byte-for-byte replay
- **No Docker required**: Native development with PostgreSQL + Redis locally

---

## Documentation

- `docs/01-PRD.md` — Product requirements
- `docs/02-TRD.md` — Technical requirements
- `docs/03-ARCHITECTURE.md` — Diagrams, data flows, failure modes
- `docs/04-API-SPEC.md` — REST, WebSocket, gRPC, Protobuf contracts
- `docs/06-ORDER-BOOK-SPEC.md` — Order book algorithm, invariants, edge cases
- `docs/07-PAPER-TRADING-SPEC.md` — Order lifecycle, ledger, reconciliation
- `docs/08-BACKTESTING-METHODOLOGY.md` — Walk-forward, sensitivity, deflated Sharpe
- `docs/09-SECURITY.md` — Threat model, JWT, rate limiting, secrets
- `docs/10-TESTING-STRATEGY.md` — Test levels, tools, CI gates
- `docs/13-DEVELOPMENT-GUIDE.md` — Setup, conventions, workflow
- `docs/adr/README.md` — Architecture Decision Records

---

## Important disclaimer

This is an educational/portfolio project. It uses **simulated (paper) trading only**. It does not handle real money, place real orders, or provide investment advice.