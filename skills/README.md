# Skills index

These skills are written for AI coding agents (and humans) working on stock_broker. Each is a folder with a `SKILL.md` (frontmatter: `name`, `description`). Load the matching skill **before** working on that area. They are specific to this project's specs in `docs/`; if a skill and a spec disagree, the spec/ADR wins - then fix the skill.

| Skill | Use it for |
|---|---|
| `rust-hot-path-performance` | Allocation-free, low-latency Rust; benchmarking and profiling |
| `orderbook-engine-correctness` | Snapshot+diff sync, resync/epoch/seq, oracle, property tests |
| `market-microstructure-metrics` | Spread, mid, microprice, imbalance, depth, VWAP, slippage formulas |
| `rust-exchange-connectors` | WebSocket/REST lifecycle, reconnect, rate limits, normalization |
| `rust-pyo3-maturin` | Rust-to-Python bindings, wheels, parity testing |
| `protobuf-grpc-contracts` | Proto design, gRPC semantics, buf, codegen |
| `java-spring-backend` | Spring Boot API structure, REST conventions, persistence, security wiring |
| `realtime-websocket-fanout` | WebSocket gateway, bounded queues, conflation, load tests |
| `paper-trading-ledger` | Orders, fills, fees, double-entry ledger, idempotency, reconciliation |
| `python-backtesting-engine` | No-lookahead backtests, execution model, evaluation, reports, job API |
| `nextjs-realtime-frontend` | Next.js live dashboard, worker + rAF + canvas, auth flow, budgets |
| `security-hardening` | Auth, authorization/isolation, abuse controls, supply chain, release gate |
| `data-storage-design` | Postgres/ClickHouse/Redis/event log/Parquet design and retention |
| `testing-and-quality` | Property/golden/replay/fuzz/load tests, CI gates |
| `observability-latency-measurement` | Honest latency measurement, metrics, dashboards, benchmarks |
| `devops-docker-ci-free-hosting` | Compose profiles, Dockerfiles, CI, free-tier demo hosting |
| `portfolio-presentation` | README, charts, demo script, resume/interview material |

## Which skills for which task

| Task | Load |
|---|---|
| Implement a Binance connector | `rust-exchange-connectors`, `orderbook-engine-correctness`, `rust-hot-path-performance`, `testing-and-quality` |
| Build/optimize the order book | `orderbook-engine-correctness`, `rust-hot-path-performance`, `market-microstructure-metrics`, `testing-and-quality` |
| Add a gRPC message or RPC | `protobuf-grpc-contracts`, then the consuming language skill |
| WebSocket gateway work | `realtime-websocket-fanout`, `java-spring-backend`, `security-hardening`, `observability-latency-measurement` |
| Paper-trading feature | `paper-trading-ledger`, `java-spring-backend`, `data-storage-design`, `security-hardening`, `testing-and-quality` |
| Backtest feature | `python-backtesting-engine`, `rust-pyo3-maturin`, `market-microstructure-metrics`, `testing-and-quality` |
| UI work | `nextjs-realtime-frontend`, `security-hardening` (auth), `observability-latency-measurement` (overlay) |
| Auth / user-data change | `security-hardening`, `java-spring-backend`, `data-storage-design` |
| CI, Docker, deployment | `devops-docker-ci-free-hosting`, `security-hardening` |
| Performance claim or benchmark | `observability-latency-measurement`, `rust-hot-path-performance` |
| README/demo/resume | `portfolio-presentation`, `observability-latency-measurement` |

## Maintaining skills
- Keep each `SKILL.md` focused (rules, patterns, checklists); link to docs instead of duplicating long specs.
- Update a skill when a recurring review comment or bug shows a missing rule.
- Descriptions are written to trigger on relevant work: keep them specific and include likely phrasings.
