---
name: paper-trading-ledger
description: Implementing the paper-trading engine and double-entry ledger correctly: order state machine, book-walking fill model, fees and rounding, idempotency keys, single-writer-per-account concurrency, atomic transactions, reconciliation, P&L, and golden test vectors shared with the Python backtester. Use this skill whenever touching orders, fills, balances, positions, fees, the ledger tables, trading endpoints, or anything that changes a user's simulated money.
---

# Paper trading and ledger

Spec: `docs/07-PAPER-TRADING-SPEC.md`, schema: `docs/05-DATA-MODEL.md`. **Simulated money only (ADR-0009).** Never add real-order or brokerage code.

## Non-negotiables
1. **Fills come from the live order book** via the shared `walk_market` logic (Rust-defined semantics, Java implementation of the paper engine consumes snapshots). Never fill at mid.
2. **Exact arithmetic:** `BigDecimal` + `NUMERIC(38,18)`; explicit rounding mode; fees round **up**; no `double` anywhere in this module.
3. **Ledger is the source of truth;** balances derive from entries; cached balances are reconciled.
4. **One writer per account;** all commands for an account are serialized.
5. **One DB transaction per business event:** validate -> insert order -> compute fills -> insert fills + ledger entries + position/balance caches -> commit. Publish WebSocket events only after commit.
6. **Idempotent commands:** `Idempotency-Key` unique per user; same key + same body returns the original result; different body => 409.
7. **Same rules as the backtester,** verified with shared golden vectors (`testdata/fill-vectors`).

## Order lifecycle
`NEW -> ACCEPTED -> PARTIALLY_FILLED -> FILLED`; `-> CANCELED`; `NEW -> REJECTED`. Terminal states are immutable. Validate transitions in code and with DB `CHECK`s. Cancel of a terminal order is a no-op returning current state.

Pre-trade checks: instrument known; **book valid and fresh**; sufficient balance including fees; qty/price alignment to lot/tick; price band for limits; per-user caps. Reject with stable codes.

## Fill model essentials
- Marketable orders walk the opposite side best-to-worse, producing one fill per consumed level; stop at the limit price for LIMIT; if depth runs out, fill what exists and cancel the remainder (never invent liquidity).
- Latency `L`: decision at `t`, evaluation against the book at `t + L` (configurable, default 0 for live paper).
- Resting limits: fill only on trades **strictly through** the limit, at the limit price, capped by trade quantity, `MAKER`; touch does not fill; quote movement alone does not fill (see spec 4.2).
- Record `book_epoch/seq` on each fill for auditability.

## Fees
`fee = notional * bps / 10_000`, round up to the asset's smallest unit. Schedule configurable per maker/taker. Charge in quote asset by default.

## Ledger rules
- Entries are append-only (no UPDATE/DELETE grants), each transaction balances per asset (`SUM(amount) = 0`); enforce with a deferred constraint trigger or a service-level assertion + test.
- Postings per fill follow spec section 6 (user cash/asset accounts, fee account, system market counter-accounts).
- Deposits only at account creation (starting balance) or an explicit admin adjustment (audited).
- **Reconciliation job:** recompute balances from entries; recompute positions from fills; compare to caches; alarm on any difference; also run after property tests.

## Concurrency
- Keyed single-thread executor / mailbox per account (hash accounts to a pool).
- Defense in depth: `SELECT ... FOR UPDATE` on the account row (or `SERIALIZABLE` with retry) inside the transaction.
- Resting-order evaluation on each trade event runs through the same per-account writer; maintain a per-symbol, price-ordered index of open orders to avoid scanning all accounts.
- No external calls inside the transaction.

## P&L
Average-cost positions in v1. Realized P&L on reduction = `(fill_price - avg_cost) * qty_closed` (fees tracked separately and reported). Unrealized marked at mid by default; offer a conservative liquidation-value mark.

## Testing (all required)
- Golden vectors: walk across levels, insufficient depth, limit stops, IOC remainder, fee rounding, tiny qty, zero-fee.
- Property tests (jqwik): random valid order sequences preserve invariants - no negative cash, ledger balanced, balances == derived from entries, fills never exceed displayed depth, positions == sum of fills.
- Concurrency tests: many parallel orders on one account cannot overspend; duplicate idempotency keys yield one order.
- Resting limit semantics: touch (no fill), through (fill), partial through-trade, cancel races.
- Isolation tests: user A cannot read/cancel B's orders.
- Parity: replay a recorded day with scripted orders through Java and Python; identical fills and balances.

## Anti-patterns
- Updating balances in place without ledger entries; computing balances from floats.
- Publishing WebSocket events before commit; retrying non-idempotent operations.
- Using wall-clock `now()` in fill logic (use event/decision timestamps passed in).
- Fills at mid, or filling more than displayed depth.

## Review checklist
- [ ] Single transaction per command; events after commit
- [ ] Golden vectors updated for any fill/fee change (and Python kept in sync)
- [ ] Reconciliation passes in tests
- [ ] Idempotency and concurrency tests present
- [ ] No double/float money; rounding mode explicit
