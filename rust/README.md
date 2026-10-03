# rust/

Cargo workspace for the fast feed. See `docs/02-TRD.md` section 5 and the skills `rust-hot-path-performance`, `orderbook-engine-correctness`.

Planned crates (create in Phase 1):

| Crate | Contents |
|---|---|
| `core` | Types, fixed-point, timestamps, instrument metadata. No I/O, no async. |
| `book` | Optimized order book and the BTreeMap **oracle**. No I/O, no async. |
| `metrics` | Incremental metrics (spread, mid, microprice, depth, imbalance, VWAP). |
| `connectors` | `Exchange` trait + Binance implementation. |
| `server` | gRPC service, writer thread, config, health/metrics endpoint. |
| `replay` | Event-log reader and replay driver. |
| `pybook` | PyO3 bindings (maturin) for Python. |
