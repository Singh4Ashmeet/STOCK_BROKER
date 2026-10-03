# ADR-0004: Optimized dense-window order book with an oracle

**Status:** Accepted
**Date:** 2026-10-02

## Context
Order-book updates are the hottest operation. A BTreeMap is simple but pays tree-walking and allocation costs. Correctness (goal 2) must not be sacrificed for speed.

## Decision
Build the optimized book from the start: dense array window around the mid, occupancy bitset, cached best prices, far-level fallback structure, incremental metrics. Keep a BTreeMap book as a **test oracle** only. Differential property tests assert equality after every operation. Publish an oracle vs optimized benchmark.

## Consequences
+ Large speedup and a strong portfolio story. - More complex code (re-centering, far levels) and more edge cases; mitigated by oracle tests, fuzzing and budgeted re-centering.

## Alternatives considered
BTreeMap only - simplest, slower. Sorted Vec around the top - faster than a tree but weaker for deep updates.
