# Glossary

| Term | Meaning |
|---|---|
| **Order book** | Per-instrument collection of resting buy (bid) and sell (ask) orders aggregated by price level |
| **Level** | One price with its total resting quantity |
| **Best bid / best ask** | Highest bid price / lowest ask price |
| **Spread** | `best_ask - best_bid` |
| **Mid price** | `(best_bid + best_ask) / 2` |
| **Microprice** | Size-weighted mid: `(bid*ask_qty + ask*bid_qty) / (bid_qty + ask_qty)` |
| **Imbalance** | `(bid_depth - ask_depth) / (bid_depth + ask_depth)` over top N levels |
| **Depth** | Cumulative quantity over the top N levels or within X bps of mid |
| **VWAP** | Volume-weighted average price over a window |
| **Trade-flow imbalance** | `(buy_volume - sell_volume) / total` using aggressor side |
| **Aggressor** | The side that crossed the spread and initiated the trade |
| **Maker / taker** | Passive resting order / order that removes liquidity |
| **Slippage** | Difference between expected (mid or decision-time) price and the achieved average fill price |
| **Market impact** | Price movement caused by your own order |
| **Tick size / lot size** | Minimum price / quantity increment |
| **Snapshot / diff (delta)** | Full book state / incremental update |
| **Epoch** | Counter that increments whenever a stream's book is rebuilt; new epoch means a new snapshot follows |
| **Seq** | Per-epoch message counter used to detect gaps |
| **Conflation** | Dropping stale queued updates and replacing them with the latest state for slow consumers |
| **Resync** | Rebuilding a book from a fresh snapshot after a gap or reconnect |
| **Oracle (book)** | Simple, obviously correct reference implementation used to test the optimized one |
| **Replay** | Re-running recorded events through the same code path, deterministically |
| **Fixed-point** | Representing decimals as scaled integers (e.g. 6432100 with scale 2 = 64321.00) |
| **PyO3 / maturin** | Rust-Python binding framework / its build tool |
| **gRPC** | RPC framework over HTTP/2 with Protobuf contracts and streaming |
| **Backpressure** | Mechanism preventing fast producers from overwhelming slow consumers |
| **p50 / p99 / p99.9** | Latency percentiles |
| **Coordinated omission** | Measurement error when a load generator slows down along with the system under test, hiding latency spikes |
| **Walk-forward** | Out-of-sample evaluation that rolls training and test windows through time |
| **Lookahead bias** | Using information in a backtest that would not have been available at decision time |
| **Deflated Sharpe ratio** | Sharpe adjusted for the number of strategies/parameters tried |
| **Idempotency key** | Client-provided token ensuring retried requests do not repeat side effects |
| **Double-entry ledger** | Accounting where every transaction has equal debits and credits |
| **ADR** | Architecture Decision Record |
| **Paper trading** | Simulated trading with no real money |
