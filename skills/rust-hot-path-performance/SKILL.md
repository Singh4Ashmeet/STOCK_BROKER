---
name: rust-hot-path-performance
description: Rules and patterns for writing low-latency, allocation-free Rust on the market-data hot path (parsing, order book updates, metrics, queues, threading, benchmarking, profiling). Use this skill whenever writing or reviewing Rust in the feed service, `sb-core`, `sb-book`, `sb-metrics` or `sb-feed` crates, optimizing latency or throughput, choosing channels/allocators/parsers, adding benchmarks, or investigating a latency regression - even if the user only says "make it faster" or "add a Rust module".
---

# Rust hot-path performance

Context: `docs/02-TRD.md` section 5, `docs/06-ORDER-BOOK-SPEC.md`, `docs/11-PERFORMANCE-AND-OBSERVABILITY.md`. Goal 1 is latency, but **correctness never yields to a benchmark number**.

## What counts as the hot path
Everything between "socket frame received" and "message handed to the gRPC/log queues": parse, normalize, book apply, metrics update, timestamp stamping, enqueue.

## Hard rules (hot path)
1. **No heap allocation.** No `Vec::push` growth, `String`, `Box`, `format!`, `clone()` of heap data, or `collect()` in per-message code. Pre-size buffers at startup; reuse them (`clear()` keeps capacity).
2. **No locks, no blocking.** No `Mutex`, `RwLock`, channels that block, file/network I/O, or `println!`/logging per message. Use single-owner state per worker thread and bounded SPSC/MPSC lock-free queues (`rtrb`, `crossbeam`).
3. **No floats for prices/quantities.** Integer ticks/lots only (ADR-0003). Floats only in derived metrics.
4. **No panics on external input.** No `unwrap/expect/index` on data from the network; return typed errors, count them, drop the frame.
5. **Time is an input.** Hot-path logic takes timestamps as arguments (deterministic replay); only the reader stamps `recv_ts` from the clock.
6. **Bounded everything.** Every queue has a capacity and an overflow policy (conflate market data; drop-and-count log records).

## Architecture patterns
- Reader task (tokio) only pumps bytes into a ring buffer; workers (dedicated OS threads) own symbols and do parse+book+metrics. Benchmark moving parse to the reader before deciding.
- Shard by symbol; one book per symbol owned by exactly one thread. No shared mutable state between workers.
- Optional core pinning (`core_affinity`) and thread names; document in config.
- Keep `sb-core`, `sb-book`, `sb-metrics` free of async/I/O so they are usable from Python (PyO3) and benchmarkable in isolation.

## Data layout
- Structure-of-arrays for per-level data on the hot path; contiguous `Vec<i64>`/boxed slices indexed by tick offset.
- Align hot structs (`#[repr(align(64))]`) to avoid false sharing between threads (counters, queue heads).
- Prefer `u32/i64` indices and `Copy` types; avoid pointer-heavy structures (`Rc`, `HashMap` per update).
- Use `SmallVec`/fixed arrays for small bounded lists (levels per message).

## Parsing
- Parse directly from the receive buffer into integer fields; avoid building a DOM. Compare `simd-json`/`sonic-rs` against a hand-written scanner for the known Binance shapes; keep `serde_json` as a correctness baseline in tests.
- Convert decimal strings to scaled integers without going through `f64`.
- Fuzz the parser (`cargo-fuzz`); parse errors increment a counter and never panic.

## Build and runtime settings
- Release profile: `opt-level = 3`, `lto = "fat"`, `codegen-units = 1`, `debug = "line-tables-only"` for profiling. Consider `panic = "abort"` for the `server` binary (not for `pybook`).
- Compare allocators (system vs `mimalloc`/`jemalloc`) with the benchmark suite, but the hot path should not allocate at all.
- `target-cpu=native` only for local benchmarks, never for distributed binaries.
- Use `#[inline]` sparingly and verify with `cargo asm`/benchmarks, not intuition. Avoid `dyn Trait` in the hottest loops; use generics.

## Benchmarking and profiling (required for perf PRs)
1. **criterion** micro-benchmarks with recorded realistic inputs (not random noise); report median and p99 via custom measurement or hdrhistogram.
2. End-to-end replay benchmark: events/s/core and per-stage latency histograms.
3. Allocation test: a counting global allocator test asserting 0 allocations during N hot-path iterations.
4. Profile: `perf record` + flamegraph (`cargo flamegraph`) or `samply`; check cache misses (`perf stat`); inspect asm for the top function.
5. Record hardware, OS, governor, commit, and flags with every result (`benchmarks/`). CI numbers are regression signals only.
6. Beware coordinated omission and warm-up effects; run multiple iterations.

## Instrumentation (cheap)
- Stage timestamps as plain integers written into the event struct; deltas into `hdrhistogram` (pre-allocated) on the worker thread; export via a separate task.
- Counters as `AtomicU64` with `Relaxed` ordering, per-thread then aggregated, to avoid contention.

## Anti-patterns
- `Arc<Mutex<Book>>` shared across threads.
- `String` keys (symbol) in per-message maps - intern symbols to small ids at startup.
- `tracing::info!` per message; `serde_json::Value` on the hot path; `HashMap` rehash growth during trading.
- Unbounded `tokio::sync::mpsc` channels.
- Optimizing without a benchmark or claiming speedups without before/after numbers.

## Review checklist
- [ ] No allocation/lock/blocking/log in per-message code (allocation test passes)
- [ ] All queues bounded with a documented overflow policy
- [ ] No unwrap on external data; fuzz target covers the parser
- [ ] Integers for money/qty; scales from InstrumentSpec
- [ ] Benchmarks added/updated with environment metadata
- [ ] Complexity and allocation guarantees documented on public hot-path functions
