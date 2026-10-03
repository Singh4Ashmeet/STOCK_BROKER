---
name: orderbook-engine-correctness
description: How to build and verify a correct local order book: Binance-style snapshot + diff synchronization, sequence-gap detection, resync state machine, invariants, the optimized dense-window book, the BTreeMap oracle, property tests, fault injection and live cross-checks. Use this skill whenever implementing, changing or debugging order book logic, book synchronization, resync/epoch/seq semantics, `book` or `connectors` crates, or investigating book mismatches - even if the request just says "update the book" or "handle reconnects".
---

# Order book engine correctness

Authoritative spec: `docs/06-ORDER-BOOK-SPEC.md`. **Verify exchange specifics (stream names, update ids, limits) against current official docs before coding; update the spec if they differ.**

## Mental model
A book is only as good as its **synchronization**. Applying diffs without a proper snapshot bridge, or missing one diff, produces a silently wrong book. Policy: **a book that might be wrong is marked Invalid and is never served as valid.**

## State machine (per symbol)
`Disconnected -> Buffering -> Syncing -> Live`; any gap/error/reconnect: `Live -> Invalid -> Buffering` with `epoch += 1`.

## Sync procedure (diff-depth streams)
1. Open stream, buffer events (do not apply).
2. Fetch REST snapshot (`lastUpdateId = L`). If `L < first buffered U`, discard and refetch.
3. Drop buffered events with `u <= L`.
4. First applied event must satisfy `U <= L+1 <= u`; otherwise restart.
5. Apply snapshot, then buffered events in order.
6. Live: require `U == prev_u + 1` for each event; violation => Invalid, new epoch, restart.
7. After Live, emit a snapshot with the new `epoch` and `seq = 1`, then deltas.
Quantities in diffs are **absolute**; qty 0 removes the level.

Handle: events arriving during the snapshot fetch, snapshot rate limits (backoff, limit concurrent resyncs across symbols), duplicates, reordering, empty diffs, crossed book after apply (=> Invalid).

## Invariants (assert in tests; sample cheaply in live)
Sorted sides; no zero-qty levels; not crossed; tick/lot alignment (warn); optimized == oracle; best-price cache == full scan; `seq` +1 within epoch; metrics are pure functions of state.

## Data structures
- **Oracle:** `BTreeMap<Price, Qty>` per side. Obvious and slow. Tests/benchmarks only.
- **Optimized:** dense `Vec<Qty>` window indexed by `(price_ticks - base)`, occupancy bitset (`trailing_zeros`/`leading_zeros` to find next level), cached best index per side, far-level fallback, incremental depth sums, re-centering when best nears the window edge.
- Re-centering is rare but expensive: count it, benchmark it, test it with price trends that cross the window.

## Testing recipe (do all of these)
1. **Differential property test (proptest):** generate random op sequences (snapshot, delta with random levels incl. removals, far prices, window-edge prices); apply to oracle and optimized; assert equality of full level lists, best prices, depth sums after **every** op. Shrinking must be enabled and seeds logged.
2. **Sequence fault tests:** synthetic venue streams with gaps, duplicates, reordering, snapshot too old/new, reconnect mid-sync; assert state transitions and epochs.
3. **Replay goldens:** hash book state every K events for recorded fixtures; diffs require an explanation.
4. **Live cross-check:** periodic REST snapshot compare of top K levels (account for in-flight updates by aligning on update id). Counters: `book_checks_total`, `book_mismatch_total`; target zero mismatches.
5. **Fuzz:** arbitrary byte-derived ops must never panic or corrupt invariants.
6. **Soak:** 24 h, forced disconnects, no memory growth.

## Output semantics to consumers
`epoch` increments on every rebuild; `seq` +1 per message within an epoch; first message after epoch change is a snapshot; `book_valid=false` during resync. Consumers must check both.

## Common bugs to look for
- Applying buffered events with `u <= L`.
- Treating diff quantities as deltas instead of absolutes.
- Forgetting to bump `epoch` on reconnect.
- Window base not updated on re-center; far levels not migrated.
- Best-price cache not invalidated when the best level is removed in the same message that adds a new best.
- Crossed book after out-of-order apply being served.
- Resync storms: many symbols hitting REST at once (rate limit) - serialize with a limiter.

## Review checklist
- [ ] Spec behavior verified against current exchange docs
- [ ] Oracle differential test passes with shrinking and >=100k cases
- [ ] Gap/duplicate/reorder/reconnect tests added for any state machine change
- [ ] Epoch/seq semantics unchanged or contract/docs updated
- [ ] No allocation introduced on the apply path; benchmark delta shown
