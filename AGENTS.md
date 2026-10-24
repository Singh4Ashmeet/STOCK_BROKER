# AGENTS.md — stock_broker

Compact instructions for AI agents working in this repo. Read this first, every session.

---

## Project Overview

**Polyglot market data platform** (paper trading only, crypto only for v1):

| Service | Language | Owns |
|---------|----------|------|
| Fast feed | Rust | Exchange connectors, order book, metrics, replay, latency timers |
| Product API | Java (Spring Boot) | Multi-user accounts, portfolios, paper trading, live fan-out |
| Research | Python | Honest backtesting on same Rust order book (via PyO3) |
| Frontend | Next.js (TypeScript) | Live dashboard, trading ticket, research pages |

**Three goals (priority order):** (1) Real-time latency, (2) Order-book correctness, (3) Honest backtesting. Never trade correctness for a benchmark number.

---

## Non-Negotiable Rules

- **Paper trading only** — never add code that places real orders or touches real funds/credentials.
- **No floats for money/quantities across boundaries** — fixed-point integers (scaled by instrument metadata) in Rust/Proto; `BigDecimal`/`NUMERIC` in Java/Postgres; decimal strings in REST. Floats only for derived analytics (ratios, microprice).
- **Rust computes market metrics; Java never recomputes them.**
- **One order-book implementation** (Rust `book` crate). Python uses it via PyO3 (`pybook` crate). Do not re-implement in Python or Java.
- **Hot path rules (Rust):** no heap allocation, no locks, no blocking I/O, no logging at info level per message.
- **Every user-owned row filtered by `user_id`** in one repository layer, with an isolation test.
- **No secrets in repo** — use env vars and `.env.example`. Never log tokens, passwords, or refresh tokens.
- **Reproducibility** — every benchmark/backtest records code version, config, data range, hardware.
- **Honest numbers** — report where latency was measured (loopback/laptop/VM). Never claim "better than X" without a measured comparison.

---

## Quickstart (Native Development)

```bash
# 1. Clone and bootstrap (generates .env, JWT keys)
git clone <repo> && cd stock_broker
./scripts/bootstrap.sh

# 2. Install project dependencies
python main.py install

# 3. Start the native API and web UI (PostgreSQL + Redis must be running locally)
python main.py start --with-feed

# 4. Verify CI configuration
make ci-lint

# 5. Check protobuf tooling
make proto

# 6. Run pre-commit hooks
pre-commit install --install-hooks -t pre-commit -t commit-msg
pre-commit run --all-files

# 7. Stop when done
python main.py stop
```

**Main launcher commands:**
- `python main.py install` — install frontend, Java, Rust, Python deps
- `python main.py start` — start Java API (port 8081) + Next.js web (port 3000)
- `python main.py start --with-feed` — also start Rust feed gRPC server (port 50051)
- `python main.py status` — show managed process status
- `python main.py stop` — stop managed processes

---

## Key Make Targets

```bash
make install          # python main.py install
make start            # python main.py start
make start-feed       # python main.py start --with-feed
make stop             # python main.py stop
make status           # python main.py status

make proto            # lint + format-check + build + compile-check
make proto-lint       # buf lint proto
make proto-format     # buf format -w proto
make proto-format-check # buf format -d --exit-code proto
make proto-build      # buf build proto

make ci-lint          # bash scripts/check-action-pins.sh

make java-build       # cd java && ./mvnw clean compile -B
make java-test        # cd java && ./mvnw test -B
make java-verify      # cd java && ./mvnw verify -B

make web-lint         # cd frontend && npm run lint
make web-build        # cd frontend && npm run build

make rust-test        # cd rust && cargo test --workspace
make python-test      # .venv/Scripts/python -m pytest python/tests
```

---

## Test Commands (Run at Right Level)

| Component | Unit/Property | Integration/Contract | Notes |
|-----------|---------------|---------------------|-------|
| **Rust** | `cargo test --workspace` | Replay golden fixtures | `cargo test --workspace --all-targets` for benches |
| **Java** | `./mvnw test` (unit) | `./mvnw verify` (integration + checkstyle + spotbugs + coverage) | Integration tests = `*IntegrationTest.java` |
| **Python** | `pytest python/tests` | Parity: PyO3 vs Rust server | Run in `.venv` |
| **Frontend** | `npm run type-check` + `npm run lint` | Playwright E2E (scheduled) | `npm run generate:api` after API changes |

**Cross-system tests (CI gates):**
- `buf lint` + `buf breaking` (if `proto/` changed)
- Replay parity on small fixtures
- Benchmark smoke with regression threshold (fail if book update median regresses >15%)

---

## Architecture Notes (Not Obvious from Filenames)

- **Protobuf is the single source of truth** — all languages generate from `proto/`. Strategy: `buf` for lint/format/build/breaking; native per-language codegen (ADR-0011).
- **Rust workspace crates** (all prefixed `sb-` per ADR-0012):
  - `core` — types, fixed-point, error types
  - `book` — optimized dense-window order book + BTreeMap oracle (no I/O, no async)
  - `connectors` — exchange trait + Binance impl
  - `metrics` — incremental metrics (spread, mid, microprice, depth, imbalance, VWAP)
  - `replay` — event-log writer thread + replay driver
  - `server` — gRPC service, config, health/metrics endpoint
  - `pybook` — PyO3 bindings (maturin) for Python
- **Java API** — Spring Boot 3.3, Java 21, virtual threads, jOOQ, Flyway, gRPC client to Rust feed, WebSocket fan-out to Next.js.
- **Python research** — `sb_research` package (built via maturin from `rust/pybook`). Uses `numpy`, `pyarrow`.
- **Frontend** — Next.js 14 (App Router), client-only live components, WebSocket straight to Java API (ADR-0008, ADR-0010).
- **Event log format** — framed Protobuf + zstd, compacted to Parquet (ADR-0006).
- **Auth** — JWT RS256 access + rotating refresh tokens (ADR-0007). Keys in `infra/secrets/` (gitignored).

---

## Environment & Filesystem Gotchas

- **Recommended:** repo on WSL2 Linux filesystem (`~/dev/stock_broker`). POSIX permissions enforced, secrets can be `chmod 600`.
- **Supported with limitations:** Windows folder outside OneDrive (e.g., `C:\dev\stock_broker`). No POSIX permissions, slower builds, scripts must be invoked via `bash scripts/<name>.sh`.
- **Never:** keep repo inside OneDrive (cloud-synced secrets, corruption risk, syncing of `target/` and `node_modules/`).
- **Benchmark numbers for publication** must be measured on Linux filesystem (e.g., `CARGO_TARGET_DIR` on ext4 inside Ubuntu). Record filesystem in benchmark metadata.
- **Windows agent runs Linux commands via WSL2:**
  ```bash
  wsl -d Ubuntu --cd 'C:\dev\stock_broker' -- bash -lc '<command>'
  ```
  - Always wrap bash command in **single quotes**.
  - If command needs quotes/heredoc, write to temp script and run it.
  - Git: run from PowerShell (Windows git). Set `core.fileMode=false`, `core.autocrlf=false`.
  - Files: UTF-8, LF line endings, NO BOM. Strip CR after editing `.sh`, Makefile, `.yml`, `.yaml`, `.sql`, `.proto`, `.md`.
  - No sudo available in agent — stop and print exact command for user to run in Ubuntu app.

---

## Coding Standards (Enforced in CI)

| Language | Lint/Format | Typecheck | Key Rules |
|----------|-------------|-----------|-----------|
| Rust | `cargo fmt --check`, `clippy -D warnings` | `cargo check` | `#![forbid(unsafe_code)]` in all crates; `thiserror` for libs, `anyhow` only in binaries; hot-path functions documented with allocation/complexity guarantees |
| Java | Checkstyle (google-java-format), SpotBugs | `mvn verify` | Constructor injection; immutable DTOs (records); no business logic in controllers; `BigDecimal` for money; avoid `synchronized` in virtual-thread code |
| Python | `ruff` (lint+format) | `mypy --strict` for engine/execution/data | Pure functions for metrics; no global state in engine; heavy loops in Rust (via `pybook`) or Numba |
| TypeScript | `eslint`, `tsc --noEmit` | `tsc --strict` | No `any` (lint rule); fixed-point formatting helpers only place converting integers to display strings; no data fetching for market data in server components |

---

## Definition of Done (Per Change)

- [ ] Builds with zero warnings (`clippy -D warnings`, `tsc --strict`, `ruff`, `mypy`, Java compiler lint)
- [ ] Tests at the right level; regression test for bugs (see `docs/10-TESTING-STRATEGY.md`)
- [ ] Metrics/logging for operational features
- [ ] Security considerations checked (authz scoping, input validation, secrets)
- [ ] Docs/ADR updated; changelog entry if user-visible
- [ ] Perf-sensitive changes include benchmark before/after
- [ ] Protobuf compatibility: `buf lint`, `buf format -d --exit-code`, `buf build`, `buf breaking` pass (if `proto/` touched)

---

## Key Documentation References

| Doc | Purpose |
|-----|---------|
| `CLAUDE.md` | Agent instructions (read every session) |
| `docs/01-PRD.md` | Product requirements (what and why) |
| `docs/02-TRD.md` | Technical requirements (how) |
| `docs/03-ARCHITECTURE.md` | Diagrams, data flows, failure modes |
| `docs/04-API-SPEC.md` to `docs/08-BACKTESTING-METHODOLOGY.md` | Detailed contracts |
| `docs/adr/README.md` | Architecture decision records |
| `docs/10-TESTING-STRATEGY.md` | Test levels, tools, CI gates |
| `docs/13-DEVELOPMENT-GUIDE.md` | Setup, conventions, workflow |
| `skills/README.md` | Best-practice skills index |

---

## Common Agent Pitfalls to Avoid

1. **Using floats for money** — use fixed-point integers / `BigDecimal` everywhere money crosses boundaries.
2. **Re-implementing the order book** — Python must use `pybook` (PyO3); Java must call Rust via gRPC.
3. **Allocating on Rust hot path** — check `cargo test` + allocation counting tests; no `Vec`, `Box`, `String` in apply path.
4. **Missing `user_id` scoping** — every user-owned row filtered in repository layer; add isolation test.
5. **Skipping protobuf checks** — if you touch `proto/`, run `make proto` and ensure `buf breaking` passes.
6. **Logging secrets** — never log tokens, passwords, refresh tokens. Print hashes/lengths/permissions only.
7. **Writing CRLF line endings** — breaks scripts on Linux. Always LF.
8. **Assuming POSIX permissions on Windows** — `chmod 600` does nothing on NTFS. Warn the user.
9. **Claiming performance without measurement** — every perf claim needs benchmark methodology, hardware, OS, filesystem recorded.
10. **Not updating docs/ADRs when behavior changes** — if a spec or ADR is contradicted, stop and flag it; update in the same change.