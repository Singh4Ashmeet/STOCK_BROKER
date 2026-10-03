# Order Book Specification

Goal 2 (correctness) lives here. This spec defines the synchronization algorithm, invariants, data structure, metrics, and verification.

> **VERIFY:** Binance stream names, update-id semantics, limits, and endpoints must be checked against the current official Binance Spot API docs before implementation. The algorithm below follows the documented "how to manage a local order book correctly" approach.

## 1. Inputs (Binance spot, diff depth)

- WebSocket diff depth stream (e.g. `<symbol>@depth` or `@depth@100ms`) delivering events with: event time, first update id `U`, final update id `u`, and arrays of `[price, quantity]` bids and asks. **Quantities are absolute, not deltas.** Quantity `0` means remove the level.
- REST depth snapshot (`/api/v3/depth?symbol=...&limit=...`) returning `lastUpdateId`, bids, asks.
- Trade stream for trades.
- Note: market-data-only hosts exist (for regions/IPs where the main host is restricted); verify availability.

## 2. Synchronization algorithm

State per symbol: `Disconnected -> Buffering -> Syncing -> Live`, with `Invalid` on failure.

1. **Buffering:** open the diff stream; start buffering events (do not apply).
2. **Snapshot:** fetch the REST snapshot; let `L = lastUpdateId`.
3. If `L` is older than the first buffered event's `U` (snapshot too old): discard it and refetch.
4. Drop buffered events with `u <= L`.
5. The first applied event must satisfy `U <= L + 1 <= u`. If not, restart from step 2 (or from 1 if the buffer cannot bridge the gap).
6. Apply the snapshot to the book; apply the remaining buffered events in order.
7. **Live:** for every subsequent event require `U == prev_u + 1`. If violated: mark `Invalid`, increment `epoch`, go to step 1.
8. On reconnect, always go to step 1 with a new `epoch`.
9. After entering `Live` emit a **snapshot** message (new epoch, `seq` starts at 1) to consumers, then deltas.

Edge cases to handle and test: events arriving during the snapshot fetch; snapshot fetch rate limits (backoff; limit concurrent resyncs); duplicate events; out-of-order frames; empty diffs; price levels outside the dense window; crossed book after an apply (treat as error: mark Invalid).

## 3. Invariants (checked in tests, and cheaply in debug/live sampling)

| ID | Invariant |
|---|---|
| I1 | Bids strictly descending by price, asks strictly ascending |
| I2 | No zero-quantity level stored |
| I3 | Not crossed: `best_bid < best_ask` (when both sides non-empty) |
| I4 | Price is a multiple of the tick size; qty a multiple of lot size (venue-dependent; warn, do not crash) |
| I5 | Optimized book equals oracle book after every operation (tests) |
| I6 | `seq` strictly increments by 1 within an epoch; epoch increments on every rebuild |
| I7 | Metrics are a pure function of book + trade-window state (replay determinism) |
| I8 | Cached best bid/ask equals a full scan result (tests) |

## 4. Data structures

### 4.1 Oracle (reference, tests only)
`BTreeMap<Price, Qty>` per side. Simple, obviously correct. Used by property tests and as the baseline in benchmarks. Not used in production paths.

### 4.2 Optimized book (production)
Per side:
- **Dense window:** `Vec<Qty>` (or boxed slice) indexed by `(price_ticks - base_ticks)` covering `W` ticks around the reference price (configurable per symbol, e.g. 4,096 to 65,536 ticks depending on tick size and volatility).
- **Occupancy bitset:** one bit per level for O(1)-ish next-occupied search with `trailing_zeros`/`leading_zeros`.
- **Far levels:** a small sorted structure (e.g. `BTreeMap` or sorted `Vec`) for prices outside the window.
- **Cached best index** per side; recomputed only when the best level empties (scan outward via the bitset).
- **Re-centering:** when best price approaches a window edge (e.g. within 10 percent of W), rebuild the window around the new mid; migrate far levels into the window and out-of-window levels to the far structure. Re-centering is rare and may allocate; it must be budgeted, measured, and tested, and executed off the per-update fast path if it exceeds budget (or amortized).
- **Depth caches:** running cumulative sums for the top-N levels updated incrementally on level changes where possible; otherwise recompute over N levels (N is small, e.g. 10 to 50).
- Prices stored as integer ticks; quantities as integer lots/units; no floats.

### 4.3 API of the book (pure, no I/O)
```
apply_snapshot(&mut self, bids: &[Level], asks: &[Level])
apply_delta(&mut self, bids: &[Level], asks: &[Level]) -> ApplyResult  // reports crossed/err
best_bid(&self) -> Option<Level>; best_ask(&self) -> Option<Level>
levels(&self, side, n, out: &mut [Level]) -> usize                    // no alloc
depth_qty(&self, side, n) -> Qty
walk_market(&self, side, qty) -> FillPlan                              // used by paper trading & backtests
clear(&mut self)
```
`walk_market` returning the exact levels consumed is the primitive that guarantees paper trading and backtests share fill logic.

## 5. Metrics (computed incrementally in Rust)

Let `bb, ba` = best bid/ask prices; `qb, qa` = best bid/ask quantities; `D_b(N), D_a(N)` = summed qty over the top N levels.

| Metric | Formula | Notes |
|---|---|---|
| Spread | `ba - bb` | Integer price units; also bps = `(ba-bb)/mid*1e4` |
| Mid | `(bb + ba) / 2` | Analytics (double) |
| Microprice | `(bb*qa + ba*qb) / (qa + qb)` | Weights price by opposite-side size |
| Imbalance (top N) | `(D_b - D_a) / (D_b + D_a)` | Range [-1, 1]; define as 0 when denominator is 0 |
| Depth at N levels | `D_b(N), D_a(N)` | Also depth within X bps of mid (optional) |
| Rolling VWAP | `sum(p*q) / sum(q)` over time window `T` | Ring buffer with running sums; evict by time |
| Trade-flow imbalance | `(V_buy - V_sell) / (V_buy + V_sell)` over window `T` | Aggressor side from the venue flag |

Computation rules: update only what changed; no allocation; deterministic (time comes from event timestamps, not wall clock).

## 6. Verification plan

1. **Property tests (proptest):** random sequences of snapshot/delta ops (including zero-qty removes, far prices, re-centering triggers); assert I1 to I5 and I8 after every op against the oracle.
2. **Sequence tests:** feed synthetic venue streams with injected gaps, duplicates, reordering, late snapshots; assert correct state transitions and `epoch` handling.
3. **Recorded-feed replay:** golden outputs for recorded hours stored as hashes; any change must be explained.
4. **Live cross-check:** every `snapshot_check.interval`, fetch the REST snapshot; compare the top K levels allowing for in-flight updates (compare at the matching `lastUpdateId` when possible by buffering diffs). Metrics: `book_mismatch_total`, `book_checks_total`.
5. **Soak:** 24 h run: zero unexplained mismatches, no memory growth, all induced disconnects recover.
6. **Fuzzing:** parser fuzz targets; book apply fuzz with arbitrary byte-derived ops.
7. **Benchmarks:** oracle vs optimized (ns per update, p50/p99), per-op breakdown (apply, best-price recompute, metrics), including re-centering cost.

## 7. Performance requirements (targets)

| Operation | Target |
|---|---|
| Apply typical delta (a few levels) | under 1 us median, under 5 us p99 on a modern laptop core |
| Metrics update after delta | under 2 us median |
| Re-center | under 200 us, and rare (counted) |
| Allocation on the hot path | zero (verified with an allocation counter in tests) |

These are goals; record measured values in `benchmarks/`.
