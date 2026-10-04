# rust/

Cargo workspace for the fast feed. See `docs/02-TRD.md` section 5 and the skills `rust-hot-path-performance`, `orderbook-engine-correctness`.

Planned crates (create in Phase 1):

| Directory | Package name | Library name | Contents |
|---|---|---|---|
| `core` | `sb-core` | `sb_core` | Types, fixed-point, timestamps, instrument metadata. No I/O, no async. |
| `book` | `sb-book` | `sb_book` | Optimized order book and the BTreeMap **oracle**. No I/O, no async. |
| `metrics` | `sb-metrics` | `sb_metrics` | Incremental metrics (spread, mid, microprice, depth, imbalance, VWAP). |
| `connectors` | `sb-connectors` | `sb_connectors` | `Exchange` trait + Binance implementation. |
| `server` | `sb-feed` | `sb_feed` (binary) | gRPC service, writer thread, config, health/metrics endpoint. |
| `replay` | `sb-replay` | `sb_replay` | Event-log reader and replay driver. |
| `pybook` | `sb-pybook` | `sb_pybook` | PyO3 bindings (maturin) for Python. |

> **Note**: Per ADR-0012, all Cargo package names use the `sb-` prefix. Directory names remain unchanged. Use `sb-core` in `Cargo.toml` dependencies; import as `use sb_core::...`.
