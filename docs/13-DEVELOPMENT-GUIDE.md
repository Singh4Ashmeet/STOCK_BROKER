# Development Guide

## 1. Prerequisites

| Tool | Version | Used for |
|---|---|---|
| Rust (rustup) | stable (pin in `rust-toolchain.toml`) | feed, pybook |
| JDK | 21 (Temurin) | Java API |
| Gradle or Maven | via wrapper | Java build |
| Python | 3.12 + `uv` or `venv` | research |
| maturin | latest | building the PyO3 wheel |
| Node.js | current LTS + pnpm | frontend |
| Docker + Compose | recent | infra |
| buf | latest | proto lint/breaking/codegen |
| Optional | `cargo-fuzz`, `cargo-audit`, `cargo-deny`, `k6`, `perf`, `flamegraph`, `gitleaks`, `pre-commit` | quality tooling |

Windows note: the author's machine may be Windows. Use WSL2 (Ubuntu) for the Rust feed, perf tooling, and Docker; native Windows works for Java, Node and most Python, but Linux gives the most representative latency numbers and tooling (`perf`, CPU pinning, governors). Record the environment with every benchmark.

## 2. First-time setup (once the scaffold exists)

```
git clone <repo> && cd stock_broker
./scripts/bootstrap            # generates .env (dev secrets, JWT keys), installs git hooks
docker compose --profile core --profile obs up -d
make proto                     # lint, format-check, build, compile-check (Docker-based, no local buf)
pre-commit install --install-hooks -t pre-commit -t commit-msg
# then per service: see its README for run/test commands
```

All tools (buf, shellcheck, gitleaks, grpcio-tools) run via Docker — no local installs required.

Provide a top-level `Makefile` with targets: `up`, `down`, `up-core`, `up-bus`, `proto`, `proto-lint`, `proto-format`, `proto-format-check`, `proto-build`, `proto-breaking`, `proto-compile-check`, `test`, `lint`, `bench`, `replay`, `e2e`.

## 3. Repository conventions

- **Branching:** trunk-based; short-lived feature branches; squash merge to `main`.
- **Commits:** Conventional Commits (`feat(feed): ...`, `fix(api): ...`, `docs: ...`, `perf(book): ...`, `test: ...`, `chore: ...`). Scope = `feed|book|metrics|api|research|web|proto|infra|docs`.
- **PRs:** small, one concern; description includes what/why/how tested; benchmark deltas for `perf` changes; screenshots for UI.
- **Docs-with-code:** behavior or contract changes update `docs/` and add/modify an ADR when a decision changes.

## 4. Coding standards

### Rust
- `rustfmt`, `clippy -D warnings`, `#![forbid(unsafe_code)]` in all crates except where `unsafe` is justified in a comment with a safety argument and tests (and reviewed in the PR).
- Errors: `thiserror` for libraries, `anyhow` only in binaries' top level. No `unwrap/expect` on external input.
- Hot-path functions are documented with their allocation and complexity guarantees.
- Public types have docs; `core` types are `Copy` where sensible.

### Java
- Spotless (google-java-format) or Checkstyle; `-Xlint:all -Werror` in CI.
- Constructor injection; immutable DTOs (records); no business logic in controllers; transactions only in the service layer.
- No `float`/`double` for money; `BigDecimal` with explicit `MathContext`/rounding mode.
- Avoid `synchronized` around blocking calls in virtual-thread code (pinning); prefer `ReentrantLock` or single-writer mailboxes.

### Python
- `ruff` (lint + format), `mypy --strict` for `engine`, `execution`, `data`; type hints everywhere.
- Pure functions for metrics; no global state in the engine; seeds explicit.
- Heavy loops in Rust (via `pybook`) or Numba; avoid per-event Python callbacks where batch operations suffice.

### TypeScript
- `strict: true`, no `any` (lint rule), ESLint + Prettier.
- Fixed-point formatting helpers are the only place that converts integers to display strings.
- No data fetching for market data in server components; live code is client-only.

## 5. Definition of Done (per change)

- [ ] Builds with zero warnings (`clippy -D warnings`, `tsc --strict`, `ruff`, `mypy`, Java compiler lint)
- [ ] Tests at the right level; regression test for bugs (see docs/10-TESTING-STRATEGY.md)
- [ ] Metrics/logging for operational features
- [ ] Security considerations checked (authz scoping, input validation, secrets)
- [ ] Docs/ADR updated; changelog entry if user-visible
- [ ] Perf-sensitive changes include benchmark before/after
- [ ] Protobuf compatibility: `buf lint`, `buf format -d --exit-code`, `buf build`, `buf breaking` pass (if proto/ touched)

## 6. Working with AI coding agents

- Start every session with `CLAUDE.md`; load matching skills from `skills/`.
- Give agents a **narrow task with acceptance criteria** and point them at the relevant spec (`docs/04` to `docs/08`).
- Require them to run the tests and show results; require benchmarks for perf claims.
- Review generated code for: float usage with money, hot-path allocation, missing `user_id` scoping, unbounded queues, lookahead in backtests, swallowed errors.
- When an agent proposes deviating from a spec, make it write the ADR first.

## 7. Local debugging tips

- Replay a fixture to reproduce any live issue: `feed --replay testdata/feeds/gap-case.pbz`.
- Dump book state at a given `seq` for diffing against the oracle.
- Use `RUST_LOG=feed=debug` only off the hot path (state transitions), never per message in benchmarks.
- For WebSocket issues use a CLI client (`websocat`) plus the Network tab; check `epoch/seq` continuity first.

## 8. Directory layout reference

```
rust/     Cargo workspace (core, book, metrics, connectors, server, replay, pybook)
java/     Spring Boot app
python/   sb_research package + job service
frontend/ Next.js app
proto/    Shared contracts
infra/    compose, Dockerfiles, prometheus, grafana
testdata/ fixtures and golden vectors
benchmarks/ results
scripts/  helpers
docs/     documentation, ADRs, runbooks
skills/   agent skills
```
