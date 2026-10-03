---
name: portfolio-presentation
description: Turning the project into a compelling, honest portfolio piece: README structure, architecture diagrams, benchmark charts and scorecard, demo video script, ADR-based write-ups, resume bullets and interview talking points. Use this skill whenever writing the README, marketing/benchmark pages, demo scripts, blog posts, case studies, resume or LinkedIn descriptions for this project, or preparing to present or explain the project to recruiters or interviewers.
---

# Portfolio presentation

Audience: recruiters (2 minutes) and engineers (20 minutes). Goal: make the three headline goals (latency, correctness, honest backtesting) instantly visible and verifiable. **Honesty is the differentiator** - every number has a method.

## README structure
1. **One-paragraph pitch + disclaimer** (simulated trading, not investment advice).
2. **Demo:** GIF/short video (<= 90 s) of the live book with latency overlay, a paper order with slippage preview, and a backtest cost waterfall; link to a live demo only if hosted safely.
3. **Scorecard** (table): latency (per stage + end to end), book update ns (oracle vs optimized), correctness (mismatches over 24 h), parity (live = replay = backtest), fan-out at 1,000 clients, backtest honesty (gross vs net). Each row links to the exact `benchmarks/results/...` directory (commit + environment).
4. **Architecture diagram** and a short "why each language" table.
5. **Key engineering decisions** (link to ADRs): fixed-point, optimized book + oracle, direct gRPC path, PyO3 shared book, conflation, no-lookahead engine.
6. **How to run** (one command via compose + replay fixture) and **how to reproduce benchmarks**.
7. **Testing and quality** summary (property tests, fuzzing, replay parity, soak, load).
8. **Limitations and honest caveats** (measured on X hardware; loopback vs network; no queue-position model; paper only).
9. **Roadmap** (brief) and license.

## Charts that persuade (generate from committed data)
- Latency waterfall by stage with p50/p99 bars and budget lines.
- Oracle vs optimized book (ns/update, with distribution).
- Parser comparison (serde_json vs simd-json vs custom).
- Fan-out added latency vs number of clients.
- Backtest cost waterfall (gross -> fees -> slippage -> latency -> net) and walk-forward vs baseline.
- Soak test: memory flat line and mismatch counter at zero.
Keep charts simple, labeled with units, environment in the caption.

## Demo script (90 seconds)
1. (10 s) Pitch + disclaimer. 2. (25 s) Live book, latency overlay, per-stage numbers. 3. (15 s) Kill the exchange connection: book goes "resyncing", recovers, mismatch counter stays 0. 4. (20 s) Paper order walks levels; slippage preview vs fill. 5. (20 s) Backtest: gross to net waterfall, baseline comparison.

## Write-up ideas (blog/case study)
"How I made an order book 10x faster and proved it correct", "Honest backtesting: why most free results are fiction", "Designing for slow consumers: conflation in a WebSocket fan-out". Use real measured numbers only.

## Resume/LinkedIn bullets (fill with measured values)
- Built a polyglot market-data platform (Rust, Java, Python, Next.js): Rust feed achieving p99 `<X>` us internal latency and zero order-book mismatches over a 24 h soak.
- Implemented a dense-window order book with a BTreeMap oracle and property/differential tests; `<N>`x faster updates than the baseline.
- Built a multi-user paper-trading engine filling against the live book with a double-entry ledger, idempotent orders and per-account serialization.
- Designed a no-lookahead backtester sharing the production Rust book via PyO3; reported gross-to-net cost breakdowns and walk-forward results.
- Load-tested WebSocket fan-out at 1,000 clients with bounded queues and conflation: p99 added latency `<Y>` ms.
Never ship bullets with placeholders; delete any claim you can't back with a result file.

## Interview talking points
Why the live path bypasses a bus; how the sync algorithm detects gaps; why fixed-point; how the oracle catches bugs (tell a real bug story); how conflation protects the system; why same-clock latency is the headline; what you'd do next (second venue, binary protocol, shared-memory transport); what went wrong and what you learned.

## Rules
- Label cross-host and indicative numbers as such; never claim "faster than Bloomberg/TradingView/Binance".
- No investment-return claims; always "simulated".
- Keep the repo tidy: clear commit history, passing CI badge, docs index, `SECURITY.md`, license, `CONTRIBUTING` (short).
- Publish failures and trade-offs (ADRs, limitations): they build credibility.

## Review checklist
- [ ] Every number links to a result directory with environment metadata
- [ ] Disclaimer and limitations visible
- [ ] One-command run works from a clean clone
- [ ] Demo video under 2 minutes; diagrams render on GitHub
