---
name: python-backtesting-engine
description: Building honest, reproducible backtests in Python on top of the Rust order book: event-driven engine, no-lookahead enforcement, latency/fee/slippage execution model, data validation, walk-forward evaluation, deflated Sharpe, cost-breakdown reporting, FastAPI job service and reproducibility manifests. Use this skill whenever writing strategies, the backtest engine, performance reports, data loaders, the research job API, or interpreting backtest results.
---

# Python backtesting engine

Spec: `docs/08-BACKTESTING-METHODOLOGY.md`. Principle: a backtest is a claim; make it falsifiable and conservative.

## Architecture
- Events come from `pybook.EventReader` in **batches** with book state per event (Arrow/NumPy). Decision time is `recv_ts` (when the system would have known).
- `StateView` is immutable and exposes only data with `recv_ts <= now`; any attempt to access future data raises `LookaheadError`. A test-only "clairvoyant" strategy must be rejected by CI to prove the guard works.
- Strategy interface: `on_event(state) -> list[OrderIntent]`. Keep strategies pure (no I/O, no global state); seed any randomness explicitly.
- Execution simulator: orders become live at `t + latency`; marketable fills walk the **Rust book** (`walk_market`) as of that time; resting limits follow the conservative through-trade rule; fees maker/taker rounded up. Same rules and golden vectors as Java.

## Data validation (before running)
Detect gaps/resync markers, duplicates, out-of-order timestamps, clock jumps, missing hours. Unsafe gaps => fail with `BACKTEST_DATA_GAP` unless `skip_gaps` is requested (then reset strategy state at gaps and list excluded intervals). Always emit a data-quality report.

## Evaluation protocol
- Chronological train/validation/test; touch the test set once.
- **Walk-forward** (rolling tune/test windows).
- **Sensitivity** heatmaps; flag single-peak optima.
- **Multiple-testing control:** log the number of configurations tried; report a deflated Sharpe (or similar) when searching parameters; never headline the best of many trials uncorrected.
- **Baselines:** buy-and-hold; seeded random strategy with matched turnover (report a distribution over many seeds). Strategies must beat baselines **after costs**.
- Latency sensitivity: report at 0/50/200/500 ms.
- Regime breakdown: by volatility regime and hour of day.

## Metrics (see doc 08 section 7)
Net/gross return, volatility, Sharpe/Sortino (state sampling interval, annualization with 365-day crypto year, risk-free = 0), max drawdown, hit rate, turnover, average trade, and the **cost waterfall**: gross -> fees -> slippage -> latency effect -> net. Mark short samples as statistically weak.

## Reproducibility
Every run writes `run.json`: git commit, `pybook` version, parameters, seed, data range and file hashes, fee model, latency, host/OS, durations. Same inputs + seed => byte-identical outputs (test it). Artifacts: summary JSON, equity and trades Parquet, optional HTML report.

## Performance
- Keep the per-event Python path minimal: vectorize signal computation over batches (Polars/NumPy) or use Numba; the engine loop handles order timing. Target 100x real time (measure).
- Use Polars/DuckDB for data prep; avoid pandas row loops; avoid `iterrows`.

## Job service (FastAPI)
`POST /jobs`, `GET /jobs/{id}`, `GET /jobs/{id}/result`; process pool for runs; strict request validation (date ranges, symbol allowlist, param schema); timeouts, quotas and queue limits; service-token auth; never expose publicly. Store status/errors; no partial results on failure.

## Testing
- Hypothesis: cash/positions conserved; equity identity; no negative cash; fills <= displayed depth.
- Golden scenarios with hand-computed P&L; shared fill vectors.
- Lookahead guard tests; determinism test; parity with Java paper engine on a recorded day.
- Data validator tests with fixtures containing gaps/dupes.

## Reporting rules (honesty)
Include a Limitations section: no queue-position model, no reaction from other participants, recorded-data gaps, spot only, simulated. Never present backtested returns as expected performance. Label everything "simulated; not investment advice".

## Anti-patterns
- Using exchange timestamps for decisions; peeking at the next event/bar; normalizing with full-sample statistics.
- Filling at mid or at the signal price; ignoring fees/latency.
- Tuning on the test set; reporting only the best parameter set.
- Re-implementing the order book or fill logic in Python.

## Review checklist
- [ ] No lookahead path; guard tests pass
- [ ] Costs/latency modeled and reported (gross vs net)
- [ ] Baselines + out-of-sample + trial count included
- [ ] `run.json` manifest complete; deterministic rerun verified
- [ ] Limitations and disclaimer present in reports
