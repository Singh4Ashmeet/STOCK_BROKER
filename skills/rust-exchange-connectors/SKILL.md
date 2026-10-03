---
name: rust-exchange-connectors
description: Building robust exchange connectors in Rust: WebSocket lifecycle, reconnect with backoff and jitter, heartbeats, rate limits, normalization into internal events, timestamping, connector trait design, and handling untrusted exchange input. Use this skill whenever adding or modifying a connector (Binance, Coinbase, Kraken), touching WebSocket/REST client code in the `connectors` crate, handling reconnects/rate limits, or recording raw frames.
---

# Rust exchange connectors

Spec context: `docs/02-TRD.md` section 5.3, `docs/06-ORDER-BOOK-SPEC.md`. **Exchange limits and message formats change; verify against current official docs and record the verification date in the connector's module docs.**

## Responsibilities
Connect, subscribe, keep alive, reconnect, stamp `recv_ts`, hand raw bytes onward, fetch snapshots, normalize to internal events, expose instrument metadata. No business logic.

## Trait design
One trait per venue capability; implementations are plugins selected by config. Normalization writes into a caller-provided reusable buffer (no allocation). Symbol mapping (venue symbol <-> canonical `VENUE:BASE-QUOTE`) happens only here; use interned symbol ids internally.

## WebSocket lifecycle
- **Connect** with TLS (rustls), timeouts on connect and on first message.
- **Heartbeats:** respond to server pings with pongs promptly; send client pings if the venue requires; detect silence (no frame for X seconds) and reconnect.
- **Proactive reconnect:** many venues cap connection lifetime (for Binance historically about 24 h - verify). Reconnect gracefully before expiry, with overlap if needed to avoid gaps, then resync.
- **Reconnect policy:** exponential backoff with full jitter, capped (e.g. 250 ms to 30 s), reset after stable period; log at warn on state transitions only. Every reconnect increments `epoch` and triggers resync (never resume without resync).
- **Subscriptions:** respect max streams per connection and message-rate limits on control messages (verify); batch subscribe.
- **Backpressure:** the socket reader never blocks on downstream; if the ring buffer is full, count and handle per policy (drop-oldest with `Invalid` marking for the affected symbol).

## REST usage
- Snapshot fetches are rate limited and weighty; add a global limiter and a concurrency cap; jittered retry; honor `Retry-After`/ban responses (never hammer).
- Use market-data-only endpoints where available and appropriate (verify availability by region).
- Timeouts everywhere; classify errors (retryable vs fatal).

## Normalization
- Parse into integer ticks/lots using instrument scales; reject frames whose precision exceeds the scale (count and log sampled).
- Convert venue ms timestamps to ns (`ms * 1_000_000`) and keep the original precision note in docs; stamp `recv_ts` from a monotonic-aligned wall clock source at the earliest point.
- Map venue side/aggressor flags carefully (for Binance trade streams the "buyer is market maker" flag inverts to the aggressor side - verify with a test using known examples).
- Unknown message types: count and ignore; never crash.

## Untrusted input rules
Treat every frame as hostile: bounded sizes, bounded depth of JSON nesting, no unbounded allocation, no `unwrap`. Fuzz the parser and keep raw-frame fixtures for regressions.

## Recording
Optionally record raw frames (`RAW_FRAME` records) in the event log for parser regression tests and debugging; make it a config flag (adds volume).

## Testing
- Mock WebSocket server that replays recorded frames and injects: delays, dropped connections, malformed frames, duplicate frames, out-of-order frames, server pings, close codes.
- Reconnect/backoff unit tests with a fake clock.
- Snapshot fetcher tests with simulated rate limit responses.
- Symbol mapping tests for all configured symbols.

## Observability
Metrics: `feed_reconnect_total`, `feed_parse_error_total{kind}`, `feed_frames_total`, `feed_ws_lag_seconds`, `feed_snapshot_fetch_seconds`, `feed_rate_limited_total`; state transitions logged.

## Review checklist
- [ ] Limits/formats verified against current docs (date noted)
- [ ] Reconnect always bumps epoch and resyncs
- [ ] No allocation/panic on bad frames; fuzz target updated
- [ ] Rate limiter covers snapshot bursts
- [ ] Timestamps and aggressor mapping tested
