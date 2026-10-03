# API Specification

Three interfaces: **gRPC** (Rust to Java/Python), **REST** (client to Java), **WebSocket** (Java to client). The gRPC contract is `proto/stockbroker/market/v1/market.proto`.

## 1. Numeric and time conventions

| Concept | gRPC | REST | WebSocket (v1) |
|---|---|---|---|
| Price | int64, scale from instrument | decimal **string** | integer in price units (scale from `/instruments`) |
| Quantity | int64, scale from instrument | decimal **string** | integer in qty units |
| Money (balances, P&L) | not used | decimal string | decimal string |
| Derived ratios (imbalance, microprice) | double | JSON number | JSON number |
| Time | int64 ns UTC | RFC 3339 UTC string (ms or us precision) | int64 ns as **string** if above 2^53, else number; use `ts_ms` helpers client-side |
| IDs | string/uuid | UUID string | UUID string |

JavaScript safe integer limit is 2^53. For WS prices/quantities the gateway sends integers; if any value could exceed 2^53 for an instrument, that instrument's WS fields are sent as strings and the client uses BigInt. The `/instruments` response includes `ws_numeric_safe: true|false` per instrument.

Symbols are canonical: `VENUE:BASE-QUOTE`, for example `BINANCE:BTC-USDT`.

## 2. REST API (Java) - `/api/v1`

**General:** JSON; `Authorization: Bearer <access_token>`; errors as `application/problem+json` (`type`, `title`, `status`, `detail`, `instance`, plus `code` and optional `errors[]`); cursor pagination (`?limit=50&cursor=...` returning `next_cursor`); `X-Request-Id` accepted/returned; rate-limit headers (`RateLimit-Limit`, `RateLimit-Remaining`, `Retry-After` on 429).

### 2.1 Auth

| Method | Path | Notes |
|---|---|---|
| POST | `/auth/register` | `{email, password}`; password policy: min 12 chars, check against breached-password list if feasible |
| POST | `/auth/login` | Returns `{access_token, expires_in}`; sets refresh cookie (via Next.js) or returns refresh token in body for non-browser clients |
| POST | `/auth/refresh` | Rotates refresh token; reuse of an old token revokes the family |
| POST | `/auth/logout` | Revokes current refresh token family |
| GET | `/me` | Profile and role |
| POST | `/ws/ticket` | Returns `{ticket, expires_in: 30}` single use |

### 2.2 Market (read-only, authenticated)

| Method | Path | Notes |
|---|---|---|
| GET | `/instruments` | List of InstrumentSpec (scales, tick, lot) |
| GET | `/market/{symbol}/snapshot?depth=20` | Latest book snapshot with `epoch/seq`, `book_valid` |
| GET | `/market/{symbol}/metrics` | Latest metrics |
| GET | `/market/{symbol}/trades?limit=100` | Recent trades |
| GET | `/market/{symbol}/candles?interval=1m&from=&to=` | From ClickHouse; intervals 1s,1m,5m,1h,1d |

### 2.3 Trading and portfolio

| Method | Path | Notes |
|---|---|---|
| POST | `/orders` | **Requires `Idempotency-Key`.** Body: `{symbol, side, type: MARKET|LIMIT, qty, limit_price?, tif: GTC|IOC}` |
| GET | `/orders?status=&symbol=` | Paginated |
| GET | `/orders/{id}` | |
| DELETE | `/orders/{id}` | Cancel; idempotent |
| GET | `/fills` | Paginated |
| GET | `/portfolio` | Cash, positions, equity, realized and unrealized P&L at the current mid/mark |
| GET | `/ledger?from=&to=` | Paginated ledger entries |
| POST | `/orders/preview` | Slippage preview: walks the current book without placing an order |
| GET/POST/DELETE | `/watchlists`, `/watchlists/{id}/items` | |
| GET/POST/PATCH/DELETE | `/alerts` | Price, spread, imbalance thresholds |

Order response example:
```json
{
  "id": "6f1c...",
  "symbol": "BINANCE:BTC-USDT",
  "side": "BUY",
  "type": "MARKET",
  "status": "FILLED",
  "qty": "0.50000",
  "filled_qty": "0.50000",
  "avg_price": "64321.47",
  "fees": {"asset": "USDT", "amount": "32.16"},
  "created_at": "2026-10-02T12:00:00.123Z",
  "fills": [{"price": "64321.00", "qty": "0.30000"}, {"price": "64322.20", "qty": "0.20000"}]
}
```

### 2.4 Research

| Method | Path | Notes |
|---|---|---|
| GET | `/strategies` | Available strategies and parameter schemas |
| POST | `/backtests` | `{strategy, params, symbol, from, to, fee_model, latency_ms}` returns `202` with run id |
| GET | `/backtests`, `/backtests/{id}` | Status, parameters, code/wheel version |
| GET | `/backtests/{id}/result` | Metrics, cost breakdown, equity curve, trade log (paged) |

### 2.5 Admin and system

| Method | Path | Notes |
|---|---|---|
| GET | `/system/health` | Public minimal liveness; detailed version requires ADMIN |
| GET | `/system/latency` | ADMIN: per-stage histogram summaries and resync counts |
| GET | `/actuator/prometheus` | Internal network only |

### 2.6 Error codes (non-exhaustive)

`AUTH_INVALID_CREDENTIALS`, `AUTH_TOKEN_EXPIRED`, `AUTH_REFRESH_REUSED`, `RATE_LIMITED`, `VALIDATION_FAILED`, `ORDER_INSUFFICIENT_FUNDS`, `ORDER_BOOK_INVALID` (market data not valid, cannot fill), `ORDER_DUPLICATE_KEY_MISMATCH` (same key, different body), `INSTRUMENT_NOT_FOUND`, `BACKTEST_DATA_GAP`.

## 3. WebSocket protocol (Java) - `/ws/v1`

Connect: `wss://host/ws/v1?ticket=<ticket>`. Tickets are single-use and expire in 30 s. Server checks the `Origin` header against an allowlist. JSON text frames. Max frame size 64 KB inbound.

### 3.1 Client to server

```json
{"op":"subscribe","channel":"book","symbol":"BINANCE:BTC-USDT","depth":20}
{"op":"subscribe","channel":"trades","symbol":"BINANCE:BTC-USDT"}
{"op":"subscribe","channel":"metrics","symbol":"BINANCE:BTC-USDT"}
{"op":"subscribe","channel":"orders"}
{"op":"subscribe","channel":"portfolio"}
{"op":"unsubscribe","channel":"book","symbol":"BINANCE:BTC-USDT"}
{"op":"resync","channel":"book","symbol":"BINANCE:BTC-USDT"}
{"op":"ping","id":"123"}
```

### 3.2 Server to client

Envelope: `{"t":"<type>","ch":"<channel>","s":"<symbol>","ep":<epoch>,"sq":<seq>,"tm":{"x":<exchange_ts_ns>,"r":<recv_ts_ns>,"p":<rust_publish_ts_ns>,"g":<gateway_send_ts_ns>},"d":{...}}`

| `t` | Meaning | `d` |
|---|---|---|
| `book.snap` | Full book | `{"b":[[price,qty],...],"a":[[price,qty],...]}` |
| `book.delta` | Absolute quantities (qty 0 removes) | same shape |
| `trade` | Trade | `{"p":price,"q":qty,"side":"B"|"S","id":"..."}` |
| `metrics` | Derived metrics | `{"bb":...,"ba":...,"spr":...,"mid":...,"mp":...,"imb":...,"vwap":...,"tfi":...,"valid":true}` |
| `order` | Order update (private) | Order object |
| `portfolio` | Portfolio delta (private) | Summary |
| `resync` | Client must discard state for the symbol and expect a snapshot | `{"reason":"epoch_change|gap|lagging|upstream_down"}` |
| `error` | Protocol/limit error | `{"code":"...","message":"..."}` |
| `pong` | Reply to ping | `{"id":"123"}` |

Rules:
- First message per subscription is always a snapshot.
- Within an `epoch`, `sq` increases by exactly 1; any other value means the client must send `resync`.
- If the client lags (queue full), the server conflates: drops queued deltas, sends `resync` then a snapshot with the new epoch.
- Server sends WebSocket ping frames every 20 s; idle sessions beyond 60 s without pong are closed.
- Limits (defaults): 5 sessions per user, 20 subscriptions per session, 20 inbound messages per second.

### 3.3 Versioning
Breaking changes use `/ws/v2`. v2 may use binary Protobuf frames (see ADR-0010).

## 4. gRPC (Rust)

See `proto/stockbroker/market/v1/market.proto`. Operational rules:
- Streams are server-streaming; the first message per symbol is a snapshot.
- Clients must handle `epoch` changes and `seq` gaps (resubscribe or call `GetSnapshot`).
- Default per-subscriber queue: 1,024 messages per symbol; overflow triggers conflation.
- Keepalive: 20 s pings; deadlines on unary calls (2 s); reflection only in dev.
- Authentication between services: mTLS or a shared service token on loopback/private network (document choice in ADR when deployed beyond one host).

## 5. Python job API (internal)

| Method | Path | Notes |
|---|---|---|
| POST | `/jobs` | Backtest request; returns `{job_id}` |
| GET | `/jobs/{id}` | `QUEUED|RUNNING|SUCCEEDED|FAILED`, progress |
| GET | `/jobs/{id}/result` | Result manifest and artifact paths |
| GET | `/healthz` | Liveness |

Authenticated with a service token; not exposed publicly.

## 6. Compatibility and change policy

- REST: additive changes only within `/v1`; deprecations announced in the changelog; removal requires `/v2`.
- Proto: `buf breaking` must pass; never reuse field numbers.
- WebSocket: unknown fields must be ignored by clients.
