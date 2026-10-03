# ADR-0002: Direct gRPC live path; message bus optional

**Status:** Accepted
**Date:** 2026-10-02

## Context
Real-time latency is goal 1. A message bus (Kafka/Redpanda) adds hops, operational weight, and RAM use on a laptop, and is not needed to feed a single Java consumer. Storage and replay do not need a bus either if Rust writes an event log.

## Decision
Rust streams live data directly to Java over gRPC server-streaming. The Rust service also writes a durable event log to files. A bus (Redpanda or NATS) is **optional** and off by default (compose profile `bus`); it may be added later for ClickHouse ingestion or multi-consumer fan-out. Note: this refines the earlier idea of putting the bus in the middle of the storage path; files are the source of truth.

## Consequences
+ Fewer hops and fewer moving parts on the live path. + Lighter local stack. - Only one live consumer type unless the broadcaster is extended (it supports multiple subscribers). - ClickHouse ingestion uses batch loads from Parquet/log files instead of streaming (acceptable for research and charts).

## Alternatives considered
Bus in the middle for everything - more realistic for large systems but slower and heavier. Shared memory/UDS - possible later optimization if the gRPC hop dominates.
