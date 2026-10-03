# Data Model

## 1. Postgres (Java owns; Flyway migrations)

Conventions: `snake_case`; UUID primary keys (`gen_random_uuid()`); `created_at`/`updated_at` as `timestamptz`; money and quantities as `NUMERIC(38,18)`; every user-owned table has `user_id` and an index starting with `user_id`.

### 1.1 Identity

```sql
CREATE TABLE users (
  id            uuid PRIMARY KEY,
  email         citext UNIQUE NOT NULL,
  password_hash text NOT NULL,                -- Argon2id encoded string
  role          text NOT NULL DEFAULT 'USER' CHECK (role IN ('USER','ADMIN')),
  status        text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LOCKED','DISABLED')),
  email_verified boolean NOT NULL DEFAULT false,
  failed_logins int NOT NULL DEFAULT 0,
  locked_until  timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
  id          uuid PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  family_id   uuid NOT NULL,                  -- all rotations of one login share a family
  token_hash  bytea NOT NULL UNIQUE,          -- SHA-256 of the opaque token
  expires_at  timestamptz NOT NULL,
  used_at     timestamptz,                    -- set when rotated; reuse of a used token revokes the family
  revoked_at  timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  user_agent  text, ip inet
);
CREATE INDEX ON refresh_tokens (user_id);
CREATE INDEX ON refresh_tokens (family_id);
```

### 1.2 Ledger and trading

```sql
CREATE TABLE accounts (                       -- one paper account per user in v1 (table allows more)
  id         uuid PRIMARY KEY,
  user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name       text NOT NULL DEFAULT 'default',
  base_ccy   text NOT NULL DEFAULT 'USDT',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, name)
);

-- Double-entry: every business event writes balanced entries per asset.
CREATE TABLE ledger_accounts (                -- e.g. (account, 'CASH:USDT'), (account,'ASSET:BTC'), (system,'FEES:USDT'), (system,'EQUITY:USDT')
  id         uuid PRIMARY KEY,
  account_id uuid REFERENCES accounts(id),    -- NULL for system accounts
  code       text NOT NULL,
  asset      text NOT NULL,
  UNIQUE (account_id, code)
);

CREATE TABLE ledger_transactions (
  id          uuid PRIMARY KEY,
  account_id  uuid NOT NULL REFERENCES accounts(id),
  user_id     uuid NOT NULL,
  kind        text NOT NULL,                  -- DEPOSIT, FILL, FEE, ADJUSTMENT
  ref_type    text, ref_id uuid,              -- e.g. 'FILL', fill id
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (                 -- append-only; no UPDATE/DELETE grants for the app role
  id             bigserial PRIMARY KEY,
  transaction_id uuid NOT NULL REFERENCES ledger_transactions(id),
  ledger_account uuid NOT NULL REFERENCES ledger_accounts(id),
  asset          text NOT NULL,
  amount         numeric(38,18) NOT NULL,     -- positive = debit, negative = credit
  created_at     timestamptz NOT NULL DEFAULT now()
);
-- Invariant (enforced by deferred constraint trigger + tests): SUM(amount) per (transaction_id, asset) = 0.

CREATE TABLE orders (
  id            uuid PRIMARY KEY,
  user_id       uuid NOT NULL,
  account_id    uuid NOT NULL REFERENCES accounts(id),
  symbol        text NOT NULL,
  side          text NOT NULL CHECK (side IN ('BUY','SELL')),
  type          text NOT NULL CHECK (type IN ('MARKET','LIMIT')),
  tif           text NOT NULL DEFAULT 'GTC' CHECK (tif IN ('GTC','IOC')),
  qty           numeric(38,18) NOT NULL CHECK (qty > 0),
  limit_price   numeric(38,18),
  status        text NOT NULL CHECK (status IN ('NEW','ACCEPTED','PARTIALLY_FILLED','FILLED','CANCELED','REJECTED')),
  filled_qty    numeric(38,18) NOT NULL DEFAULT 0,
  reject_reason text,
  idempotency_key text NOT NULL,
  request_hash  bytea NOT NULL,               -- detects same key with different body
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, idempotency_key)
);
CREATE INDEX ON orders (user_id, created_at DESC);
CREATE INDEX ON orders (symbol, status) WHERE status IN ('ACCEPTED','PARTIALLY_FILLED');

CREATE TABLE fills (
  id           uuid PRIMARY KEY,
  order_id     uuid NOT NULL REFERENCES orders(id),
  user_id      uuid NOT NULL,
  account_id   uuid NOT NULL,
  symbol       text NOT NULL,
  side         text NOT NULL,
  price        numeric(38,18) NOT NULL,
  qty          numeric(38,18) NOT NULL,
  fee_asset    text NOT NULL,
  fee_amount   numeric(38,18) NOT NULL,
  liquidity    text NOT NULL CHECK (liquidity IN ('TAKER','MAKER')),
  book_epoch   bigint, book_seq bigint,      -- the market state used for this fill (auditability)
  filled_at    timestamptz NOT NULL
);
CREATE INDEX ON fills (user_id, filled_at DESC);

CREATE TABLE positions (                      -- derived cache; rebuildable from fills/ledger
  account_id uuid NOT NULL, symbol text NOT NULL,
  qty numeric(38,18) NOT NULL, avg_cost numeric(38,18) NOT NULL, realized_pnl numeric(38,18) NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, symbol)
);
```

Balances are **derived**: `SELECT asset, SUM(amount) FROM ledger_entries JOIN ledger_accounts ... WHERE account = ? GROUP BY asset`. A cached `balances` table (updated in the same transaction) is allowed for speed; a nightly reconciliation job compares cache vs ledger and alarms on any difference.

### 1.3 Product features

```sql
CREATE TABLE watchlists (id uuid PRIMARY KEY, user_id uuid NOT NULL, name text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(user_id,name));
CREATE TABLE watchlist_items (watchlist_id uuid REFERENCES watchlists(id) ON DELETE CASCADE, symbol text NOT NULL, position int NOT NULL, PRIMARY KEY (watchlist_id, symbol));

CREATE TABLE alerts (
  id uuid PRIMARY KEY, user_id uuid NOT NULL, symbol text NOT NULL,
  metric text NOT NULL CHECK (metric IN ('PRICE','SPREAD','IMBALANCE')),
  op text NOT NULL CHECK (op IN ('GT','LT')), threshold numeric(38,18) NOT NULL,
  status text NOT NULL DEFAULT 'ACTIVE', cooldown_s int NOT NULL DEFAULT 60,
  last_triggered_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE backtest_runs (
  id uuid PRIMARY KEY, user_id uuid NOT NULL, strategy text NOT NULL, params jsonb NOT NULL,
  symbol text NOT NULL, data_from timestamptz NOT NULL, data_to timestamptz NOT NULL,
  fee_model jsonb NOT NULL, latency_ms int NOT NULL,
  status text NOT NULL, error text,
  code_version text, pybook_version text, seed bigint,
  summary jsonb, artifact_path text,
  created_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz
);

CREATE TABLE audit_log (
  id bigserial PRIMARY KEY, at timestamptz NOT NULL DEFAULT now(),
  user_id uuid, action text NOT NULL, ip inet, request_id text, detail jsonb
);
```

### 1.4 Data protection rules
- `password_hash` and tokens are never logged or returned.
- App DB role: no `UPDATE`/`DELETE` on `ledger_entries` and `ledger_transactions`.
- Optional: Postgres row-level security keyed on `current_setting('app.user_id')` as defense in depth.
- Deleting a user cascades only non-ledger personal data; ledger rows are anonymized (user_id kept as opaque id) if retention is required. Document the decision when implementing account deletion.

## 2. ClickHouse (analytics; fed from event logs/Parquet)

```sql
CREATE TABLE trades (
  venue LowCardinality(String), symbol LowCardinality(String),
  ts DateTime64(9, 'UTC'), recv_ts DateTime64(9, 'UTC'),
  price Int64, qty Int64, aggressor Enum8('BUY'=1,'SELL'=2),
  trade_id String
) ENGINE = MergeTree
PARTITION BY toYYYYMMDD(ts)
ORDER BY (symbol, ts)
TTL toDateTime(ts) + INTERVAL 30 DAY;

CREATE TABLE metrics_1s (
  symbol LowCardinality(String), ts DateTime('UTC'),
  best_bid Int64, best_ask Int64, spread Int64,
  mid Float64, microprice Float64, imbalance Float64, vwap Float64, tfi Float64,
  updates UInt32
) ENGINE = MergeTree PARTITION BY toYYYYMM(ts) ORDER BY (symbol, ts);

-- Candles are built with materialized views / AggregatingMergeTree from trades (1s, 1m, 5m, 1h, 1d).
```

Prices stay integers in ClickHouse; scale is applied at query time using instrument metadata. Ingestion: batch insert from compacted Parquet (e.g. `INSERT ... SELECT * FROM file(...)`) or a small loader; optionally via a bus later.

## 3. Redis keys

| Key | Value | TTL |
|---|---|---|
| `mkt:{symbol}:book` | Latest snapshot (serialized) | none (overwritten, throttled) |
| `mkt:{symbol}:metrics` | Latest metrics | none |
| `rl:{user_id}:{route}` / `rl:ip:{ip}:{route}` | Rate-limit bucket state | window |
| `wsticket:{ticket}` | `user_id` | 30 s, deleted on use |
| `login:fail:{email_hash}` | Failure counter | 15 min |
| `idem:{user_id}:{key}` | Optional fast duplicate check (Postgres is authoritative) | 24 h |

## 4. Raw event log (Rust writes)

**Path:** `data/raw/venue=BINANCE/symbol=BTC-USDT/date=YYYY-MM-DD/hour=HH/part-NNNN.pbz`

**Format:** sequence of `[u32 length][LogRecord protobuf]` compressed in zstd frames (frame per few thousand records so files are seekable). Each file begins with a header record: schema version, venue, symbol, instrument spec, start time, writer version, host clock info.

```
message LogRecord {
  uint32 schema_version = 1;
  int64 recv_ts_ns = 2;
  int64 exchange_ts_ns = 3;
  RecordKind kind = 4;      // TRADE, BOOK_SNAPSHOT, BOOK_DELTA, GAP_MARKER, RESYNC_MARKER, RAW_FRAME
  bytes payload = 5;        // serialized market.v1 message or raw venue frame
}
```

Markers (gap, resync, reconnect) are recorded so replay and backtests can see exactly where data is unreliable.

## 5. Parquet (research)

**Path:** `data/parquet/{trades|book_events|metrics}/venue=.../symbol=.../date=YYYY-MM-DD/part-*.parquet` (zstd). Columns mirror the log record with typed fields (ints for price/qty/ts). Compaction runs on closed hour files; originals are kept until verified (row-count and checksum parity).

## 6. Backtest artifacts

```
runs/{run_id}/run.json         # commit, wheel version, params, seed, data range, host, durations
runs/{run_id}/summary.json     # metrics and cost breakdown
runs/{run_id}/equity.parquet
runs/{run_id}/trades.parquet
runs/{run_id}/report.html      # optional static report
```

## 7. Migrations and compatibility
- Flyway versioned migrations only (`V001__...sql`); never edit applied migrations.
- Event-log `schema_version` increments on any change; readers support at least N and N-1.
- Parquet schemas are versioned in the path or a metadata field.
