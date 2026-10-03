---
name: market-microstructure-metrics
description: Definitions, formulas, edge cases and implementation rules for order-book and trade metrics (spread, mid, microprice, imbalance, depth, rolling VWAP, trade-flow imbalance, slippage). Use this skill whenever computing, displaying, testing or documenting market metrics in Rust, Python or the frontend, designing alert thresholds on metrics, or writing trading signals that consume them.
---

# Market microstructure metrics

Source of truth for formulas: `docs/06-ORDER-BOOK-SPEC.md` section 5 and `docs/16-GLOSSARY.md`. Rust computes; Java forwards; Python (via PyO3) and the UI consume. **Never recompute these independently in another language** except for display formatting.

## Definitions
Let `bb, ba` = best bid/ask price; `qb, qa` = best bid/ask size; `D_b(N), D_a(N)` = summed size over the top N levels per side.
- **Spread** = `ba - bb` (integer price units). In bps: `(ba - bb) / mid * 10_000`.
- **Mid** = `(bb + ba) / 2` (analytics double; half-tick values possible).
- **Microprice** = `(bb*qa + ba*qb) / (qa + qb)`. Leans toward the side with *less* resting size (the side likely to be consumed). Undefined if `qa+qb = 0`.
- **Imbalance(N)** = `(D_b(N) - D_a(N)) / (D_b(N) + D_a(N))` in [-1, 1]; define as 0 when the denominator is 0.
- **Depth within X bps** = sum of size on each side with price within X bps of mid.
- **Rolling VWAP(T)** = `sum(p_i * q_i) / sum(q_i)` over trades in the last `T` of event time.
- **Trade-flow imbalance(T)** = `(V_buy - V_sell) / (V_buy + V_sell)` using the aggressor flag.
- **Slippage (bps)** for a buy of size Q = `(avg_fill_price - reference) / reference * 10_000`, where reference is mid (or decision-time best ask for execution cost) - always state which. Computed by walking the book (`walk_market`), never by formula.

## Implementation rules
1. **Incremental updates:** adjust running sums when a level changes; avoid rescanning the book. Top-N recompute is acceptable when N is small and benchmarked.
2. **Rolling windows** use ring buffers with running sums and eviction by **event time** (not wall clock) so replay is deterministic.
3. **Pure functions of state:** metrics depend only on book + trade window + config. No hidden state, no randomness.
4. **Numeric hygiene:** accumulate in `i128`/`i64` where possible and convert to `f64` at the end; guard divisions by zero; document NaN policy (emit `valid=false` instead of NaN).
5. **Validity:** when the book is invalid or one side is empty, mark metrics invalid; consumers must not trade on invalid metrics.
6. **Units:** keep price/qty units and scales explicit; convert to real units only in analytics fields.

## Testing
- Hand-computed examples for each metric, including odd/even spreads and empty sides.
- Property tests: imbalance in [-1,1]; microprice within [bb, ba]; VWAP within trade price range; metrics unchanged by no-op deltas; metrics identical between oracle and optimized book.
- Replay determinism: identical metrics across runs.
- Frontend: display formatting tests (scales, rounding), never recomputation.

## Interpreting metrics in strategies/alerts
- Imbalance and microprice are noisy at tick level; require persistence (K events) or smoothing and always evaluate **after costs** in backtests.
- Spread widening plus depth thinning often precedes volatility; useful as risk filters rather than direction signals.
- Always validate that a signal's lead time exceeds latency + fees.

## Review checklist
- [ ] Formula matches this skill/spec; edge cases covered
- [ ] No allocation per update; incremental maintenance
- [ ] Event-time windows; deterministic replay
- [ ] Invalid states flagged rather than NaN
