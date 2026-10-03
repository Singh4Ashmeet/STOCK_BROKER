---
name: testing-and-quality
description: Test strategy and techniques for this polyglot project: property-based and differential testing, golden vectors, deterministic replay and parity tests, fuzzing, fault injection, soak and load tests, contract tests, E2E tests, CI gates and benchmark regression checks. Use this skill whenever adding or reviewing tests, setting up CI checks, investigating flaky tests, defining acceptance tests for a feature, or when a task says "make sure it works" or "add tests".
---

# Testing and quality

Full plan: `docs/10-TESTING-STRATEGY.md`. Tests are how goals 2 and 3 are *earned*.

## Principles
1. Lowest effective level; add integration tests for boundary risks.
2. **Differential/property tests** wherever a simple reference exists (book vs oracle, fills vs vectors, ledger vs recomputation).
3. **Deterministic replay** is the main integration technique; recorded fixtures + hashes.
4. Every bug gets a regression test (fixture or golden vector).
5. No flaky tests: quarantine within a day, fix within a week; no sleeps - use fake clocks, barriers, and explicit synchronization.

## Techniques cheat-sheet
| Technique | Where | Tool |
|---|---|---|
| Property tests with shrinking | Rust book/metrics, Java ledger, Python engine | proptest, jqwik, Hypothesis |
| Differential testing | optimized vs oracle book | proptest |
| Golden vectors | fill/fee model across Java+Python | JSON in `testdata/fill-vectors` |
| Replay golden hashes | recorded feeds | state hash every K events |
| Fuzzing | parser, book apply, WS inbound frames | cargo-fuzz, jazzer (optional) |
| Fault injection | reconnects, gaps, DB/Redis outage | mock servers, Testcontainers pause, toxiproxy (optional) |
| Contract tests | gRPC/REST/WS | `buf breaking`, stub servers, OpenAPI diff |
| Concurrency stress | per-account ordering | many-thread tests, repeat N times |
| E2E | full stack on replay | Playwright + compose |
| Load/soak | fan-out, 24 h feed | k6/Gatling, scheduled runs |
| Mutation testing (optional) | book, metrics, fills, ledger | cargo-mutants, PIT |

## Property test design tips
- Generate **valid** and **adversarial** inputs (zero qty removals, boundaries of the window, max int values, duplicate ops).
- Assert invariants after **every** step, not just at the end.
- Log seeds; keep minimal shrunk counterexamples as permanent regression tests.
- Keep generators fast so 100k+ cases are practical in CI nightly; smaller counts per PR.

## Replay and parity
- Fixtures in `testdata/feeds/` (clean, gap, reconnect); same file through Rust live-mode code, `pybook`, and Java paper engine; compare book hashes, metrics, fills, ledger balances.
- When a replay golden changes, the PR must explain *why* (intentional behavior change vs bug).

## Concurrency and time
Inject clocks (`Clock` trait/interface); never call wall-clock inside logic under test; use deterministic schedulers or barriers; for Java concurrency tests use latches and repeat tests many times in CI nightly.

## Performance tests
Controlled environment, warm-up, multiple runs, percentiles; open-loop load to avoid coordinated omission; allocation tests for hot paths; compare to a stored baseline with an explicit regression threshold (tune to avoid noise); publish method + hardware with numbers.

## CI gates (every PR)
Format/lint/type checks; unit + property tests for changed components (all if `proto/` changed); `buf lint/breaking`; dependency + secret scans; build artifacts; replay parity on small fixtures; benchmark smoke. Nightly: fuzz (time-boxed), full golden suite, Playwright E2E, 1 h soak. Pre-release: 24 h soak, 1,000-client load test, security checklist.

## Coverage
Meaningful assertions over percentages: core logic crates >= 90 percent, overall >= 80 percent; review test quality (do tests fail when the code is broken?). Consider mutation testing on the critical modules.

## Anti-patterns
Tests that only assert "no exception"; snapshot tests of huge outputs nobody reviews; real network calls in unit tests; sleeping to "wait for async"; shared mutable fixtures; ignoring a failing flaky test; testing implementation details instead of invariants.

## Definition of tested
Unit + (property|golden|parity as relevant) + failure-path tests + metrics/logging verified + docs updated.
