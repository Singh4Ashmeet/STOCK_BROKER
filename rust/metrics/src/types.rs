//! Output types for metrics snapshots
//!
//! All analytics values are `f64` (mid, microprice, imbalance, VWAP).
//! Integer prices/quantities remain as `Price`/`Qty` (no floats for money).
//! When the book is invalid or a side is empty, `valid = false`.

use sb_core::{Price, Qty};

/// Snapshot of BBO (best bid/offer) derived metrics.
///
/// All f64 fields are only meaningful when `valid == true`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BboSnapshot {
    /// Best bid price (None if bid side empty)
    pub best_bid: Option<Price>,
    /// Best bid quantity
    pub best_bid_qty: Option<Qty>,
    /// Best ask price (None if ask side empty)
    pub best_ask: Option<Price>,
    /// Best ask quantity
    pub best_ask_qty: Option<Qty>,
    /// Spread in price units (ba - bb); only valid when both sides present
    pub spread_units: i64,
    /// Mid price: (bb + ba) / 2 as f64 in price units
    pub mid: f64,
    /// Microprice: (bb*qa + ba*qb) / (qa+qb) as f64 in price units
    pub microprice: f64,
    /// Whether these values are valid (both sides present, not crossed)
    pub valid: bool,
}

impl Default for BboSnapshot {
    fn default() -> Self {
        Self {
            best_bid: None,
            best_bid_qty: None,
            best_ask: None,
            best_ask_qty: None,
            spread_units: 0,
            mid: 0.0,
            microprice: 0.0,
            valid: false,
        }
    }
}

/// Snapshot of depth and imbalance metrics.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct DepthSnapshot {
    /// Total bid quantity at top N levels
    pub bid_depth: Qty,
    /// Total ask quantity at top N levels
    pub ask_depth: Qty,
    /// Number of depth levels used for computation
    pub depth_levels: usize,
    /// Order-book imbalance: (Db - Da) / (Db + Da), in [-1, 1]
    /// Defined as 0.0 when Db + Da == 0
    pub imbalance: f64,
    /// Whether these values are valid
    pub valid: bool,
}

impl Default for DepthSnapshot {
    fn default() -> Self {
        Self {
            bid_depth: Qty::ZERO,
            ask_depth: Qty::ZERO,
            depth_levels: 0,
            imbalance: 0.0,
            valid: false,
        }
    }
}

/// Snapshot of rolling VWAP.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct VwapSnapshot {
    /// VWAP in price units (f64)
    pub vwap: f64,
    /// Window duration in nanoseconds
    pub window_ns: i64,
    /// Number of trades in the window
    pub trade_count: u32,
    /// Whether this value is valid (at least one trade in window)
    pub valid: bool,
}

impl Default for VwapSnapshot {
    fn default() -> Self {
        Self {
            vwap: 0.0,
            window_ns: 0,
            trade_count: 0,
            valid: false,
        }
    }
}

/// Snapshot of trade-flow imbalance.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TradeFlowSnapshot {
    /// Trade-flow imbalance: (Vbuy - Vsell) / (Vbuy + Vsell), in [-1, 1]
    /// Defined as 0.0 when Vbuy + Vsell == 0
    pub imbalance: f64,
    /// Total buy volume in the window (qty units)
    pub buy_volume: i64,
    /// Total sell volume in the window (qty units)
    pub sell_volume: i64,
    /// Window duration in nanoseconds
    pub window_ns: i64,
    /// Number of trades in the window
    pub trade_count: u32,
    /// Whether this value is valid (at least one trade in window)
    pub valid: bool,
}

impl Default for TradeFlowSnapshot {
    fn default() -> Self {
        Self {
            imbalance: 0.0,
            buy_volume: 0,
            sell_volume: 0,
            window_ns: 0,
            trade_count: 0,
            valid: false,
        }
    }
}

/// Combined metrics snapshot (all metrics at a point in time).
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct MetricsSnapshot {
    /// BBO-derived metrics
    pub bbo: BboSnapshot,
    /// Depth and imbalance metrics
    pub depth: DepthSnapshot,
    /// Rolling VWAP
    pub vwap: VwapSnapshot,
    /// Trade-flow imbalance
    pub trade_flow: TradeFlowSnapshot,
}
