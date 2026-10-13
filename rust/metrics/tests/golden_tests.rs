//! Hand-calculated golden value tests for all metrics.
//!
//! Each test uses specific numeric values and verifies the exact expected
//! result computed by hand.

use sb_book::{Book, BookConfig};
use sb_core::{Aggressor, InstrumentSpec, Level, Price, Qty, TsNs};
use sb_metrics::engine::{MetricsConfig, MetricsEngine};

fn spec() -> InstrumentSpec {
    // price_scale=2 → 1 unit = 0.01; tick_size=10 → 0.10
    // qty_scale=5 → 1 unit = 0.00001; lot_size=100 → 0.00100
    InstrumentSpec::new(2, 5, 10, 100, 0).unwrap()
}

fn config() -> BookConfig {
    BookConfig::new(1024, 100, 50).unwrap()
}

fn metrics_config() -> MetricsConfig {
    MetricsConfig {
        depth_levels: 5,
        vwap_window_ns: 60_000_000_000,
        trade_flow_window_ns: 60_000_000_000,
        vwap_capacity: 4096,
        trade_flow_capacity: 4096,
    }
}

// ─── BBO / Spread / Mid / Microprice ─────────────────────────────────

#[test]
fn golden_bbo_symmetric() {
    // bb=100.00 (10000), ba=100.20 (10020)
    // qb=5.00000 (500000), qa=3.00000 (300000)
    let mut book = Book::new(config(), spec()).unwrap();
    book.apply_snapshot(
        &[Level::new(
            Price::from_units(10000).unwrap(),
            Qty::from_units(500000).unwrap(),
        )],
        &[Level::new(
            Price::from_units(10020).unwrap(),
            Qty::from_units(300000).unwrap(),
        )],
    )
    .unwrap();

    let mut engine = MetricsEngine::new(metrics_config());
    engine.on_book_update(&book);
    let snap = engine.snapshot();

    assert!(snap.bbo.valid);

    // Spread = 10020 - 10000 = 20 units
    assert_eq!(snap.bbo.spread_units, 20);

    // Mid = (10000 + 10020) / 2 = 10010.0
    assert!((snap.bbo.mid - 10010.0).abs() < 1e-9);

    // Microprice = (10000 * 300000 + 10020 * 500000) / (300000 + 500000)
    //            = (3_000_000_000 + 5_010_000_000) / 800000
    //            = 8_010_000_000 / 800000
    //            = 10012.5
    assert!((snap.bbo.microprice - 10012.5).abs() < 1e-9);
}

#[test]
fn golden_bbo_equal_sizes() {
    // Equal sizes → microprice == mid
    let mut book = Book::new(config(), spec()).unwrap();
    book.apply_snapshot(
        &[Level::new(
            Price::from_units(10000).unwrap(),
            Qty::from_units(1000).unwrap(),
        )],
        &[Level::new(
            Price::from_units(10020).unwrap(),
            Qty::from_units(1000).unwrap(),
        )],
    )
    .unwrap();

    let mut engine = MetricsEngine::new(metrics_config());
    engine.on_book_update(&book);
    let snap = engine.snapshot();

    assert!(snap.bbo.valid);
    // microprice = (10000 * 1000 + 10020 * 1000) / (1000 + 1000)
    //            = 20_020_000 / 2000 = 10010.0 == mid
    assert!((snap.bbo.microprice - snap.bbo.mid).abs() < 1e-9);
}

#[test]
fn golden_bbo_odd_spread() {
    // Spread = 1 unit (odd) → mid has .5. Use tick_size=1 so 1 unit spread is tick-aligned.
    let spec_tick1 = InstrumentSpec::new(2, 5, 1, 100, 0).unwrap();
    let mut book = Book::new(config(), spec_tick1).unwrap();
    book.apply_snapshot(
        &[Level::new(
            Price::from_units(100).unwrap(),
            Qty::from_units(1000).unwrap(),
        )],
        &[Level::new(
            Price::from_units(101).unwrap(),
            Qty::from_units(1000).unwrap(),
        )],
    )
    .unwrap();

    let mut engine = MetricsEngine::new(metrics_config());
    engine.on_book_update(&book);
    let snap = engine.snapshot();

    assert!(snap.bbo.valid);
    assert_eq!(snap.bbo.spread_units, 1);
    assert!((snap.bbo.mid - 100.5).abs() < 1e-9);
}

// ─── Depth / Imbalance ────────────────────────────────────────────────

#[test]
fn golden_depth_imbalance() {
    // Bids: 3 levels, asks: 2 levels, depth_levels=5
    let mut book = Book::new(config(), spec()).unwrap();
    book.apply_snapshot(
        &[
            Level::new(
                Price::from_units(1000).unwrap(),
                Qty::from_units(100).unwrap(),
            ),
            Level::new(
                Price::from_units(990).unwrap(),
                Qty::from_units(200).unwrap(),
            ),
            Level::new(
                Price::from_units(980).unwrap(),
                Qty::from_units(300).unwrap(),
            ),
        ],
        &[
            Level::new(
                Price::from_units(1010).unwrap(),
                Qty::from_units(150).unwrap(),
            ),
            Level::new(
                Price::from_units(1020).unwrap(),
                Qty::from_units(250).unwrap(),
            ),
        ],
    )
    .unwrap();

    let mut engine = MetricsEngine::new(metrics_config());
    engine.on_book_update(&book);
    let snap = engine.snapshot();

    // Bid depth(5) = 100 + 200 + 300 = 600
    assert_eq!(snap.depth.bid_depth.units(), 600);
    // Ask depth(5) = 150 + 250 = 400
    assert_eq!(snap.depth.ask_depth.units(), 400);
    // Imbalance = (600 - 400) / (600 + 400) = 200 / 1000 = 0.2
    assert!((snap.depth.imbalance - 0.2).abs() < 1e-9);
}

#[test]
fn golden_depth_one_side_empty() {
    let mut book = Book::new(config(), spec()).unwrap();
    book.apply_snapshot(
        &[Level::new(
            Price::from_units(1000).unwrap(),
            Qty::from_units(500).unwrap(),
        )],
        &[],
    )
    .unwrap();

    let mut engine = MetricsEngine::new(metrics_config());
    engine.on_book_update(&book);
    let snap = engine.snapshot();

    // Bid only → imbalance = (500-0)/(500+0) = 1.0
    assert!((snap.depth.imbalance - 1.0).abs() < 1e-9);
}

// ─── VWAP ─────────────────────────────────────────────────────────────

#[test]
fn golden_vwap_two_trades() {
    // Trade 1: p=100, q=10 → notional=1000
    // Trade 2: p=200, q=30 → notional=6000
    // VWAP = 7000 / 40 = 175.0
    let mut book = Book::new(config(), spec()).unwrap();
    book.apply_snapshot(
        &[Level::new(
            Price::from_units(100).unwrap(),
            Qty::from_units(100).unwrap(),
        )],
        &[Level::new(
            Price::from_units(300).unwrap(),
            Qty::from_units(100).unwrap(),
        )],
    )
    .unwrap();

    let mut engine = MetricsEngine::new(metrics_config());
    engine.on_book_update(&book);

    engine.on_trade(
        TsNs::from_nanos(1_000_000_000),
        Price::from_units(100).unwrap(),
        Qty::from_units(10).unwrap(),
        Aggressor::Buy,
    );
    engine.on_trade(
        TsNs::from_nanos(2_000_000_000),
        Price::from_units(200).unwrap(),
        Qty::from_units(30).unwrap(),
        Aggressor::Sell,
    );

    let snap = engine.snapshot();
    assert!(snap.vwap.valid);
    assert_eq!(snap.vwap.trade_count, 2);
    // VWAP = (100*10 + 200*30) / (10+30) = (1000+6000)/40 = 175.0
    assert!((snap.vwap.vwap - 175.0).abs() < 1e-9);
}

#[test]
fn golden_vwap_single_trade() {
    let mut engine = MetricsEngine::new(metrics_config());
    engine.on_trade(
        TsNs::from_nanos(1_000_000_000),
        Price::from_units(42000).unwrap(),
        Qty::from_units(100).unwrap(),
        Aggressor::Buy,
    );

    let snap = engine.snapshot();
    assert!(snap.vwap.valid);
    // Single trade → VWAP == trade price
    assert!((snap.vwap.vwap - 42000.0).abs() < 1e-9);
}

// ─── Trade-flow imbalance ─────────────────────────────────────────────

#[test]
fn golden_trade_flow_mixed() {
    // Buy: 100 + 200 = 300
    // Sell: 150
    // Imbalance = (300 - 150) / (300 + 150) = 150 / 450 = 1/3 ≈ 0.3333
    let mut engine = MetricsEngine::new(metrics_config());

    engine.on_trade(
        TsNs::from_nanos(1_000_000_000),
        Price::from_units(100).unwrap(),
        Qty::from_units(100).unwrap(),
        Aggressor::Buy,
    );
    engine.on_trade(
        TsNs::from_nanos(2_000_000_000),
        Price::from_units(100).unwrap(),
        Qty::from_units(200).unwrap(),
        Aggressor::Buy,
    );
    engine.on_trade(
        TsNs::from_nanos(3_000_000_000),
        Price::from_units(100).unwrap(),
        Qty::from_units(150).unwrap(),
        Aggressor::Sell,
    );

    let snap = engine.snapshot();
    assert!(snap.trade_flow.valid);
    assert_eq!(snap.trade_flow.buy_volume, 300);
    assert_eq!(snap.trade_flow.sell_volume, 150);
    assert!((snap.trade_flow.imbalance - (1.0 / 3.0)).abs() < 1e-9);
}

#[test]
fn golden_trade_flow_all_sells() {
    let mut engine = MetricsEngine::new(metrics_config());
    engine.on_trade(
        TsNs::from_nanos(1_000_000_000),
        Price::from_units(100).unwrap(),
        Qty::from_units(500).unwrap(),
        Aggressor::Sell,
    );

    let snap = engine.snapshot();
    assert!(snap.trade_flow.valid);
    assert!((snap.trade_flow.imbalance - (-1.0)).abs() < 1e-9);
}

// ─── Edge cases ───────────────────────────────────────────────────────

#[test]
fn golden_empty_book() {
    let book = Book::new(config(), spec()).unwrap();
    let mut engine = MetricsEngine::new(metrics_config());
    engine.on_book_update(&book);

    let snap = engine.snapshot();
    assert!(!snap.bbo.valid);
    assert!((snap.depth.imbalance - 0.0).abs() < 1e-9); // Both sides zero
}

#[test]
fn golden_crossed_book() {
    let mut book = Book::new(config(), spec()).unwrap();
    book.apply_snapshot(
        &[Level::new(
            Price::from_units(1000).unwrap(),
            Qty::from_units(100).unwrap(),
        )],
        &[Level::new(
            Price::from_units(1010).unwrap(),
            Qty::from_units(100).unwrap(),
        )],
    )
    .unwrap();

    // Now cross the book
    let _ = book.apply_delta(
        &[Level::new(
            Price::from_units(1020).unwrap(),
            Qty::from_units(50).unwrap(),
        )],
        &[],
    );

    let mut engine = MetricsEngine::new(metrics_config());
    engine.on_book_update(&book);

    let snap = engine.snapshot();
    // BBO should be invalid because book is crossed
    assert!(!snap.bbo.valid);
}

#[test]
fn golden_metrics_unchanged_by_noop_delta() {
    let mut book = Book::new(config(), spec()).unwrap();
    book.apply_snapshot(
        &[Level::new(
            Price::from_units(1000).unwrap(),
            Qty::from_units(100).unwrap(),
        )],
        &[Level::new(
            Price::from_units(1010).unwrap(),
            Qty::from_units(200).unwrap(),
        )],
    )
    .unwrap();

    let mut engine = MetricsEngine::new(metrics_config());
    engine.on_book_update(&book);
    let snap1 = *engine.snapshot();

    // Apply empty delta (no-op)
    let _ = book.apply_delta(&[], &[]);
    engine.on_book_update(&book);
    let snap2 = *engine.snapshot();

    // BBO should be unchanged
    assert_eq!(snap1.bbo.spread_units, snap2.bbo.spread_units);
    assert!((snap1.bbo.mid - snap2.bbo.mid).abs() < 1e-9);
    assert!((snap1.bbo.microprice - snap2.bbo.microprice).abs() < 1e-9);
    assert_eq!(snap1.depth.bid_depth, snap2.depth.bid_depth);
    assert_eq!(snap1.depth.ask_depth, snap2.depth.ask_depth);
}
