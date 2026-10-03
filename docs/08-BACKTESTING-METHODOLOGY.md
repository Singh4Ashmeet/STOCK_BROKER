# Backtesting Methodology

Goal 3: results a skeptical reader can trust. The methodology below is the contract for the Python engine and for how results are presented.

## 1. Principles

1. **Same world as live:** the backtest replays recorded book/trade events through the **same Rust book** (PyO3). No separate approximation of market structure.
2. **No lookahead, enforced by the engine,** not by convention.
3. **Costs are first-class:** fees, slippage (from book depth), latency, and an explicit queue-position assumption.
4. **Report what was tried:** parameters searched, number of trials, out-of-sample results.
5. **Reproducible:** each run stores code version, wheel version, parameters, seed, data range, and environment.

## 2. Data validation (before any run)

- Check the event log for: gaps (markers), resyncs, duplicates, out-of-order timestamps, clock jumps, missing hours.
- A run over a range that contains an **unsafe gap** fails with `BACKTEST_DATA_GAP` unless the request explicitly opts in to `skip_gaps`, in which case the strategy state is reset at each gap and the report lists excluded intervals.
- Output a **data quality report** with each run (coverage percent, number of resyncs, excluded time).

## 3. Engine design

```
for batch in EventReader(files, batch_size=N):          # PyO3, Arrow/NumPy arrays, book state included
    for event in batch:                                  # strictly increasing by recv_ts (ties broken by file order)
        clock.advance(event.recv_ts)
        exec_sim.release_due_orders(clock.now)           # orders whose (submit_ts + latency) <= now reach the "market"
        state = StateView(book=event.book, metrics=event.metrics, clock=clock.now)
        orders = strategy.on_event(state)                # strategy sees only data with recv_ts <= now
        exec_sim.submit(orders, submit_ts=clock.now)
        exec_sim.match_resting(event)                    # resting-order rules identical to paper trading
        portfolio.mark(event)
```

- **Time source:** `recv_ts` (when the system would have known). Exchange timestamps are used only for analysis, never for decisions.
- **StateView is immutable** and exposes no future data; attempting to access it raises `LookaheadError`.
- **Batching** is internal; strategies never see batch boundaries.

## 4. Execution model

| Aspect | Rule |
|---|---|
| Order submission | Strategy decision at `t`; order becomes live at `t + latency` (default 50 ms, configurable; show sensitivity at 0, 50, 200, 500 ms) |
| Marketable fills | Walk the book **as of `t + latency`** via the Rust `walk_market` primitive |
| Resting limits | Conservative: fill only when trades print strictly through the limit; partial by trade size (same as `07-PAPER-TRADING-SPEC.md`) |
| Queue position | Not modeled; stated assumption. Optional pessimistic variant: require the displayed queue ahead (at order time) to trade through before filling |
| Fees | Maker/taker bps from the configured schedule; rounded up |
| Market impact | Book-walking captures own-order impact for the order itself; optional extra impact penalty parameter for sizes beyond a fraction of displayed depth; no modeling of other participants' reaction |
| Borrow/leverage | None (spot only) |
| Funding/slippage on exit | Included because exits also walk the book |

Limitations section (must appear in every report): no queue-position model, no reaction of other participants, venue outages not simulated, recorded data may differ from true venue state if gaps exist.

## 5. Strategies in scope

1. **Baselines:** buy-and-hold; random entry/exit with matched turnover (seeded; many seeds reported as a distribution).
2. **Order-book imbalance:** go long when top-N imbalance exceeds a threshold for K events, exit on reversal or time stop.
3. **Microprice drift:** signal from microprice vs mid.
4. **Trade-flow momentum:** signal from trade-flow imbalance over a window.
5. *(Optional)* gradient-boosted model using the same features with strict time-based splits.

A strategy that cannot beat baselines **after costs** is reported as such. Negative results are acceptable and valuable.

## 6. Evaluation protocol

- **Splits:** train / validation / untouched test, chronological; test data touched once at the end.
- **Walk-forward:** rolling window (e.g. tune on 5 days, test on the next 1 day, roll forward).
- **Sensitivity:** parameter heatmaps; flag results that depend on a single sharp optimum.
- **Multiple testing:** record the number of configurations tried; report a deflated Sharpe ratio (or similar correction) when searching parameters; do not present the best of many trials as the headline without correction.
- **Randomization check:** compare against random-signal baselines with the same turnover and costs.
- **Regime breakdown:** performance by volatility regime and by hour of day.

## 7. Metrics (definitions)

| Metric | Definition |
|---|---|
| Net return | `(E_end - E_start) / E_start` after all costs |
| Gross return | Return before fees and slippage (for the cost breakdown) |
| Volatility | Std dev of per-interval returns, annualized with the stated sampling interval (crypto trades 24/7: use 365-day years) |
| Sharpe | `mean(r) / std(r) * sqrt(periods_per_year)` with risk-free = 0 (state it) |
| Sortino | Uses downside deviation |
| Max drawdown | Largest peak-to-trough equity decline |
| Hit rate | Winning trades / total trades (after costs) |
| Turnover | Total traded notional / average equity |
| Avg trade | Mean net P&L per round trip |
| Cost breakdown | Gross P&L, minus fees, minus slippage vs mid at decision, minus latency effect (slippage vs decision-time book), = net |

Short sample warning: for samples under a configured threshold of trades or days, the report marks Sharpe and returns as **statistically weak**.

## 8. Reporting

Each run produces: summary JSON, equity and drawdown curves, trade log, cost-breakdown waterfall, sensitivity plots (if applicable), data quality report, limitations, and the full reproducibility manifest. The UI shows the cost waterfall prominently (gross to net).

## 9. Parity with live paper trading

- Fill, fee and latency rules are shared via golden vectors.
- **Parity test:** run the same recorded day through (a) the backtester with a scripted order list and (b) the Java paper engine fed from replay; assert identical fills (price, qty, time within tolerance) and identical ledger balances.

## 10. Testing

- Hypothesis property tests: cash and positions conserved; equity identity holds; no negative cash; fills never exceed displayed depth.
- Lookahead tests: strategies that read future data must raise; a "clairvoyant" strategy (peeks via a test-only backdoor) must be rejected in CI to prove the guard exists.
- Golden scenarios with hand-computed P&L.
- Determinism: same inputs and seed produce byte-identical outputs.
- PyO3 parity test (Rust server vs binding) per `02-TRD.md` section 7.

## 11. Performance targets

Replay at least 100x real time on a laptop for 5 symbols of depth data (target; measure and publish), enabling multi-day studies in minutes.
