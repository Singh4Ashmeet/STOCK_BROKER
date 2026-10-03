# ADR-0003: Fixed-point integers across all boundaries

**Status:** Accepted
**Date:** 2026-10-02

## Context
Floating-point numbers produce rounding errors that are unacceptable for prices, quantities and money, and can hide bugs. Parsing decimals into floats is also slower.

## Decision
Prices and quantities are int64 scaled by per-instrument scales (InstrumentSpec). REST uses decimal strings; Postgres uses NUMERIC(38,18); Java uses BigDecimal. Doubles are permitted only for derived analytics (mid, microprice, ratios) and never for balances, fills, or fees. JavaScript clients use integers when safe (instrument flag) and BigInt/strings otherwise.

## Consequences
+ Exactness, determinism, fast parsing. - Conversion code at the edges; scale metadata must travel with the data; analytics doubles must be clearly separated. Tests must verify round-trips.

## Alternatives considered
Floats - rejected. Decimal types everywhere (Rust rust_decimal) - slower in the hot path; used only at edges if needed.
