//! Depth at N levels and order-book imbalance.
//!
//! ## Formulas (from skill/spec)
//!
//! - **Depth(N)** = sum of qty over top N levels per side
//! - **Imbalance(N)** = `(D_b(N) - D_a(N)) / (D_b(N) + D_a(N))` in [-1, 1]
//!   - Defined as 0 when `D_b(N) + D_a(N) == 0`

use sb_core::Qty;

use crate::types::DepthSnapshot;

/// Compute depth and imbalance from pre-computed bid/ask depth quantities.
///
/// The caller provides the summed quantities for the top `n` levels from
/// the book's depth cache (via `Book::depth_qty`), so this function is O(1).
///
/// # Complexity
///
/// O(1) — no scanning, no allocation.
///
/// # Edge cases
///
/// - One or both sides empty (qty == 0) → imbalance saturates to ±1 or 0
/// - Both zero → imbalance = 0.0
#[allow(
    clippy::float_arithmetic,
    clippy::cast_precision_loss,
    clippy::as_conversions
)]
pub fn compute_depth(bid_depth: Qty, ask_depth: Qty, n: usize) -> DepthSnapshot {
    let db = bid_depth.units();
    let da = ask_depth.units();
    let sum = i128::from(db).saturating_add(i128::from(da));

    let imbalance = if sum == 0 {
        0.0
    } else {
        let diff = i128::from(db).saturating_sub(i128::from(da));
        diff as f64 / sum as f64
    };

    DepthSnapshot {
        bid_depth,
        ask_depth,
        depth_levels: n,
        imbalance,
        valid: true,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sb_core::Qty;

    #[test]
    fn balanced_depth() {
        let snap = compute_depth(
            Qty::from_units(100).unwrap(),
            Qty::from_units(100).unwrap(),
            5,
        );
        assert!(snap.valid);
        assert!((snap.imbalance - 0.0).abs() < 1e-9);
    }

    #[test]
    fn bid_heavy() {
        let snap = compute_depth(
            Qty::from_units(300).unwrap(),
            Qty::from_units(100).unwrap(),
            5,
        );
        assert!(snap.valid);
        // (300 - 100) / (300 + 100) = 200 / 400 = 0.5
        assert!((snap.imbalance - 0.5).abs() < 1e-9);
    }

    #[test]
    fn ask_heavy() {
        let snap = compute_depth(
            Qty::from_units(100).unwrap(),
            Qty::from_units(300).unwrap(),
            5,
        );
        assert!(snap.valid);
        // (100 - 300) / (100 + 300) = -200 / 400 = -0.5
        assert!((snap.imbalance - (-0.5)).abs() < 1e-9);
    }

    #[test]
    fn bid_only_depth() {
        let snap = compute_depth(Qty::from_units(100).unwrap(), Qty::ZERO, 5);
        assert!(snap.valid);
        assert!((snap.imbalance - 1.0).abs() < 1e-9);
    }

    #[test]
    fn ask_only_depth() {
        let snap = compute_depth(Qty::ZERO, Qty::from_units(100).unwrap(), 5);
        assert!(snap.valid);
        assert!((snap.imbalance - (-1.0)).abs() < 1e-9);
    }

    #[test]
    fn both_empty() {
        let snap = compute_depth(Qty::ZERO, Qty::ZERO, 5);
        assert!(snap.valid);
        assert!((snap.imbalance - 0.0).abs() < 1e-9);
    }

    #[test]
    fn imbalance_in_range() {
        // Arbitrary values
        let snap = compute_depth(
            Qty::from_units(12345).unwrap(),
            Qty::from_units(67890).unwrap(),
            10,
        );
        assert!(snap.imbalance >= -1.0);
        assert!(snap.imbalance <= 1.0);
    }
}
