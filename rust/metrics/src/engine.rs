//! `MetricsEngine` — the main entry point for incremental metrics.
//!
//! The engine wraps a reference to a `Book` and owns the rolling-window
//! state (VWAP, trade-flow). On each book update or trade, the caller
//! invokes the appropriate method and gets back a `MetricsSnapshot`.
//!
//! ## Design
//!
//! - **Incremental**: BBO and depth are computed from the book's cached
//!   best/depth values (O(1)). VWAP and trade-flow maintain ring buffers.
//! - **No allocation** on the hot path after construction.
//! - **Pure functions of state**: metrics depend only on book + trade window + config.

use sb_book::Book;
use sb_core::{Aggressor, BookSide, Price, Qty, TsNs};

use crate::bbo::compute_bbo;
use crate::depth::compute_depth;
use crate::trade_flow::TradeFlowImbalance;
use crate::types::MetricsSnapshot;
use crate::vwap::RollingVwap;

/// Configuration for the metrics engine.
#[derive(Debug, Clone, Copy)]
pub struct MetricsConfig {
    /// Number of depth levels for imbalance computation
    pub depth_levels: usize,
    /// VWAP window duration in nanoseconds
    pub vwap_window_ns: i64,
    /// Trade-flow imbalance window duration in nanoseconds
    pub trade_flow_window_ns: i64,
    /// Ring buffer capacity for VWAP
    pub vwap_capacity: usize,
    /// Ring buffer capacity for trade-flow
    pub trade_flow_capacity: usize,
}

impl Default for MetricsConfig {
    fn default() -> Self {
        Self {
            depth_levels: 10,
            vwap_window_ns: 60_000_000_000,       // 60 seconds
            trade_flow_window_ns: 60_000_000_000, // 60 seconds
            vwap_capacity: 4096,
            trade_flow_capacity: 4096,
        }
    }
}

/// Incremental metrics engine.
///
/// Owns the rolling-window state. Does **not** own the book — the caller
/// must pass the book reference on each update.
///
/// # Hot-path guarantees
///
/// - `on_book_update`: O(1), no allocation
/// - `on_trade`: amortized O(1), no allocation (ring buffer bounded)
/// - `snapshot`: O(1), no allocation
#[derive(Debug)]
pub struct MetricsEngine {
    config: MetricsConfig,
    vwap: RollingVwap,
    trade_flow: TradeFlowImbalance,
    /// Cached latest snapshot
    latest: MetricsSnapshot,
}

impl MetricsEngine {
    /// Create a new metrics engine with the given configuration.
    ///
    /// This is the only allocation: ring buffers are pre-sized here.
    pub fn new(config: MetricsConfig) -> Self {
        Self {
            vwap: RollingVwap::new(config.vwap_window_ns, config.vwap_capacity),
            trade_flow: TradeFlowImbalance::new(
                config.trade_flow_window_ns,
                config.trade_flow_capacity,
            ),
            latest: MetricsSnapshot::default(),
            config,
        }
    }

    /// Update metrics after a book change (snapshot or delta applied).
    ///
    /// Reads best bid/ask and depth from the book. O(1).
    pub fn on_book_update(&mut self, book: &Book) {
        // BBO
        let best_bid = book.best_bid();
        let best_ask = book.best_ask();
        self.latest.bbo = compute_bbo(best_bid, best_ask);

        // Depth and imbalance
        let bid_depth = book
            .depth_qty(BookSide::Bid, self.config.depth_levels)
            .unwrap_or(Qty::ZERO);
        let ask_depth = book
            .depth_qty(BookSide::Ask, self.config.depth_levels)
            .unwrap_or(Qty::ZERO);
        self.latest.depth = compute_depth(bid_depth, ask_depth, self.config.depth_levels);

        // VWAP and trade-flow snapshots are read from their own state
        self.latest.vwap = self.vwap.snapshot();
        self.latest.trade_flow = self.trade_flow.snapshot();
    }

    /// Record a trade and update VWAP and trade-flow metrics.
    ///
    /// Call this for every trade event. The book-derived metrics (BBO, depth)
    /// are not recomputed here — call `on_book_update` if the book also changed.
    pub fn on_trade(&mut self, event_ts: TsNs, price: Price, qty: Qty, aggressor: Aggressor) {
        self.vwap.record_trade(event_ts, price, qty);
        self.trade_flow.record_trade(event_ts, qty, aggressor);

        // Update cached trade metrics
        self.latest.vwap = self.vwap.snapshot();
        self.latest.trade_flow = self.trade_flow.snapshot();
    }

    /// Get the latest computed metrics snapshot.
    ///
    /// O(1), no computation — returns the cached value.
    pub fn snapshot(&self) -> &MetricsSnapshot {
        &self.latest
    }

    /// Get the metrics configuration.
    pub fn config(&self) -> &MetricsConfig {
        &self.config
    }

    /// Reset all metrics state (e.g., on book rebuild/epoch change).
    pub fn reset(&mut self) {
        self.vwap.clear();
        self.trade_flow.clear();
        self.latest = MetricsSnapshot::default();
    }
}

/// Slow, from-scratch recomputation of all metrics.
///
/// This is the **oracle** for property testing: it recomputes every metric
/// by scanning the book and trade history from scratch. Production code
/// uses the incremental `MetricsEngine`.
///
/// # Arguments
///
/// * `book` - The order book
/// * `trades` - All trades to consider (will be filtered by event-time window)
/// * `depth_levels` - Number of levels for depth/imbalance
/// * `vwap_window_ns` - VWAP window duration in nanoseconds
/// * `trade_flow_window_ns` - Trade-flow window duration in nanoseconds
#[allow(
    clippy::float_arithmetic,
    clippy::cast_precision_loss,
    clippy::as_conversions,
    clippy::arithmetic_side_effects
)]
pub fn slow_recompute(
    book: &Book,
    trades: &[(TsNs, Price, Qty, Aggressor)],
    depth_levels: usize,
    vwap_window_ns: i64,
    trade_flow_window_ns: i64,
) -> MetricsSnapshot {
    // BBO
    let best_bid = book.best_bid();
    let best_ask = book.best_ask();
    let bbo = compute_bbo(best_bid, best_ask);

    // Depth
    let bid_depth = book
        .depth_qty(BookSide::Bid, depth_levels)
        .unwrap_or(Qty::ZERO);
    let ask_depth = book
        .depth_qty(BookSide::Ask, depth_levels)
        .unwrap_or(Qty::ZERO);
    let depth = compute_depth(bid_depth, ask_depth, depth_levels);

    // Find latest trade timestamp
    let latest_ts = trades
        .iter()
        .map(|(ts, _, _, _)| ts.as_nanos())
        .max()
        .unwrap_or(0);

    // VWAP (from scratch)
    let vwap_cutoff = TsNs::from_nanos(latest_ts - vwap_window_ns);
    let mut vwap_sum_notional: i128 = 0;
    let mut vwap_sum_qty: i128 = 0;
    let mut vwap_count: u32 = 0;

    for (ts, price, qty, _) in trades {
        if *ts >= vwap_cutoff {
            vwap_sum_notional += i128::from(price.units()) * i128::from(qty.units());
            vwap_sum_qty += i128::from(qty.units());
            vwap_count += 1;
        }
    }

    let vwap = if vwap_sum_qty > 0 {
        crate::types::VwapSnapshot {
            vwap: vwap_sum_notional as f64 / vwap_sum_qty as f64,
            window_ns: vwap_window_ns,
            trade_count: vwap_count,
            valid: true,
        }
    } else {
        crate::types::VwapSnapshot {
            vwap: 0.0,
            window_ns: vwap_window_ns,
            trade_count: 0,
            valid: false,
        }
    };

    // Trade-flow imbalance (from scratch)
    let flow_cutoff = TsNs::from_nanos(latest_ts - trade_flow_window_ns);
    let mut sum_buy: i128 = 0;
    let mut sum_sell: i128 = 0;
    let mut flow_count: u32 = 0;

    for (ts, _, qty, aggressor) in trades {
        if *ts >= flow_cutoff {
            match aggressor {
                Aggressor::Buy => sum_buy += i128::from(qty.units()),
                Aggressor::Sell => sum_sell += i128::from(qty.units()),
            }
            flow_count += 1;
        }
    }

    let trade_flow = if flow_count > 0 {
        let total = sum_buy + sum_sell;
        let imbalance = if total == 0 {
            0.0
        } else {
            (sum_buy - sum_sell) as f64 / total as f64
        };
        crate::types::TradeFlowSnapshot {
            imbalance,
            buy_volume: i64::try_from(sum_buy).unwrap_or(i64::MAX),
            sell_volume: i64::try_from(sum_sell).unwrap_or(i64::MAX),
            window_ns: trade_flow_window_ns,
            trade_count: flow_count,
            valid: true,
        }
    } else {
        crate::types::TradeFlowSnapshot {
            imbalance: 0.0,
            buy_volume: 0,
            sell_volume: 0,
            window_ns: trade_flow_window_ns,
            trade_count: 0,
            valid: false,
        }
    };

    MetricsSnapshot {
        bbo,
        depth,
        vwap,
        trade_flow,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sb_book::{Book, BookConfig};
    use sb_core::{Aggressor, InstrumentSpec, Level, Price, Qty, TsNs};

    fn spec() -> InstrumentSpec {
        InstrumentSpec::new(2, 5, 10, 100, 0).unwrap()
    }

    fn book_config() -> BookConfig {
        BookConfig::new(1024, 100, 50).unwrap()
    }

    #[test]
    fn engine_basic() {
        let mut book = Book::new(book_config(), spec()).unwrap();
        book.apply_snapshot(
            &[Level::new(
                Price::from_units(10000).unwrap(),
                Qty::from_units(500).unwrap(),
            )],
            &[Level::new(
                Price::from_units(10020).unwrap(),
                Qty::from_units(300).unwrap(),
            )],
        )
        .unwrap();

        let mut engine = MetricsEngine::new(MetricsConfig::default());
        engine.on_book_update(&book);

        let snap = engine.snapshot();
        assert!(snap.bbo.valid);
        assert_eq!(snap.bbo.spread_units, 20);
        assert!(!snap.vwap.valid); // No trades yet
    }

    #[test]
    fn engine_with_trades() {
        let mut book = Book::new(book_config(), spec()).unwrap();
        book.apply_snapshot(
            &[Level::new(
                Price::from_units(10000).unwrap(),
                Qty::from_units(500).unwrap(),
            )],
            &[Level::new(
                Price::from_units(10020).unwrap(),
                Qty::from_units(300).unwrap(),
            )],
        )
        .unwrap();

        let mut engine = MetricsEngine::new(MetricsConfig::default());
        engine.on_book_update(&book);

        engine.on_trade(
            TsNs::from_nanos(1_000_000_000),
            Price::from_units(10020).unwrap(),
            Qty::from_units(100).unwrap(),
            Aggressor::Buy,
        );

        let snap = engine.snapshot();
        assert!(snap.vwap.valid);
        assert!((snap.vwap.vwap - 10020.0).abs() < 1e-9);
        assert!(snap.trade_flow.valid);
        assert!((snap.trade_flow.imbalance - 1.0).abs() < 1e-9);
    }

    #[test]
    fn engine_vs_slow_recompute() {
        let mut book = Book::new(book_config(), spec()).unwrap();
        book.apply_snapshot(
            &[
                Level::new(
                    Price::from_units(10000).unwrap(),
                    Qty::from_units(500).unwrap(),
                ),
                Level::new(
                    Price::from_units(9990).unwrap(),
                    Qty::from_units(300).unwrap(),
                ),
            ],
            &[
                Level::new(
                    Price::from_units(10020).unwrap(),
                    Qty::from_units(300).unwrap(),
                ),
                Level::new(
                    Price::from_units(10030).unwrap(),
                    Qty::from_units(200).unwrap(),
                ),
            ],
        )
        .unwrap();

        let config = MetricsConfig {
            depth_levels: 5,
            vwap_window_ns: 10_000_000_000,
            trade_flow_window_ns: 10_000_000_000,
            vwap_capacity: 4096,
            trade_flow_capacity: 4096,
        };

        let mut engine = MetricsEngine::new(config);

        let trades = vec![
            (
                TsNs::from_nanos(1_000_000_000),
                Price::from_units(10020).unwrap(),
                Qty::from_units(100).unwrap(),
                Aggressor::Buy,
            ),
            (
                TsNs::from_nanos(2_000_000_000),
                Price::from_units(10000).unwrap(),
                Qty::from_units(200).unwrap(),
                Aggressor::Sell,
            ),
        ];

        for (ts, price, qty, agg) in &trades {
            engine.on_trade(*ts, *price, *qty, *agg);
        }
        engine.on_book_update(&book);

        let incremental = engine.snapshot();
        let oracle = slow_recompute(
            &book,
            &trades,
            config.depth_levels,
            config.vwap_window_ns,
            config.trade_flow_window_ns,
        );

        // BBO should match exactly
        assert_eq!(incremental.bbo.valid, oracle.bbo.valid);
        assert_eq!(incremental.bbo.spread_units, oracle.bbo.spread_units);
        assert!((incremental.bbo.mid - oracle.bbo.mid).abs() < 1e-9);
        assert!((incremental.bbo.microprice - oracle.bbo.microprice).abs() < 1e-9);

        // Depth should match
        assert_eq!(incremental.depth.bid_depth, oracle.depth.bid_depth);
        assert_eq!(incremental.depth.ask_depth, oracle.depth.ask_depth);
        assert!((incremental.depth.imbalance - oracle.depth.imbalance).abs() < 1e-9);

        // VWAP should match
        assert_eq!(incremental.vwap.valid, oracle.vwap.valid);
        assert!((incremental.vwap.vwap - oracle.vwap.vwap).abs() < 1e-9);

        // Trade-flow should match
        assert_eq!(incremental.trade_flow.valid, oracle.trade_flow.valid);
        assert!((incremental.trade_flow.imbalance - oracle.trade_flow.imbalance).abs() < 1e-9);
    }

    #[test]
    fn engine_reset() {
        let mut engine = MetricsEngine::new(MetricsConfig::default());
        engine.on_trade(
            TsNs::from_nanos(1_000_000_000),
            Price::from_units(100).unwrap(),
            Qty::from_units(10).unwrap(),
            Aggressor::Buy,
        );
        assert!(engine.snapshot().vwap.valid);

        engine.reset();
        assert!(!engine.snapshot().vwap.valid);
    }
}
