# stock_broker

A stock and crypto **market data platform** built as a portfolio project. It is deliberately polyglot, and each language owns the part it is best at:

| Service | Language | Owns |
|---|---|---|
| Fast feed | **Rust** | Exchange connectors, order book, metrics, replay, latency timers |
| Product API | **Java** (Spring Boot) | Multi-user accounts, portfolios, paper trading, live fan-out to clients |
| Research | **Python** | Honest backtesting on the same Rust order book (via PyO3), strategy research |
| Frontend | **Next.js** (TypeScript) | Live dashboard, trading ticket, research pages, health page, marketing site |

**Status:** Phase 1 (Rust core) in progress — core types and order book complete, connector next.
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
  rust/            Cargo workspace (Phase 1)
  java/            Spring Boot service (Phase 4)
  python/          Research + backtest + job service (Phase 6)
  frontend/        Next.js app (Phase 9)
  main.py          Native development launcher
  testdata/        Recorded feeds, golden vectors (small files only)
  benchmarks/      Benchmark results and chart sources
  scripts/         Dev helper scripts (bootstrap, verify)
```

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

## Where to start

## Important disclaimer

This is an educational/portfolio project. It uses **simulated (paper) trading only**. It does not handle real money, place real orders, or provide investment advice.
