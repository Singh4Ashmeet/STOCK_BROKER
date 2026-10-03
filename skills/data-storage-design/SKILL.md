---
name: data-storage-design
description: Designing and operating the data layer: Postgres schema and migrations for users/ledger/orders, ClickHouse tables for ticks and candles, Redis key patterns and TTLs, the raw event log and Parquet compaction, partitioning, retention and backups. Use this skill whenever adding tables or migrations, writing queries, designing time-series storage, choosing indexes, handling retention, building the compactor or loaders, or debugging data-layer performance.
---

# Data storage design

Schemas and formats: `docs/05-DATA-MODEL.md`. Decision: `docs/adr/ADR-0006-event-log-format.md`.

## Postgres (relational, ACID)
- Flyway versioned migrations (`V001__name.sql`); **never edit applied migrations**; one concern per migration; test migrations on a copy of realistic data.
- Types: UUID PKs, `timestamptz`, `NUMERIC(38,18)` money/qty, `citext` for emails, `CHECK` constraints for enums and positivity, FKs everywhere ownership matters.
- Every user-owned table has `user_id` and an index starting with `user_id`; partial indexes for hot subsets (open orders).
- **Ledger:** append-only, double-entry, balanced per transaction and asset; app role has no UPDATE/DELETE on ledger tables; derived caches are reconciled.
- Idempotency: `UNIQUE (user_id, idempotency_key)` plus stored request hash.
- Use `EXPLAIN (ANALYZE, BUFFERS)` for new queries; avoid N+1; keep transactions short; set statement and lock timeouts.
- Backups: nightly encrypted `pg_dump` off-host; restore test script; document RPO/RTO for the demo.

## ClickHouse (analytics)
- `MergeTree` with `ORDER BY (symbol, ts)` and monthly/daily partitions; `LowCardinality(String)` for symbol/venue; integer prices/qty (scale at query time); TTL for retention.
- Candles via materialized views or `AggregatingMergeTree`; avoid per-row inserts: **batch inserts** (thousands of rows) from Parquet/log loaders.
- Use ClickHouse only for historical/aggregate queries; the live path never queries it.
- Keep it in the `analytics` compose profile; the platform must still run (live + trading) without it, degrading historical charts gracefully.

## Redis (ephemeral)
Keys: `mkt:{symbol}:book|metrics` (latest state, throttled writes), `rl:*` (rate-limit buckets), `wsticket:{ticket}` (30 s TTL, `GETDEL` on use), `login:fail:*`, `idem:*`. Always set TTLs on non-state keys; namespace keys; don't store secrets; assume Redis can be flushed (system must recover; Postgres is authoritative).

## Event log (Rust writes)
- Length-delimited Protobuf `LogRecord` in zstd frames; one file per symbol per hour; header record with schema version + instrument spec; gap/resync markers recorded.
- Writer thread isolated from the hot path; if it lags, drop log records and count (live mode); offline recording mode may backpressure.
- Files closed and fsynced at rotation; atomic rename on completion (`.part` -> final) so compactors never read partial files.
- Readers support schema versions N and N-1.

## Parquet (research)
Partitioned `venue/symbol/date`, zstd, typed integer columns, row groups sized for scans; compaction job verifies row-count and checksum parity before deleting/marking source files; Polars/DuckDB read via predicate pushdown.

## Retention and volume
Measure messages/s and bytes after zstd in Phase 2; set retention accordingly (default 7 days raw local, ClickHouse TTLs per doc); keep curated small "golden days" for tests/demos; alert at 85 percent disk; the writer pauses log writing below a free-space threshold (live stream continues).

## Anti-patterns
Storing money as float; editing old migrations; deleting ledger rows; per-row ClickHouse inserts; unbounded Redis keys; querying ClickHouse on the live path; reading unfinished log files.

## Review checklist
- [ ] Migration is new, reversible by a follow-up, and tested
- [ ] Indexes support the new queries (EXPLAIN checked)
- [ ] `user_id` present and indexed on user-owned data
- [ ] Retention/TTL decided for any new data
- [ ] Docs (`05-DATA-MODEL.md`) updated
