-- V001__initial_schema.sql
-- Initial database schema for Stock Broker API
-- See docs/05-DATA-MODEL.md for full specification

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "citext";

-- ============================================================
-- 1.1 Identity
-- ============================================================

CREATE TABLE users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email         citext UNIQUE NOT NULL,
    password_hash text NOT NULL,
    role          text NOT NULL DEFAULT 'USER' CHECK (role IN ('USER','ADMIN')),
    status        text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LOCKED','DISABLED')),
    email_verified boolean NOT NULL DEFAULT false,
    failed_logins int NOT NULL DEFAULT 0,
    locked_until  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    family_id   uuid NOT NULL,
    token_hash  bytea NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    user_agent  text,
    ip          inet
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

-- ============================================================
-- 1.2 Ledger and Trading
-- ============================================================

CREATE TABLE accounts (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       text NOT NULL DEFAULT 'default',
    base_ccy   text NOT NULL DEFAULT 'USDT',
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, name)
);

-- Double-entry ledger accounts
CREATE TABLE ledger_accounts (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid REFERENCES accounts(id),
    code       text NOT NULL,
    asset      text NOT NULL,
    UNIQUE (account_id, code)
);

CREATE TABLE ledger_transactions (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  uuid NOT NULL REFERENCES accounts(id),
    user_id     uuid NOT NULL REFERENCES users(id),
    kind        text NOT NULL CHECK (kind IN ('DEPOSIT','FILL','FEE','ADJUSTMENT')),
    ref_type    text,
    ref_id      uuid,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id             bigserial PRIMARY KEY,
    transaction_id uuid NOT NULL REFERENCES ledger_transactions(id),
    ledger_account uuid NOT NULL REFERENCES ledger_accounts(id),
    asset          text NOT NULL,
    amount         numeric(38,18) NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries (transaction_id);
CREATE INDEX idx_ledger_entries_ledger_account ON ledger_entries (ledger_account);
CREATE INDEX idx_ledger_transactions_account_id ON ledger_transactions (account_id);
CREATE INDEX idx_ledger_transactions_user_id ON ledger_transactions (user_id);

-- Orders
CREATE TABLE orders (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           uuid NOT NULL REFERENCES users(id),
    account_id        uuid NOT NULL REFERENCES accounts(id),
    symbol            text NOT NULL,
    side              text NOT NULL CHECK (side IN ('BUY','SELL')),
    type              text NOT NULL CHECK (type IN ('MARKET','LIMIT')),
    tif               text NOT NULL DEFAULT 'GTC' CHECK (tif IN ('GTC','IOC')),
    qty               numeric(38,18) NOT NULL CHECK (qty > 0),
    limit_price       numeric(38,18),
    status            text NOT NULL CHECK (status IN ('NEW','ACCEPTED','PARTIALLY_FILLED','FILLED','CANCELED','REJECTED')),
    filled_qty        numeric(38,18) NOT NULL DEFAULT 0,
    avg_fill_price    numeric(38,18),
    reject_reason     text,
    idempotency_key   text NOT NULL,
    request_hash      bytea NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, idempotency_key)
);

CREATE INDEX idx_orders_user_id_created_at ON orders (user_id, created_at DESC);
CREATE INDEX idx_orders_symbol_status ON orders (symbol, status) WHERE status IN ('ACCEPTED','PARTIALLY_FILLED');
CREATE INDEX idx_orders_account_id ON orders (account_id);

-- Fills
CREATE TABLE fills (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id      uuid NOT NULL REFERENCES orders(id),
    user_id       uuid NOT NULL REFERENCES users(id),
    account_id    uuid NOT NULL REFERENCES accounts(id),
    symbol        text NOT NULL,
    side          text NOT NULL CHECK (side IN ('BUY','SELL')),
    price         numeric(38,18) NOT NULL,
    qty           numeric(38,18) NOT NULL,
    fee_asset     text NOT NULL,
    fee_amount    numeric(38,18) NOT NULL,
    liquidity     text NOT NULL CHECK (liquidity IN ('TAKER','MAKER')),
    book_epoch    bigint,
    book_seq      bigint,
    filled_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_fills_user_id_filled_at ON fills (user_id, filled_at DESC);
CREATE INDEX idx_fills_order_id ON fills (order_id);
CREATE INDEX idx_fills_symbol ON fills (symbol);

-- Positions (derived cache)
CREATE TABLE positions (
    account_id   uuid NOT NULL,
    symbol       text NOT NULL,
    qty          numeric(38,18) NOT NULL DEFAULT 0,
    avg_cost     numeric(38,18) NOT NULL DEFAULT 0,
    realized_pnl numeric(38,18) NOT NULL DEFAULT 0,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, symbol)
);

-- ============================================================
-- 1.3 Product Features
-- ============================================================

CREATE TABLE watchlists (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, name)
);

CREATE TABLE watchlist_items (
    watchlist_id uuid NOT NULL REFERENCES watchlists(id) ON DELETE CASCADE,
    symbol       text NOT NULL,
    position     int NOT NULL,
    PRIMARY KEY (watchlist_id, symbol)
);

CREATE TABLE alerts (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    symbol            text NOT NULL,
    metric            text NOT NULL CHECK (metric IN ('PRICE','SPREAD','IMBALANCE')),
    op                text NOT NULL CHECK (op IN ('GT','LT')),
    threshold         numeric(38,18) NOT NULL,
    status            text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','TRIGGERED','DISABLED')),
    cooldown_s        int NOT NULL DEFAULT 60,
    last_triggered_at timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_alerts_user_id ON alerts (user_id);
CREATE INDEX idx_alerts_symbol_metric ON alerts (symbol, metric);

CREATE TABLE backtest_runs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    strategy        text NOT NULL,
    params          jsonb NOT NULL,
    symbol          text NOT NULL,
    data_from       timestamptz NOT NULL,
    data_to         timestamptz NOT NULL,
    fee_model       jsonb NOT NULL,
    latency_ms      int NOT NULL,
    status          text NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    error           text,
    code_version    text,
    pybook_version  text,
    seed            bigint,
    summary         jsonb,
    artifact_path   text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    finished_at     timestamptz
);

CREATE INDEX idx_backtest_runs_user_id ON backtest_runs (user_id);
CREATE INDEX idx_backtest_runs_status ON backtest_runs (status);

CREATE TABLE audit_log (
    id         bigserial PRIMARY KEY,
    at         timestamptz NOT NULL DEFAULT now(),
    user_id    uuid,
    action     text NOT NULL,
    ip         inet,
    request_id text,
    detail     jsonb
);

CREATE INDEX idx_audit_log_user_id ON audit_log (user_id);
CREATE INDEX idx_audit_log_at ON audit_log (at DESC);
CREATE INDEX idx_audit_log_request_id ON audit_log (request_id);

-- ============================================================
-- Updated at trigger function
-- ============================================================

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_accounts_updated_at
    BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_orders_updated_at
    BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_positions_updated_at
    BEFORE UPDATE ON positions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();