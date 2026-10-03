---
name: rust-pyo3-maturin
description: Exposing the Rust order book, metrics and event reader to Python with PyO3 and maturin: coarse-grained API design, zero-copy NumPy/Arrow interchange, GIL handling, error mapping, wheel builds in CI, versioning and parity testing. Use this skill whenever working on the `pybook` crate, building or debugging the Python wheel, designing the Python-Rust boundary, or making the backtester use the shared Rust book.
---

# Rust to Python with PyO3 and maturin

Decision record: `docs/adr/ADR-0005-pyo3-shared-book.md`. Goal: **one order-book implementation** shared by live feed and backtests.

## Design the boundary first
- **Coarse-grained calls.** Python must never call into Rust once per tick. Provide batch APIs: `EventReader.next_batch(n)` returns columnar arrays for `n` events plus the book state after each event (best bid/ask, top-K levels, metrics), so Python-side logic is vectorized or Numba-compiled.
- **Stateful objects stay in Rust** (`OrderBook`, `EventReader`, `ExecutionBook` for `walk_market`); Python holds handles.
- **No Python callbacks in the hottest loops.** If a strategy must react per event, expose a Rust-driven loop that calls the Python strategy only on "decision points" (for example when a feature crosses a threshold) or process in vectorized batches with explicit latency handling in the engine.
- Keep the API small, versioned, and documented with type stubs (`.pyi`) generated or hand-maintained.

## Project setup
- Crate type `cdylib`; `pyo3` with `abi3` feature (stable ABI, e.g. `abi3-py310`) to build one wheel per platform instead of per Python version.
- `maturin develop` for local dev; `maturin build --release` for wheels; `pyproject.toml` with `[tool.maturin]`.
- `pybook` depends on `core`, `book`, `metrics`, `replay` only (no tokio, no gRPC).
- Keep `unwind` panic strategy for `pybook`; convert panics/errors to Python exceptions. Do not set `panic = "abort"` for this crate.

## Data interchange
- Prefer Arrow arrays (`pyarrow` via the C Data Interface, or the `arrow` crate with PyCapsule) or NumPy arrays via the `numpy` crate for zero-copy where lifetimes allow. For each batch, allocate output buffers once and reuse.
- Integer columns for prices/qty/timestamps (fixed-point preserved). Convert to floats only in analytics columns.
- Document dtype and scale conventions; include `InstrumentSpec` in the reader's metadata.

## GIL and threading
- Release the GIL during long Rust work: `py.allow_threads(|| ...)`.
- Do not hold references to Python objects across `allow_threads`.
- Readers can run in a background Rust thread producing batches into a bounded queue (prefetch) to overlap I/O and Python compute.

## Errors
Map Rust errors to a small exception hierarchy (`PyBookError`, `DataGapError`, `SchemaVersionError`). Never return partial/corrupt batches silently.

## Versioning and compatibility
- Wheel version = workspace version; expose `pybook.__version__` and `pybook.build_info()` (git commit, rustc version, features).
- Event-log `schema_version` check at open; refuse unknown future versions.
- Record `pybook` version in every backtest `run.json`.

## CI
- Build wheels for Linux (manylinux), Windows, macOS where needed (at least the platforms you run on); upload as artifacts; test by installing the built wheel into a clean venv.
- Run the **parity test**: same recorded day through the Rust server (live-mode code, replay) and through `pybook`; assert identical book states and metrics at every event (compare hashes every K events plus full equality on small fixtures).
- Run `cargo test` for the pure crates separately from Python tests.

## Performance checks
- Benchmark `next_batch` throughput (events/s) and the Python overhead per batch; target 100x real-time replay on a laptop (see `docs/08`).
- Check for accidental copies with allocation profiling; avoid converting to Python lists/dicts.

## Anti-patterns
- Per-tick Python <-> Rust calls; returning Python lists of dicts; building DataFrames row by row.
- Re-implementing book logic in Python/Numba "just for the backtest".
- Holding the GIL while reading files.
- Floating-point prices crossing the boundary.

## Review checklist
- [ ] Batch API only; documented dtypes/scales
- [ ] GIL released in long operations
- [ ] Parity test passes in CI; wheel version recorded
- [ ] Type stubs updated; docs/README for installing the wheel updated
