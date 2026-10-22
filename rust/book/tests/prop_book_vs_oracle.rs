//! Property tests: optimized book vs BTreeMap oracle
//!
//! This test file uses proptest to generate random sequences of order book operations
//! (snapshots, deltas, clears) and asserts that the optimized Book and the OracleBook
//! produce identical results after every operation.
//!
//! Run with PROPTEST_CASES=100000 for CI mode, or default for local runs.

use proptest::prelude::*;
use sb_book::{ApplyResult, Book, BookConfig, BookError, FillPlan, OracleBook};
use sb_core::{BookSide, InstrumentSpec, Level, Price, Qty};

/// Instrument spec for testing (BTC-USDT like: price_scale=2, qty_scale=5, tick=0.01, lot=0.00001)
fn test_spec() -> InstrumentSpec {
    InstrumentSpec::new(2, 5, 10, 100, 0).unwrap()
}

/// Default book configuration for testing
fn test_config() -> BookConfig {
    BookConfig::new(1024, 100, 10).unwrap()
}

/// Generate a valid Price (non-negative, tick-aligned)
fn arb_price() -> impl Strategy<Value = Price> {
    (0..1_000_000i64)
        .prop_map(|units| Price::from_units(units * 10).unwrap()) // tick size = 10
        .prop_filter("valid price", |p| p.units() >= 0)
}

/// Generate a valid Qty (non-negative, lot-aligned)
fn arb_qty() -> impl Strategy<Value = Qty> {
    (0..1_000_000i64)
        .prop_map(|units| Qty::from_units(units * 100).unwrap()) // lot size = 100
        .prop_filter("valid qty", |q| q.units() >= 0)
}

/// Generate a non-removal level
fn arb_regular_level() -> impl Strategy<Value = Level> {
    (arb_price(), arb_qty())
        .prop_map(|(price, qty)| Level::new(price, qty))
        .prop_filter("non-zero qty", |l| !l.is_removal())
}

/// Generate a removal level (qty = 0)
fn arb_removal_level() -> impl Strategy<Value = Level> {
    arb_price()
        .prop_map(|price| Level::new(price, Qty::ZERO))
        .prop_filter("is removal", |l| l.is_removal())
}

/// Generate a level (regular or removal)
fn arb_any_level() -> impl Strategy<Value = Level> {
    prop_oneof![
        8 => arb_regular_level(),
        2 => arb_removal_level(),
    ]
}

/// Generate a vector of levels
fn arb_levels() -> impl Strategy<Value = Vec<Level>> {
    prop::collection::vec(arb_any_level(), 0..20)
}

/// Generate a snapshot (two sides with regular levels only, non-crossed)
fn arb_snapshot() -> impl Strategy<Value = (Vec<Level>, Vec<Level>)> {
    (
        prop::collection::vec(arb_regular_level(), 0..10),
        prop::collection::vec(arb_regular_level(), 0..10),
    )
        .prop_filter("non-crossed snapshot", |(bids, asks)| {
            let best_bid = bids.iter().map(|l| l.price.units()).max().unwrap_or(i64::MIN);
            let best_ask = asks.iter().map(|l| l.price.units()).min().unwrap_or(i64::MAX);
            best_bid < best_ask
        })
}

/// Generate a delta (two sides with any levels)
fn arb_delta() -> impl Strategy<Value = (Vec<Level>, Vec<Level>)> {
    (arb_levels(), arb_levels())
}

/// Operation to apply to both books
#[derive(Debug, Clone)]
enum Operation {
    Snapshot { bids: Vec<Level>, asks: Vec<Level> },
    Delta { bids: Vec<Level>, asks: Vec<Level> },
    Clear,
}

/// Generate a random operation
fn arb_operation() -> impl Strategy<Value = Operation> {
    prop_oneof![
        2 => arb_snapshot().prop_map(|(bids, asks)| Operation::Snapshot { bids, asks }),
        5 => arb_delta().prop_map(|(bids, asks)| Operation::Delta { bids, asks }),
        1 => Just(Operation::Clear),
    ]
}

/// Generate a sequence of operations
fn arb_operation_sequence() -> impl Strategy<Value = Vec<Operation>> {
    prop::collection::vec(arb_operation(), 1..50)
}

/// Assert that two books are equal in all observable aspects
fn assert_books_equal(optimized: &Book, oracle: &OracleBook, step: usize) {
    // best_bid
    let opt_bid = optimized.best_bid();
    let ora_bid = oracle.best_bid();
    assert_eq!(
        opt_bid, ora_bid,
        "step {step}: best_bid mismatch: opt={:?}, ora={:?}",
        opt_bid, ora_bid
    );

    // best_ask
    let opt_ask = optimized.best_ask();
    let ora_ask = oracle.best_ask();
    assert_eq!(
        opt_ask, ora_ask,
        "step {step}: best_ask mismatch: opt={:?}, ora={:?}",
        opt_ask, ora_ask
    );

    // is_crossed
    let opt_crossed = optimized.is_crossed();
    let ora_crossed = oracle.is_crossed();
    assert_eq!(opt_crossed, ora_crossed, "step {step}: is_crossed mismatch");

    // levels for various n
    for n in [1, 2, 5, 10, 20, 50] {
        let mut opt_out = vec![Level::new(Price::ZERO, Qty::ZERO); n];
        let mut ora_out = vec![Level::new(Price::ZERO, Qty::ZERO); n];

        let opt_count = optimized.levels(BookSide::Bid, n, &mut opt_out);
        let ora_count = oracle.levels(BookSide::Bid, n, &mut ora_out);
        assert_eq!(opt_count, ora_count, "step {step}: bid levels count n={n}");
        assert_eq!(
            &opt_out[..opt_count],
            &ora_out[..ora_count],
            "step {step}: bid levels n={n}"
        );

        let opt_count = optimized.levels(BookSide::Ask, n, &mut opt_out);
        let ora_count = oracle.levels(BookSide::Ask, n, &mut ora_out);
        assert_eq!(opt_count, ora_count, "step {step}: ask levels count n={n}");
        assert_eq!(
            &opt_out[..opt_count],
            &ora_out[..ora_count],
            "step {step}: ask levels n={n}"
        );
    }

    // depth_qty for various n
    for n in [1, 2, 5, 10, 20, 50] {
        let opt_depth_bid = optimized.depth_qty(BookSide::Bid, n).unwrap_or_else(|_| Qty::ZERO);
        let ora_depth_bid = oracle.depth_qty(BookSide::Bid, n).unwrap_or_else(|_| Qty::ZERO);
        assert_eq!(
            opt_depth_bid, ora_depth_bid,
            "step {step}: bid depth_qty n={n}"
        );

        let opt_depth_ask = optimized.depth_qty(BookSide::Ask, n).unwrap_or_else(|_| Qty::ZERO);
        let ora_depth_ask = oracle.depth_qty(BookSide::Ask, n).unwrap_or_else(|_| Qty::ZERO);
        assert_eq!(
            opt_depth_ask, ora_depth_ask,
            "step {step}: ask depth_qty n={n}"
        );
    }

    // walk_market for various quantities on both sides
    for qty in [
        Qty::ZERO,
        Qty::from_units(10).unwrap(),
        Qty::from_units(100).unwrap(),
        Qty::from_units(1000).unwrap(),
        Qty::from_units(10000).unwrap(),
    ] {
        let opt_plan_bid = optimized.walk_market(BookSide::Bid, qty).unwrap_or_else(|_| FillPlan::empty());
        let ora_plan_bid = oracle.walk_market(BookSide::Bid, qty).unwrap_or_else(|_| FillPlan::empty());
        assert_fill_plans_equal(&opt_plan_bid, &ora_plan_bid, BookSide::Bid, qty, step);

        let opt_plan_ask = optimized.walk_market(BookSide::Ask, qty).unwrap_or_else(|_| FillPlan::empty());
        let ora_plan_ask = oracle.walk_market(BookSide::Ask, qty).unwrap_or_else(|_| FillPlan::empty());
        assert_fill_plans_equal(&opt_plan_ask, &ora_plan_ask, BookSide::Ask, qty, step);
    }
}

/// Assert two FillPlans are equal
fn assert_fill_plans_equal(opt: &FillPlan, ora: &FillPlan, side: BookSide, qty: Qty, step: usize) {
    assert_eq!(
        opt.levels.len(),
        ora.levels.len(),
        "step {step}: fill plan levels len {side:?} qty={}",
        qty.units()
    );
    assert_eq!(
        opt.total_filled,
        ora.total_filled,
        "step {step}: total_filled {side:?} qty={}",
        qty.units()
    );
    assert_eq!(
        opt.total_notional,
        ora.total_notional,
        "step {step}: total_notional {side:?} qty={}",
        qty.units()
    );
    assert_eq!(
        opt.avg_price,
        ora.avg_price,
        "step {step}: avg_price {side:?} qty={}",
        qty.units()
    );

    for (i, (opt_lvl, ora_lvl)) in opt.levels.iter().zip(ora.levels.iter()).enumerate() {
        assert_eq!(
            opt_lvl.price,
            ora_lvl.price,
            "step {step}: fill level {i} price {side:?} qty={}",
            qty.units()
        );
        assert_eq!(
            opt_lvl.qty,
            ora_lvl.qty,
            "step {step}: fill level {i} qty {side:?} qty={}",
            qty.units()
        );
        assert_eq!(
            opt_lvl.filled_qty,
            ora_lvl.filled_qty,
            "step {step}: fill level {i} filled_qty {side:?} qty={}",
            qty.units()
        );
    }
}

/// Assert invariants hold on the optimized book
fn assert_invariants(book: &Book, step: usize) {
    // I1: Bids strictly descending, asks strictly ascending
    let mut prev_bid_price = i64::MAX;
    let mut idx = book.test_bid_best_idx();
    while let Some(i) = idx {
        let level = &book.test_bid_window()[i];
        if !level.qty.is_zero() {
            assert!(
                level.price_ticks < prev_bid_price,
                "step {step}: bids not strictly descending at index {i}"
            );
            prev_bid_price = level.price_ticks;
        }
        idx = book.test_next_occupied_bid(i);
    }

    let mut prev_ask_price = i64::MIN;
    let mut idx = book.test_ask_best_idx();
    while let Some(i) = idx {
        let level = &book.test_ask_window()[i];
        if !level.qty.is_zero() {
            assert!(
                level.price_ticks > prev_ask_price,
                "step {step}: asks not strictly ascending at index {i}"
            );
            prev_ask_price = level.price_ticks;
        }
        idx = book.test_next_occupied_ask(i);
    }

    // I2: No zero-quantity levels in windows (empty slots are allowed)
    for level in book.test_bid_window() {
        if !level.qty.is_zero() {
            // price_ticks can be 0 (valid price of 0)
            assert!(level.price_ticks >= 0, "step {step}: negative price_ticks");
        }
    }
    for level in book.test_ask_window() {
        if !level.qty.is_zero() {
            assert!(level.price_ticks >= 0, "step {step}: negative price_ticks");
        }
    }

    // I3: Not crossed (unless the last operation made it crossed)
    // We don't assert this here because crossed state is valid after a delta that crosses
    // The delta operation returns ApplyResult::Crossed in that case
}

proptest! {
    #![proptest_config(ProptestConfig {
        cases: std::env::var("PROPTEST_CASES")
            .ok()
            .and_then(|s| s.parse().ok())
            .unwrap_or(1000),
        ..ProptestConfig::default()
    })]

    #[test]
    fn prop_book_vs_oracle(ops in arb_operation_sequence()) {
        let spec = test_spec();
        let config = test_config();

        let mut optimized = Book::new(config, spec).unwrap();
        let mut oracle = OracleBook::new();

        for (step, op) in ops.iter().enumerate() {
            match op {
                Operation::Snapshot { bids, asks } => {
                    let opt_result = optimized.apply_snapshot(bids, asks);
                    let ora_result = oracle.apply_snapshot(bids, asks);
            prop_assert!(ora_result.is_ok(), "step {step}: oracle apply_snapshot failed: {:?}", ora_result);

                    // Optimized book should succeed (snapshots don't cross by construction)
                    prop_assert!(opt_result.is_ok(), "step {step}: optimized apply_snapshot failed: {:?}", opt_result);

                    // After snapshot, books should be equal
                    assert_books_equal(&optimized, &oracle, step);
                    assert_invariants(&optimized, step);
                }
                Operation::Delta { bids, asks } => {
                    let opt_result = optimized.apply_delta(bids, asks);
                    let ora_result = oracle.apply_delta(bids, asks);

                    // Results should match
                    match (opt_result, ora_result) {
                        // Both crossed
                        (Ok(ApplyResult::Crossed), Ok(ApplyResult::Crossed)) => {
                            // Both crossed - this is expected
                        }
                        // Both succeeded (no cross)
                        (Ok(opt_res), Ok(ora_res)) => {
                            prop_assert_eq!(opt_res, ora_res, "step {}: apply_delta results should match", step);
                        }
                        // Optimized succeeded, oracle failed (but not crossed)
                        (Ok(opt_res), Err(ora_err)) if !matches!(ora_err, BookError::Crossed) => {
                            prop_assert!(false, "step {}: oracle error but optimized succeeded: opt={:?}, ora={:?}", step, opt_res, ora_err);
                        }
                        // Optimized failed, oracle succeeded
                        (Err(opt_err), Ok(ora_res)) if !matches!(opt_err, BookError::Crossed) => {
                            prop_assert!(false, "step {}: optimized error but oracle succeeded: opt={:?}, ora={:?}", step, opt_err, ora_res);
                        }
                        // Both errored (same error)
                        (Err(opt_err), Err(ora_err)) => {
                            prop_assert_eq!(opt_err, ora_err, "step {}: BookError mismatch", step);
                        }
                        // Mismatched results
                        (opt_res, ora_res) => {
                            prop_assert!(false, "step {}: mismatched results: opt={:?}, ora={:?}", step, opt_res, ora_res);
                        }
                    }

                    // If not crossed, books should still be equal
                    if !optimized.is_crossed() {
                        assert_books_equal(&optimized, &oracle, step);
                        assert_invariants(&optimized, step);
                    }
                }
                Operation::Clear => {
                    optimized.clear();
                    oracle.clear();

                    assert_books_equal(&optimized, &oracle, step);
                    assert_invariants(&optimized, step);
                }
            }
        }
    }
}

// Regression tests for specific bugs found during property testing
mod regressions {
    use super::*;

    #[test]
    fn regression_crossed_book_after_delta() {
        let spec = test_spec();
        let config = test_config();

        let mut optimized = Book::new(config, spec).unwrap();
        let mut oracle = OracleBook::new();

        // Setup initial book
        optimized
            .apply_snapshot(
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
        oracle
            .apply_snapshot(
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

        // Apply delta that crosses the book
        let opt_result = optimized.apply_delta(
            &[Level::new(
                Price::from_units(1020).unwrap(),
                Qty::from_units(50).unwrap(),
            )],
            &[],
        );
        let ora_result = oracle.apply_delta(
            &[Level::new(
                Price::from_units(1020).unwrap(),
                Qty::from_units(50).unwrap(),
            )],
            &[],
        );

        assert_eq!(opt_result, Ok(ApplyResult::Crossed));
        assert_eq!(ora_result, Ok(ApplyResult::Crossed));

        // After crossing, both should be crossed
        assert!(optimized.is_crossed());
        assert!(oracle.is_crossed());
    }

    #[test]
    fn regression_recentering_preserves_levels() {
        let spec = test_spec();
        // Small window to trigger re-centering easily
        let config = BookConfig::new(64, 10, 10).unwrap();

        let mut optimized = Book::new(config, spec).unwrap();
        let mut oracle = OracleBook::new();

        // Initial snapshot near the middle
        optimized
            .apply_snapshot(
                &[Level::new(
                    Price::from_units(5000).unwrap(),
                    Qty::from_units(100).unwrap(),
                )],
                &[Level::new(
                    Price::from_units(5010).unwrap(),
                    Qty::from_units(200).unwrap(),
                )],
            )
            .unwrap();
        oracle
            .apply_snapshot(
                &[Level::new(
                    Price::from_units(5000).unwrap(),
                    Qty::from_units(100).unwrap(),
                )],
                &[Level::new(
                    Price::from_units(5010).unwrap(),
                    Qty::from_units(200).unwrap(),
                )],
            )
            .unwrap();

        assert_books_equal(&optimized, &oracle, 0);

        // Apply many deltas to move the price toward edge, triggering re-centering
        for i in 0..30 {
            let new_bid = 5000 + i * 10;
            let new_ask = 5010 + i * 10;

            let opt_result = optimized.apply_delta(
                &[Level::new(
                    Price::from_units(new_bid).unwrap(),
                    Qty::from_units(100).unwrap(),
                )],
                &[Level::new(
                    Price::from_units(new_ask).unwrap(),
                    Qty::from_units(200).unwrap(),
                )],
            );
            let ora_result = oracle.apply_delta(
                &[Level::new(
                    Price::from_units(new_bid).unwrap(),
                    Qty::from_units(100).unwrap(),
                )],
                &[Level::new(
                    Price::from_units(new_ask).unwrap(),
                    Qty::from_units(200).unwrap(),
                )],
            );

            // Compare results
            match (opt_result, ora_result) {
                // Both crossed
                (Ok(ApplyResult::Crossed), Ok(ApplyResult::Crossed)) => {
                    // Both crossed - this is expected
                }
                // Both succeeded (no cross)
                (Ok(ApplyResult::Ok), Ok(ApplyResult::Ok)) => {}
                // Both errored with same error
                (Err(opt_err), Err(ora_err)) => {
                    assert_eq!(opt_err, ora_err, "step {}: BookError mismatch", i)
                }
                // Mismatched results
                (opt_res, ora_res) => panic!(
                    "step {}: mismatched results: opt={:?}, ora={:?}",
                    i, opt_res, ora_res
                ),
            }

            if !optimized.is_crossed() {
                assert_books_equal(&optimized, &oracle, i as usize);
            }
        }

        // Should have re-centered (epoch incremented)
        assert!(optimized.epoch().id() > 0);
    }

    #[test]
    fn regression_far_levels_handling() {
        let spec = test_spec();
        // Small window so far levels are easily created
        let config = BookConfig::new(64, 10, 10).unwrap();

        let mut optimized = Book::new(config, spec).unwrap();
        let mut oracle = OracleBook::new();

        // Use a price far outside the window
        let bid_far = Level::new(
            Price::from_units(5000).unwrap(),
            Qty::from_units(100).unwrap(),
        );
        let ask_far = Level::new(
            Price::from_units(15000).unwrap(),
            Qty::from_units(200).unwrap(),
        );

        optimized.apply_snapshot(&[bid_far], &[ask_far]).unwrap();
        oracle.apply_snapshot(&[bid_far], &[ask_far]).unwrap();
        // Both should succeed - oracle returns Result, optimized returns Result

        assert_books_equal(&optimized, &oracle, 0);

        // The far levels should be stored in the BTreeMap and accessible
        assert!(optimized.best_bid().is_some());
        assert!(optimized.best_ask().is_some());
    }

    #[test]
    fn regression_zero_qty_removal() {
        let spec = test_spec();
        let config = test_config();

        let mut optimized = Book::new(config, spec).unwrap();
        let mut oracle = OracleBook::new();

        optimized
            .apply_snapshot(
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
        oracle
            .apply_snapshot(
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

        // Remove bid level (qty = 0)
        optimized
            .apply_delta(
                &[Level::new(Price::from_units(1000).unwrap(), Qty::ZERO)],
                &[],
            )
            .unwrap();
        oracle
            .apply_delta(
                &[Level::new(Price::from_units(1000).unwrap(), Qty::ZERO)],
                &[],
            )
            .unwrap();

        assert_eq!(optimized.best_bid(), None);
        assert_eq!(oracle.best_bid(), None);
        assert_books_equal(&optimized, &oracle, 0);
    }

    #[test]
    fn regression_walk_market_partial_fill() {
        let spec = test_spec();
        let config = test_config();

        let mut optimized = Book::new(config, spec).unwrap();
        let mut oracle = OracleBook::new();

        optimized
            .apply_snapshot(
                &[
                    Level::new(
                        Price::from_units(1000).unwrap(),
                        Qty::from_units(100).unwrap(),
                    ),
                    Level::new(
                        Price::from_units(990).unwrap(),
                        Qty::from_units(200).unwrap(),
                    ),
                ],
                &[
                    Level::new(
                        Price::from_units(1010).unwrap(),
                        Qty::from_units(100).unwrap(),
                    ),
                    Level::new(
                        Price::from_units(1020).unwrap(),
                        Qty::from_units(200).unwrap(),
                    ),
                ],
            )
            .unwrap();
        oracle
            .apply_snapshot(
                &[
                    Level::new(
                        Price::from_units(1000).unwrap(),
                        Qty::from_units(100).unwrap(),
                    ),
                    Level::new(
                        Price::from_units(990).unwrap(),
                        Qty::from_units(200).unwrap(),
                    ),
                ],
                &[
                    Level::new(
                        Price::from_units(1010).unwrap(),
                        Qty::from_units(100).unwrap(),
                    ),
                    Level::new(
                        Price::from_units(1020).unwrap(),
                        Qty::from_units(200).unwrap(),
                    ),
                ],
            )
            .unwrap();

        // Buy 150 units (walk asks) - partial fill of second level
        let opt_plan = optimized.walk_market(BookSide::Ask, Qty::from_units(150).unwrap()).unwrap();
        let ora_plan = oracle.walk_market(BookSide::Ask, Qty::from_units(150).unwrap()).unwrap();

        assert_fill_plans_equal(
            &opt_plan,
            &ora_plan,
            BookSide::Ask,
            Qty::from_units(150).unwrap(),
            0,
        );

        // Verify the partial fill
        assert_eq!(opt_plan.total_filled.units(), 150);
        assert_eq!(opt_plan.levels.len(), 2);
        assert_eq!(opt_plan.levels[0].price.units(), 1010);
        assert_eq!(opt_plan.levels[0].filled_qty.units(), 100);
        assert_eq!(opt_plan.levels[1].price.units(), 1020);
        assert_eq!(opt_plan.levels[1].filled_qty.units(), 50);
    }
}
