# ADR-0010: JSON over WebSocket for v1

**Status:** Accepted
**Date:** 2026-10-02

## Context
Browsers parse JSON natively and it is easy to debug. Binary Protobuf could reduce bandwidth and parse cost but adds tooling and complexity.

## Decision
Use compact JSON envelopes (short keys, integer prices and quantities) over WebSocket in v1. Design the envelope so a binary Protobuf encoding can replace it in a future /ws/v2. Measure parse cost in the browser worker; if it dominates, run the v2 experiment and publish the comparison.

## Consequences
+ Fast to build and debug; transparent in dev tools. - Higher CPU and bandwidth than binary; mitigated by the Web Worker and conflation.

## Alternatives considered
Binary Protobuf from the start - faster but slower to iterate. MessagePack - middle ground; revisit with benchmarks.
