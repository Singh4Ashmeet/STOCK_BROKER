# stock_broker

A stock and crypto **market data platform** built as a portfolio project. It is deliberately polyglot, and each language owns the part it is best at:

| Service | Language | Owns |
|---|---|---|
| Fast feed | **Rust** | Exchange connectors, order book, metrics, replay, latency timers |
| Product API | **Java** (Spring Boot) | Multi-user accounts, portfolios, paper trading, live fan-out to clients |
| Research | **Python** | Honest backtesting on the same Rust order book (via PyO3), strategy research |
| Frontend | **Next.js** (TypeScript) | Live dashboard, trading ticket, research pages, health page, marketing site |

**Status:** planning complete, implementation not started.
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
  infra/           Prometheus/Grafana config, DB init scripts
  docker-compose.yml  Root compose file with profiles (core, analytics, obs, bus, app)
  testdata/        Recorded feeds, golden vectors (small files only)
  benchmarks/      Benchmark results and chart sources
  scripts/         Dev helper scripts (bootstrap, verify)
```

## Where to start

1. Read `docs/01-PRD.md` and `docs/02-TRD.md`.
2. Read `docs/14-ROADMAP.md` and begin at **Phase 0**.
3. When building a part, load the matching skill from `skills/` first.

## Important disclaimer

This is an educational/portfolio project. It uses **simulated (paper) trading only**. It does not handle real money, place real orders, or provide investment advice.
