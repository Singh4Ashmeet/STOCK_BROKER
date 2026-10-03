# Testing Strategy

Quality is a headline feature (goals 2 and 3). Tests are how the claims are earned.

## 1. Principles

1. Test at the lowest level that can catch the bug; add higher-level tests for integration risks.
2. **Property-based and differential tests** for anything with a simple reference (order book vs oracle; fill model vs vectors).
3. **Deterministic replay** is the primary integration test technique.
4. Every bug gets a regression test (a recorded fixture or golden vector where possible).
5. CI must be fast enough to run on every PR; slow suites (soak, load) run on schedule or manually and publish artifacts.

## 2. Test pyramid by component

### Rust
| Level | What | Tools |
|---|---|---|
| Unit | Fixed-point math, parsing, metric formulas | `cargo test` |
| Property | Book vs oracle after random ops; invariants I1 to I8 | `proptest` |
| Fuzz | Parser (no panic, no unbounded alloc), book apply | `cargo-fuzz` (libFuzzer) |
| Sequence/fault | Gaps, duplicates, reordering, reconnect state machine | Scripted synthetic streams |
| Replay golden | Recorded hours produce identical output hashes | Fixtures in `testdata/` |
| Allocation | Zero allocations in hot functions | Counting allocator test harness |
| Benchmarks | Parse, apply, metrics; end-to-end replay throughput; regression gate | `criterion`, CI compares to baseline |
| Soak | 24 h live: mismatches, memory growth, reconnects | Scheduled job + report |

### Java
| Level | What | Tools |
|---|---|---|
| Unit | Fill math, fees, order state machine, token logic | JUnit 5, AssertJ |
| Property | Ledger balance invariants, no overspend under random sequences | jqwik |
| Integration | Repositories, migrations, transactions, idempotency, isolation | Testcontainers (Postgres, Redis) |
| Contract | gRPC client against a stub Rust server; proto compatibility | `buf breaking`, in-process gRPC |
| Security | Auth flows, authorization matrix, rate limits | Spring Security test, custom matrix tests |
| WebSocket | Snapshot-then-delta, gap handling, slow-consumer conflation | Test clients |
| Concurrency | Concurrent orders on one account | Stress tests with many threads |
| Load | 1,000 simulated clients; added fan-out latency | k6 or Gatling (scheduled) |

### Python
| Level | What | Tools |
|---|---|---|
| Unit | Metrics, data validators, report builders | pytest |
| Property | Conservation (cash/positions), fills <= depth | Hypothesis |
| Golden | Hand-computed scenarios; shared fill vectors | pytest + `testdata/fill-vectors` |
| Lookahead | Cheating strategies must raise; clairvoyant harness rejected | pytest |
| Parity | PyO3 binding vs Rust server on a recorded day | CI job with built wheel |
| Determinism | Two runs, byte-identical outputs | pytest |
| Static | Types, lint | mypy, ruff |

### Frontend
| Level | What | Tools |
|---|---|---|
| Unit | Reducers, formatters (fixed-point to display), WS message handling | Vitest |
| Component | Order book canvas logic (pure functions), forms | Testing Library |
| E2E | Login, subscribe, place order, view portfolio, backtest flow | Playwright with a replay-fed stack |
| Performance | Frame rate and main-thread blocking under full-rate replay | Playwright + Performance API traces |
| Accessibility | Keyboard navigation, contrast | axe |
| Visual (optional) | Key pages snapshot | Playwright screenshots |

## 3. Cross-system tests

| Test | Description |
|---|---|
| **Replay parity** | Same recorded day through Rust (live-mode code), PyO3 binding, and Java paper engine (scripted orders): identical books/metrics; identical fills and ledgers |
| **Fault injection** | Kill/restart each service mid-stream; drop connections; delay snapshot fetches; verify recovery and `epoch/seq` semantics end to end |
| **Contract** | `buf breaking` plus consumer tests in Java and Python against a stub/real Rust build |
| **Full stack smoke** | `docker compose` up, replay a fixture, UI shows live data, order placed, ledger reconciles |
| **Chaos (light)** | Randomly pause containers (e.g. Redis, Postgres) during load; verify degradation behavior in `03-ARCHITECTURE.md` |

## 4. Test data

- `testdata/feeds/`: small recorded snippets (a few thousand events) including a clean run, a run with a gap, a run with a reconnect; zstd-compressed.
- `testdata/fill-vectors/`: JSON golden vectors for the fill/fee model.
- `testdata/orderbook-sequences/`: hand-written edge-case sequences (crossing, far levels, re-centering).
- Large recordings live outside git; document how to record and verify them via `scripts/`.
- Synthetic generators for stress: seeded, parameterized message rate, depth, and fault rates.

## 5. CI gates (every PR)

1. Format and lint: `cargo fmt --check`, `clippy -D warnings`, `ruff`, `mypy --strict` (where feasible), `eslint`, `tsc --noEmit`, Java checkstyle/spotless.
2. Unit and property tests for changed components (all components if `proto/` changed).
3. `buf lint` and `buf breaking`.
4. Security scans: dependency audits, gitleaks.
5. Build artifacts (wheel, jar, Next.js build, containers).
6. Benchmark smoke with regression threshold (e.g. fail if book update median regresses more than 15 percent versus the baseline stored in `benchmarks/baseline.json`; tune the threshold to avoid noise).
7. Replay parity on the small fixtures.

**Nightly/scheduled:** fuzzing (time-boxed), full replay golden suite, Playwright E2E, 1-hour soak.
**Manual/pre-release:** 24 h soak, 1,000-client load test, security checklist.

## 6. Coverage and quality targets

- Line coverage is a signal, not a goal. Targets: core logic crates (book, metrics, fill model, ledger) at 90 percent or more with meaningful assertions; overall 80 percent.
- Optional mutation testing (`cargo-mutants`, PIT for Java) on book, metrics, fill, and ledger logic.
- No flaky tests: quarantine within one day, fix within a week.

## 7. Definition of "tested" per feature

A feature is not done until: unit tests exist, the relevant property/golden/parity test exists, failure paths are tested, metrics/logging verified, and docs updated.

## 8. Performance testing rules

- Pin CPU frequency governor to performance where possible; close other apps; record hardware and OS in results.
- Warm up before measuring; run multiple iterations; report medians and percentiles, not means alone.
- Avoid coordinated omission in load tests: use open-loop load (constant arrival rate) when measuring latency under load, or record intended send times (HdrHistogram's correction).
- Always publish the methodology with the numbers (`11-PERFORMANCE-AND-OBSERVABILITY.md`).
