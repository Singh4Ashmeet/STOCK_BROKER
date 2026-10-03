# Instructions for AI coding agents (stock_broker)

You are helping build a polyglot market data platform (Rust + Java + Python + Next.js). Read this file first, every session.

## Before you write code
1. Read `docs/01-PRD.md` (what and why) and `docs/02-TRD.md` (how). Specs live in `docs/04` to `docs/08`.
2. Load the matching skill(s) from `skills/` for the area you are touching (index: `skills/README.md`).
3. If your change contradicts a doc or ADR, **stop and flag it**. Update the doc/ADR in the same change if the decision truly changes.

## Non-negotiable rules
- **Priority order:** (1) real-time latency, (2) order-book correctness, (3) honest backtesting. Never trade correctness for a benchmark number.
- **Paper trading only.** Never add code that places real orders or touches real funds or brokerage credentials.
- **No floats for money or quantities across any boundary.** Fixed-point integers (scaled by instrument metadata) in Rust/Proto, `BigDecimal`/`NUMERIC` in Java/Postgres, decimal strings in REST. Floats are allowed only for derived analytics (ratios, microprice) and never for balances or fills.
- **Rust computes market metrics; Java never recomputes them.**
- **One order-book implementation** (Rust). Python uses it via PyO3. Do not re-implement it in Python or Java.
- **Hot path rules (Rust):** no heap allocation, no locks, no blocking I/O, no logging at info level per message.
- **Every user-owned row is filtered by `user_id`** in one repository layer, with an isolation test.
- **No secrets in the repo.** Use env vars and `.env.example`. Never log tokens, passwords, or refresh tokens.
- **Reproducibility:** every benchmark or backtest result records code version, config, data range, and hardware.
- **Honest numbers:** report where latency was measured (loopback/laptop/VM). Never claim "better than X" without a measured comparison.

## Definition of done
- Code compiles with zero warnings (`clippy -D warnings`, `tsc --strict`, `ruff`, `mypy`, Java compiler lint).
- Tests added at the right level (see `docs/10-TESTING-STRATEGY.md`) and passing.
- Metrics/logging added where the feature is operationally relevant.
- Docs/ADR updated if behavior, contract, or decision changed.
- `buf lint` and `buf breaking` pass if `proto/` changed.

## Working style
- Small, reviewable changes. One concern per PR. Conventional Commits.
- Prefer boring, well-tested approaches; justify any clever optimization with a benchmark in the PR description.
- When unsure about an exchange API detail (limits, message formats), **verify against the current official docs** rather than trusting memory or these docs; then update the doc.
