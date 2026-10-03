# ADR-0006: Raw event log as framed Protobuf + zstd, compacted to Parquet

**Status:** Accepted
**Date:** 2026-10-02

## Context
The feed needs cheap appends on the hot path and lossless replay; research needs columnar analytics. Parquet is poor for tiny appends; row-oriented framed logs are poor for analytics.

## Decision
Rust writes length-delimited Protobuf records in zstd frames, one file per symbol per hour, including markers for gaps/resyncs. A compactor converts closed hours to Parquet. The writer runs on its own thread; if it falls behind, log records are dropped with a counter and alarm rather than delaying the book (live service). A separate offline recording mode may use backpressure to guarantee completeness when capturing fixtures.

## Consequences
+ Fast, simple, replay-native logs; analytics-friendly Parquet. - Two formats and a compactor job; possible gaps in the log under extreme load (visible via markers/counters).

## Alternatives considered
Parquet only - slower appends. Kafka log - extra infrastructure. SQLite - slower for this pattern.
