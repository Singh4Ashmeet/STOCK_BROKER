-- infra/clickhouse/init/01-init.sql
-- ClickHouse initialization: create database, tables, and low-privilege user
-- This runs on first startup of the clickhouse container

-- Create database
CREATE DATABASE IF NOT EXISTS stockbroker;

-- Create tables as specified in docs/05-DATA-MODEL.md section 2

-- trades table
CREATE TABLE IF NOT EXISTS stockbroker.trades (
    venue LowCardinality(String),
    symbol LowCardinality(String),
    ts DateTime64(9, 'UTC'),
    recv_ts DateTime64(9, 'UTC'),
    price Int64,
    qty Int64,
    aggressor Enum8('BUY' = 1, 'SELL' = 2),
    trade_id String
) ENGINE = MergeTree
PARTITION BY toYYYYMMDD(ts)
ORDER BY (symbol, ts)
TTL toDateTime(ts) + INTERVAL 30 DAY
SETTINGS index_granularity = 8192;

-- metrics_1s table
CREATE TABLE IF NOT EXISTS stockbroker.metrics_1s (
    symbol LowCardinality(String),
    ts DateTime('UTC'),
    best_bid Int64,
    best_ask Int64,
    spread Int64,
    mid Float64,
    microprice Float64,
    imbalance Float64,
    vwap Float64,
    tfi Float64,
    updates UInt32
) ENGINE = MergeTree
PARTITION BY toYYYYMM(ts)
ORDER BY (symbol, ts)
SETTINGS index_granularity = 8192;

-- Create low-privilege user for application access
-- This user will be used by the research/service layer to read data
-- Password is set via CLICKHOUSE_PASSWORD env var for the default user
-- We create a dedicated read-only user here

-- Note: ClickHouse user management via SQL requires the default user to have
-- access management enabled (CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1)
-- The sb_clickhouse_user is created with password from env

-- Create read-only role for applications
CREATE ROLE IF NOT EXISTS sb_ro;

-- Grant read access to stockbroker database
GRANT SELECT ON stockbroker.* TO sb_ro;

-- Create application user (password from env var SB_CLICKHOUSE_APP_PASSWORD)
-- This is done via users.xml config normally, but we can also do:
-- CREATE USER IF NOT EXISTS sb_app IDENTIFIED WITH sha256_password BY '${SB_CLICKHOUSE_APP_PASSWORD}';
-- GRANT sb_ro TO sb_app;

-- Note: Candle materialized views (1s, 1m, 5m, 1h, 1d) come later in Phase 2+
-- They will be created as materialized views on the trades table using
-- AggregatingMergeTree or materialized view to pre-aggregated tables.

-- Verify tables created
-- SELECT name, engine, partition_key, sorting_key, ttl FROM system.tables WHERE database = 'stockbroker';