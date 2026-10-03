# ADR-0005: Reuse the Rust book in Python via PyO3

**Status:** Accepted
**Date:** 2026-10-02

## Context
Backtests that use a different order-book implementation can diverge from live behavior. Re-implementing the book in Python/Numba would create two sources of truth.

## Decision
Expose the Rust book, metrics and event reader to Python via PyO3 built with maturin as a wheel. Keep the boundary coarse (batches of events with book state as Arrow/NumPy arrays). Version the wheel with the workspace and record it in every backtest. A parity test asserts the binding matches the Rust server on recorded days.

## Consequences
+ One implementation and strong parity guarantees. - Build complexity (Rust toolchain in CI), cross-boundary debugging; mitigated by pure-Rust crates tested independently.

## Alternatives considered
Numba re-implementation - simpler build but risks drift. gRPC to Rust per query - too slow for large backtests.
