//! Property tests: incremental MetricsEngine vs slow_recompute oracle.
//!
//! Generates random book ops (snapshots, deltas, removals) and trades,
//! applies them to both the engine and the oracle recompute, and asserts
//! all metrics match after every operation.

use proptest::prelude::*;

use sb_book::{Book, BookConfig};
use sb_core::{Aggressor, InstrumentSpec, Level, Price, Qty, TsNs};
use sb_metrics::engine::{MetricsConfig, MetricsEngine, slow_recompute};

fn spec() -> InstrumentSpec {
    InstrumentSpec::new(2, 5, 10, 100, 0).unwrap()
}

fn book_config() -> BookConfig {
    BookConfig::new(1024, 100, 50).unwrap()
}

fn metrics_config() -> MetricsConfig {
    MetricsConfig {
        depth_levels: 5,
        vwap_window_ns: 100_000_000_000, // 100 seconds — wide enough to include all test trades
        trade_flow_window_ns: 100_000_000_000,
        vwap_capacity: 8192,
        trade_flow_capacity: 8192,
    }
}

/// Strategy to generate a price in units, tick-aligned.
fn price_units_strategy() -> impl Strategy<Value = i64> {
    // Prices from 10 to 200, in multiples of 10 (tick_size=10)
    (1i64..20).prop_map(|x| x * 10)
}

/// Strategy to generate a quantity in units, lot-aligned.
fn qty_units_strategy() -> impl Strategy<Value = i64> {
    // Quantities from 100 to 5000, in multiples of 100 (lot_size=100)
    (1i64..50).prop_map(|x| x * 100)
}

/// An operation to apply to the book/engine.
#[derive(Debug, Clone)]
enum Op {
    /// Apply a snapshot with given bid/ask levels
    Snapshot {
        bids: Vec<(i64, i64)>,
        asks: Vec<(i64, i64)>,
    },
    /// Apply a delta (set or remove levels)
    Delta {
        bids: Vec<(i64, i64)>,
        asks: Vec<(i64, i64)>,
    },
    /// Record a trade
    Trade {
        price_units: i64,
        qty_units: i64,
        is_buy: bool,
        event_ts_ns: i64,
    },
}

/// Strategy to generate a vector of bid levels (price descending, non-overlapping with asks).
fn bid_levels_strategy() -> impl Strategy<Value = Vec<(i64, i64)>> {
    prop::collection::vec((price_units_strategy(), qty_units_strategy()), 0..=5).prop_map(
        |levels| {
            // Deduplicate by price and keep prices in range [10, 90]
            let mut seen = std::collections::HashSet::new();
            levels
                .into_iter()
                .filter(|(p, _)| *p <= 90 && seen.insert(*p))
                .collect()
        },
    )
}

/// Strategy to generate a vector of ask levels (higher prices than bids).
fn ask_levels_strategy() -> impl Strategy<Value = Vec<(i64, i64)>> {
    prop::collection::vec((price_units_strategy(), qty_units_strategy()), 0..=5).prop_map(
        |levels| {
            let mut seen = std::collections::HashSet::new();
            levels
                .into_iter()
                // Shift asks up to avoid crossing with bids
                .map(|(p, q)| (p + 100, q))
                .filter(|(p, _)| seen.insert(*p))
                .collect()
        },
    )
}

fn delta_op_strategy() -> impl Strategy<Value = Op> {
    (
        prop::collection::vec(
            (price_units_strategy(), (0i64..50).prop_map(|x| x * 100)),
            0..=3,
        )
        .prop_map(|levels| {
            let mut seen = std::collections::HashSet::new();
            levels
                .into_iter()
                .filter(|(p, _)| *p <= 90 && seen.insert(*p))
                .collect::<Vec<_>>()
        }),
        prop::collection::vec(
            (price_units_strategy(), (0i64..50).prop_map(|x| x * 100)),
            0..=3,
        )
        .prop_map(|levels| {
            let mut seen = std::collections::HashSet::new();
            levels
                .into_iter()
                .map(|(p, q)| (p + 100, q))
                .filter(|(p, _)| seen.insert(*p))
                .collect::<Vec<_>>()
        }),
    )
        .prop_map(|(bids, asks)| Op::Delta { bids, asks })
}

fn trade_op_strategy() -> impl Strategy<Value = Op> {
    (
        price_units_strategy().prop_map(|p| p + 50), // trade price somewhere in mid
        qty_units_strategy(),
        any::<bool>(),
        1_000_000_000i64..100_000_000_000i64,
    )
        .prop_map(|(price_units, qty_units, is_buy, event_ts_ns)| Op::Trade {
            price_units,
            qty_units,
            is_buy,
            event_ts_ns,
        })
}

/// Strategy to generate a sequence of operations.
fn ops_strategy() -> impl Strategy<Value = Vec<Op>> {
    // Always start with a snapshot, then random ops
    let snapshot = (bid_levels_strategy(), ask_levels_strategy())
        .prop_map(|(bids, asks)| Op::Snapshot { bids, asks });

    // Start with snapshot, then 1..20 random ops
    snapshot.prop_flat_map(|initial_snap| {
        let follow =
            prop::collection::vec(prop_oneof![delta_op_strategy(), trade_op_strategy()], 1..20);
        follow.prop_map(move |mut ops| {
            ops.insert(0, initial_snap.clone());
            ops
        })
    })
}

fn assert_metrics_match(
    incremental: &sb_metrics::MetricsSnapshot,
    oracle: &sb_metrics::MetricsSnapshot,
    op_idx: usize,
) {
    // BBO validity and values
    assert_eq!(
        incremental.bbo.valid, oracle.bbo.valid,
        "BBO valid mismatch at op {}",
        op_idx
    );
    assert_eq!(
        incremental.bbo.spread_units, oracle.bbo.spread_units,
        "Spread mismatch at op {}",
        op_idx
    );

    if incremental.bbo.valid && oracle.bbo.valid {
        assert!(
            (incremental.bbo.mid - oracle.bbo.mid).abs() < 1e-6,
            "Mid mismatch at op {}: {} vs {}",
            op_idx,
            incremental.bbo.mid,
            oracle.bbo.mid
        );
        assert!(
            (incremental.bbo.microprice - oracle.bbo.microprice).abs() < 1e-6,
            "Microprice mismatch at op {}: {} vs {}",
            op_idx,
            incremental.bbo.microprice,
            oracle.bbo.microprice
        );
    }

    // Depth
    assert_eq!(
        incremental.depth.bid_depth, oracle.depth.bid_depth,
        "Bid depth mismatch at op {}",
        op_idx
    );
    assert_eq!(
        incremental.depth.ask_depth, oracle.depth.ask_depth,
        "Ask depth mismatch at op {}",
        op_idx
    );
    assert!(
        (incremental.depth.imbalance - oracle.depth.imbalance).abs() < 1e-6,
        "Imbalance mismatch at op {}: {} vs {}",
        op_idx,
        incremental.depth.imbalance,
        oracle.depth.imbalance
    );

    // VWAP
    assert_eq!(
        incremental.vwap.valid, oracle.vwap.valid,
        "VWAP valid mismatch at op {}",
        op_idx
    );
    if incremental.vwap.valid && oracle.vwap.valid {
        assert!(
            (incremental.vwap.vwap - oracle.vwap.vwap).abs() < 1e-6,
            "VWAP mismatch at op {}: {} vs {}",
            op_idx,
            incremental.vwap.vwap,
            oracle.vwap.vwap
        );
    }

    // Trade-flow
    assert_eq!(
        incremental.trade_flow.valid, oracle.trade_flow.valid,
        "Trade flow valid mismatch at op {}",
        op_idx
    );
    if incremental.trade_flow.valid && oracle.trade_flow.valid {
        assert!(
            (incremental.trade_flow.imbalance - oracle.trade_flow.imbalance).abs() < 1e-6,
            "Trade flow imbalance mismatch at op {}: {} vs {}",
            op_idx,
            incremental.trade_flow.imbalance,
            oracle.trade_flow.imbalance
        );
    }

    // Invariant: imbalance in [-1, 1]
    assert!(
        incremental.depth.imbalance >= -1.0 && incremental.depth.imbalance <= 1.0,
        "Depth imbalance out of range at op {}: {}",
        op_idx,
        incremental.depth.imbalance
    );

    if incremental.trade_flow.valid {
        assert!(
            incremental.trade_flow.imbalance >= -1.0 && incremental.trade_flow.imbalance <= 1.0,
            "Trade flow imbalance out of range at op {}: {}",
            op_idx,
            incremental.trade_flow.imbalance
        );
    }

    // Invariant: microprice within [bb, ba] when valid
    if let (true, Some(bb), Some(ba)) = (
        incremental.bbo.valid,
        incremental.bbo.best_bid,
        incremental.bbo.best_ask,
    ) {
        let bb_f = bb.units() as f64;
        let ba_f = ba.units() as f64;
        assert!(
            incremental.bbo.microprice >= bb_f - 1e-6 && incremental.bbo.microprice <= ba_f + 1e-6,
            "Microprice {} outside [bb={}, ba={}] at op {}",
            incremental.bbo.microprice,
            bb_f,
            ba_f,
            op_idx
        );
    }
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(
        std::env::var("PROPTEST_CASES")
            .ok()
            .and_then(|s| s.parse().ok())
            .unwrap_or(1000)
    ))]

    #[test]
    fn metrics_match_oracle(ops in ops_strategy()) {
        let s = spec();
        let bc = book_config();
        let mc = metrics_config();

        let mut book = Book::new(bc, s).unwrap();
        let mut engine = MetricsEngine::new(mc);
        let mut all_trades: Vec<(TsNs, Price, Qty, Aggressor)> = Vec::new();

        for (idx, op) in ops.iter().enumerate() {
            match op {
                Op::Snapshot { bids, asks } => {
                    let bid_levels: Vec<Level> = bids
                        .iter()
                        .filter_map(|&(p, q)| {
                            Some(Level::new(
                                Price::from_units(p).ok()?,
                                Qty::from_units(q).ok()?,
                            ))
                        })
                        .collect();
                    let ask_levels: Vec<Level> = asks
                        .iter()
                        .filter_map(|&(p, q)| {
                            Some(Level::new(
                                Price::from_units(p).ok()?,
                                Qty::from_units(q).ok()?,
                            ))
                        })
                        .collect();

                    let _ = book.apply_snapshot(&bid_levels, &ask_levels);
                    engine.on_book_update(&book);
                }
                Op::Delta { bids, asks } => {
                    let bid_levels: Vec<Level> = bids
                        .iter()
                        .filter_map(|&(p, q)| {
                            Some(Level::new(
                                Price::from_units(p).ok()?,
                                Qty::from_units(q).ok()?,
                            ))
                        })
                        .collect();
                    let ask_levels: Vec<Level> = asks
                        .iter()
                        .filter_map(|&(p, q)| {
                            Some(Level::new(
                                Price::from_units(p).ok()?,
                                Qty::from_units(q).ok()?,
                            ))
                        })
                        .collect();

                    let _ = book.apply_delta(&bid_levels, &ask_levels);
                    engine.on_book_update(&book);
                }
                Op::Trade {
                    price_units,
                    qty_units,
                    is_buy,
                    event_ts_ns,
                } => {
                    if let (Ok(price), Ok(qty)) =
                        (Price::from_units(*price_units), Qty::from_units(*qty_units))
                    {
                        let aggressor = if *is_buy {
                            Aggressor::Buy
                        } else {
                            Aggressor::Sell
                        };
                        let ts = TsNs::from_nanos(*event_ts_ns);
                        engine.on_trade(ts, price, qty, aggressor);
                        all_trades.push((ts, price, qty, aggressor));
                    }
                }
            }

            // Compare incremental vs oracle after every op
            let incremental = *engine.snapshot();
            let oracle = slow_recompute(
                &book,
                &all_trades,
                mc.depth_levels,
                mc.vwap_window_ns,
                mc.trade_flow_window_ns,
            );

            assert_metrics_match(&incremental, &oracle, idx);
        }
    }
}
