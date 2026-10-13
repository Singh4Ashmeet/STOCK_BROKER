//! Best bid/ask, spread, mid, and microprice computation.
//!
//! All functions are pure: they take book state and return derived values.
//! No allocation, no side effects.
//!
//! ## Formulas (from skill/spec)
//!
//! - **Spread** = `ba - bb` (integer price units)
//! - **Mid** = `(bb + ba) / 2` (f64, half-tick values possible)
//! - **Microprice** = `(bb*qa + ba*qb) / (qa + qb)` (f64)
//!   - Leans toward the side with *less* resting size
//!   - Undefined if `qa + qb == 0`

use sb_core::Level;

use crate::types::BboSnapshot;

/// Compute BBO snapshot from best bid and best ask levels.
///
/// # Complexity
///
/// O(1) — no scanning, no allocation.
///
/// # Edge cases
///
/// - Either side empty → `valid = false`, spread/mid/microprice zeroed
/// - Crossed book (bb >= ba) → `valid = false`
/// - `qa + qb == 0` → microprice invalid (set to mid as fallback, `valid = false`)
#[allow(
    clippy::float_arithmetic,
    clippy::cast_precision_loss,
    clippy::as_conversions,
    clippy::arithmetic_side_effects
)]
pub fn compute_bbo(best_bid: Option<Level>, best_ask: Option<Level>) -> BboSnapshot {
    match (best_bid, best_ask) {
        (Some(bid), Some(ask)) => {
            let bb = bid.price.units();
            let ba = ask.price.units();
            let qb = bid.qty.units();
            let qa = ask.qty.units();

            // Crossed book → invalid
            if bb >= ba {
                return BboSnapshot {
                    best_bid: Some(bid.price),
                    best_bid_qty: Some(bid.qty),
                    best_ask: Some(ask.price),
                    best_ask_qty: Some(ask.qty),
                    spread_units: ba - bb, // negative or zero
                    mid: 0.0,
                    microprice: 0.0,
                    valid: false,
                };
            }

            let spread = ba - bb;
            let mid = f64::midpoint(bb as f64, ba as f64);

            let denom = qa + qb;
            let microprice = if denom == 0 {
                // Both sides have zero qty — shouldn't normally happen with
                // non-removal levels, but handle gracefully
                mid
            } else {
                // microprice = (bb * qa + ba * qb) / (qa + qb)
                // Use i128 to avoid overflow in the numerator
                let num = i128::from(bb) * i128::from(qa) + i128::from(ba) * i128::from(qb);
                num as f64 / denom as f64
            };

            // Microprice validity: we mark valid even when denom==0 and microprice==mid
            // because mid is a reasonable fallback; but check if qty sum was truly zero
            let valid = denom > 0;

            BboSnapshot {
                best_bid: Some(bid.price),
                best_bid_qty: Some(bid.qty),
                best_ask: Some(ask.price),
                best_ask_qty: Some(ask.qty),
                spread_units: spread,
                mid,
                microprice,
                valid,
            }
        }
        (Some(bid), None) => BboSnapshot {
            best_bid: Some(bid.price),
            best_bid_qty: Some(bid.qty),
            best_ask: None,
            best_ask_qty: None,
            spread_units: 0,
            mid: 0.0,
            microprice: 0.0,
            valid: false,
        },
        (None, Some(ask)) => BboSnapshot {
            best_bid: None,
            best_bid_qty: None,
            best_ask: Some(ask.price),
            best_ask_qty: Some(ask.qty),
            spread_units: 0,
            mid: 0.0,
            microprice: 0.0,
            valid: false,
        },
        (None, None) => BboSnapshot::default(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sb_core::{Level, Price, Qty};

    #[test]
    fn both_sides_present() {
        let bid = Level::new(
            Price::from_units(10000).unwrap(),
            Qty::from_units(500).unwrap(),
        );
        let ask = Level::new(
            Price::from_units(10020).unwrap(),
            Qty::from_units(300).unwrap(),
        );
        let snap = compute_bbo(Some(bid), Some(ask));
        assert!(snap.valid);
        assert_eq!(snap.spread_units, 20);
        // mid = (10000 + 10020) / 2 = 10010.0
        assert!((snap.mid - 10010.0).abs() < 1e-9);
        // microprice = (10000 * 300 + 10020 * 500) / (300 + 500)
        //            = (3_000_000 + 5_010_000) / 800
        //            = 8_010_000 / 800 = 10012.5
        assert!((snap.microprice - 10012.5).abs() < 1e-9);
    }

    #[test]
    fn bid_only() {
        let bid = Level::new(
            Price::from_units(10000).unwrap(),
            Qty::from_units(500).unwrap(),
        );
        let snap = compute_bbo(Some(bid), None);
        assert!(!snap.valid);
        assert!(snap.best_bid.is_some());
        assert!(snap.best_ask.is_none());
    }

    #[test]
    fn ask_only() {
        let ask = Level::new(
            Price::from_units(10020).unwrap(),
            Qty::from_units(300).unwrap(),
        );
        let snap = compute_bbo(None, Some(ask));
        assert!(!snap.valid);
    }

    #[test]
    fn empty_book() {
        let snap = compute_bbo(None, None);
        assert!(!snap.valid);
    }

    #[test]
    fn crossed_book() {
        let bid = Level::new(
            Price::from_units(10020).unwrap(),
            Qty::from_units(500).unwrap(),
        );
        let ask = Level::new(
            Price::from_units(10000).unwrap(),
            Qty::from_units(300).unwrap(),
        );
        let snap = compute_bbo(Some(bid), Some(ask));
        assert!(!snap.valid);
    }

    #[test]
    fn equal_spread() {
        // bb == ba → crossed
        let bid = Level::new(
            Price::from_units(10000).unwrap(),
            Qty::from_units(500).unwrap(),
        );
        let ask = Level::new(
            Price::from_units(10000).unwrap(),
            Qty::from_units(300).unwrap(),
        );
        let snap = compute_bbo(Some(bid), Some(ask));
        assert!(!snap.valid);
    }

    #[test]
    fn odd_spread() {
        // Odd spread → mid has a .5
        let bid = Level::new(
            Price::from_units(10000).unwrap(),
            Qty::from_units(100).unwrap(),
        );
        let ask = Level::new(
            Price::from_units(10001).unwrap(),
            Qty::from_units(100).unwrap(),
        );
        let snap = compute_bbo(Some(bid), Some(ask));
        assert!(snap.valid);
        assert_eq!(snap.spread_units, 1);
        assert!((snap.mid - 10000.5).abs() < 1e-9);
        // Equal sizes → microprice == mid
        assert!((snap.microprice - 10000.5).abs() < 1e-9);
    }
}
