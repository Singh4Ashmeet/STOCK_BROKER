# Paper Trading Specification

Paper trading only. No real orders, no real funds, no brokerage credentials. The UI shows a persistent "simulated" label.

## 1. Principles

1. **Fills come from the real live order book**, not mid price.
2. **Same rules as the backtester.** The fill model, fee model and latency model are specified once here, implemented in Java and Python, and verified with shared golden vectors in `testdata/fill-vectors/`.
3. **Money is exact:** `NUMERIC(38,18)` / `BigDecimal`; scales converted from feed integers without rounding errors.
4. **Ledger is the source of truth;** balances are derived. Everything is auditable and reconcilable.
5. **Serialized per account:** one writer per account.

## 2. Account model

- On registration create one account with a starting balance (default **100,000 USDT**, configurable) via a `DEPOSIT` ledger transaction against a system `EQUITY` account.
- Spot-style: **no leverage, no shorting** in v1. A SELL requires sufficient asset balance; a BUY requires sufficient quote cash for notional plus estimated fees.
- Instruments limited to configured symbols.

## 3. Orders

| Field | Rules |
|---|---|
| side | BUY or SELL |
| type | MARKET or LIMIT |
| tif | GTC (default) or IOC |
| qty | Positive; multiple of lot size; at least min notional (if known) |
| limit_price | LIMIT only; multiple of tick size |
| Idempotency-Key | Required header; unique per user; same key + same body returns the original result; same key + different body returns `409 ORDER_DUPLICATE_KEY_MISMATCH` |

### 3.1 State machine
`NEW -> ACCEPTED -> PARTIALLY_FILLED -> FILLED`; `ACCEPTED|PARTIALLY_FILLED -> CANCELED`; `NEW -> REJECTED`.
Terminal states: FILLED, CANCELED, REJECTED. Transitions are validated in code and by DB checks; each transition is one transaction.

### 3.2 Pre-trade checks (reject with a reason code)
- Instrument known and book **valid** (`ORDER_BOOK_INVALID` otherwise).
- Market data fresh: last update age under a threshold (default 2 s) else reject `STALE_MARKET_DATA`.
- Balance sufficient (include fees); open-order and rate limits not exceeded.
- Price band sanity for LIMIT (reject prices more than X percent from mid, default 20, configurable) to catch fat-finger.

## 4. Fill model

Inputs: latest valid `BookSnapshot` (taken from gateway state at decision time `t_decision`), simulated latency `L` (default **0 ms live paper, configurable**; backtests default to measured or chosen value), fee schedule.

### 4.1 Marketable orders (MARKET, or LIMIT crossing the book)
1. Choose the book state at `t_decision + L` (live paper: apply `L` by delaying evaluation and reading the book at that later time; if `L = 0` use the latest book).
2. Walk the opposite side from best to worse: for each level take `min(remaining, level_qty)` at that level's price. For a LIMIT, stop when the next level violates the limit price.
3. Each consumed level produces a **fill** (price, qty) with `liquidity = TAKER`.
4. MARKET: if book depth is insufficient, fill what is available and **reject/cancel the remainder** (status PARTIALLY_FILLED then CANCELED) - never invent liquidity. IOC: same for the unfilled remainder.
5. LIMIT GTC: any unfilled remainder becomes a resting order (status ACCEPTED/PARTIALLY_FILLED).

### 4.2 Resting limit orders (conservative model)
A LIMIT order that is not marketable at submission rests in the paper book and is evaluated on every **trade** event for its symbol.
- A BUY limit at price `P` fills only when a trade prints **strictly below** `P`; a SELL limit at `P` fills only when a trade prints **strictly above** `P`. A trade at exactly `P` (touch) does **not** fill, because queue position is unknown (conservative).
- Quote movement alone (the best ask dropping to or below `P`) does **not** fill a resting order, because displayed quotes can be pulled. Only prints count.
- Fill price is the order's limit price (no price-improvement credit); `liquidity = MAKER`.
- Fill quantity is capped by the through-trade quantity and the order's remaining quantity; a single trade can fill several resting orders in price priority, then time priority.
- A resting order that becomes marketable because the book moved is **not** converted to a taker fill; it waits for a qualifying print. (The backtester uses the same rule so results are comparable.)
- This model is deliberately pessimistic. The assumption is stated in the README and in the UI help text.

### 4.3 Slippage preview
`POST /orders/preview` runs the same `walk_market` logic on the current book without placing an order and returns average price, slippage vs mid in bps, levels used, fees, and any shortfall.

### 4.4 Determinism
Given the same sequence of book states, orders and latency settings, the engine produces identical fills. Fills record `book_epoch` and `book_seq` of the state used.

## 5. Fees

- Configurable schedule: maker bps, taker bps, fee asset rule (default: charged in quote asset on the notional). Default **10 bps** for both (verify against the venue's current standard spot schedule when you set defaults).
- `fee = notional * bps / 10_000`, rounded **up** to the asset's smallest unit (fees never round in the user's favor).
- Quantities and prices rounded only where the venue rules require; never round intermediate values.

## 6. Ledger postings

Notation: amounts are signed; debit positive, credit negative; each transaction balances per asset.

**BUY fill** of qty `q` at price `p`, fee `f` (quote asset):
| Ledger account | Asset | Amount |
|---|---|---|
| ASSET:BASE (user) | BASE | +q |
| CASH:QUOTE (user) | QUOTE | -(q*p) |
| CASH:QUOTE (user) | QUOTE | -f |
| FEES:QUOTE (system) | QUOTE | +f |
| MARKET:BASE (system counter-account) | BASE | -q |
| MARKET:QUOTE (system counter-account) | QUOTE | +(q*p) |

SELL is the mirror image. The system `MARKET` counter-accounts make each transaction balance per asset. (Alternative: model user-only postings with a conservation check; choose one in implementation and document it in an ADR.)

**Reconciliation job (nightly and on demand):** recompute balances from entries and compare with the cached balances and with positions; recompute positions from fills; alarm on any difference; assert global `SUM(amount) = 0` per asset.

## 7. Concurrency and idempotency

- **Single writer per account:** commands for an account are serialized (keyed executor / mailbox). Different accounts run in parallel.
- Each command is one DB transaction: validate -> insert order -> compute fills -> insert fills and ledger entries -> update caches -> commit. Only after commit are events published to WebSocket.
- Idempotency: unique `(user_id, idempotency_key)`; request body hash stored to detect mismatches.
- Cancel is idempotent: canceling a terminal order returns its current state.
- Balance-affecting transactions use `SERIALIZABLE` or `SELECT ... FOR UPDATE` on the account row, in addition to the single-writer rule (defense in depth).
- Resting-order evaluation runs on the same per-account writer to avoid races between cancel and fill.

## 8. Portfolio and P&L

- Position average cost: weighted average (v1). Realized P&L on reduction = `(fill_price - avg_cost) * qty_closed - fees_attributed`.
- Unrealized P&L marked at **mid** by default, with a toggle for liquidation value (walk the book for the full position) to avoid flattering marks.
- Equity = cash + sum(position qty * mark). Equity history sampled (e.g. each minute) into a time-series table.

## 9. Golden vectors (shared with Python)

`testdata/fill-vectors/*.json`:
```json
{
  "name": "market_buy_walks_three_levels",
  "instrument": {"price_scale": 2, "qty_scale": 5},
  "book": {"asks": [[6432100, 30000], [6432220, 20000], [6432500, 100000]], "bids": []},
  "order": {"side": "BUY", "type": "MARKET", "qty": 50000},
  "fee": {"taker_bps": 10},
  "expected": {
    "fills": [[6432100, 30000], [6432220, 20000]],
    "avg_price_units": "...", "fee_quote": "...", "status": "FILLED"
  }
}
```
Both implementations must pass every vector. Add a vector for every bug found.

## 10. Test checklist

- Walk across levels; insufficient depth; limit stops walking; IOC remainder; stale book rejection.
- Idempotent retries; same key different body; concurrent orders cannot overspend.
- Fees rounding; zero-fee schedule; tiny quantities.
- Resting limit: touch (no fill), through (fill), partial through-trade; cancel races.
- Reconciliation passes after random order sequences (property test).
- Isolation: user A cannot read/cancel user B's orders.

## 11. Out of scope for v1
Margin, shorting, stop orders, order modification (cancel/replace), multi-currency base, funding rates, borrow.
