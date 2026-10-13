//! Trade-flow imbalance with event-time rolling window.
//!
//! ## Formula
//!
//! `TFI(T) = (V_buy - V_sell) / (V_buy + V_sell)` using the aggressor flag,
//! over trades in the last `T` nanoseconds of event time.
//!
//! ## Complexity
//!
//! - `record_trade`: amortized O(1), bounded by ring buffer capacity
//! - `snapshot`: O(1)
//!
//! ## Allocation
//!
//! None after construction.

use sb_core::{Aggressor, Qty, TsNs};

use crate::types::TradeFlowSnapshot;

/// A single entry in the trade-flow ring buffer.
#[derive(Debug, Clone, Copy)]
struct FlowEntry {
    /// Event time
    event_ts: TsNs,
    /// Quantity in qty units
    qty_units: i64,
    /// Aggressor side
    aggressor: Aggressor,
}

impl Default for FlowEntry {
    fn default() -> Self {
        Self {
            event_ts: TsNs::from_nanos(0),
            qty_units: 0,
            aggressor: Aggressor::Buy,
        }
    }
}

/// Rolling trade-flow imbalance calculator.
#[derive(Debug)]
pub struct TradeFlowImbalance {
    /// Ring buffer of trade entries
    entries: Vec<FlowEntry>,
    /// Capacity
    capacity: usize,
    /// Write cursor
    write_idx: usize,
    /// Number of entries stored
    count: usize,
    /// Window duration in nanoseconds
    window_ns: i64,

    // Running sums
    /// Sum of buy quantities in the window
    sum_buy: i128,
    /// Sum of sell quantities in the window
    sum_sell: i128,
    /// Number of trades in window
    trade_count: u32,
    /// Latest event timestamp
    latest_ts: TsNs,
}

impl TradeFlowImbalance {
    /// Create a new trade-flow imbalance calculator.
    ///
    /// # Arguments
    ///
    /// * `window_ns` - Window duration in nanoseconds
    /// * `capacity` - Maximum number of trade entries
    pub fn new(window_ns: i64, capacity: usize) -> Self {
        let cap = capacity.max(1);
        Self {
            entries: vec![FlowEntry::default(); cap],
            capacity: cap,
            write_idx: 0,
            count: 0,
            window_ns,
            sum_buy: 0,
            sum_sell: 0,
            trade_count: 0,
            latest_ts: TsNs::from_nanos(0),
        }
    }

    /// Record a trade.
    #[allow(clippy::arithmetic_side_effects)]
    pub fn record_trade(&mut self, event_ts: TsNs, qty: Qty, aggressor: Aggressor) {
        let entry = FlowEntry {
            event_ts,
            qty_units: qty.units(),
            aggressor,
        };

        self.entries[self.write_idx] = entry;
        self.write_idx = (self.write_idx + 1) % self.capacity;
        if self.count < self.capacity {
            self.count += 1;
        }

        if event_ts > self.latest_ts {
            self.latest_ts = event_ts;
        }

        self.recompute_sums();
    }

    /// Get the current trade-flow imbalance snapshot.
    #[allow(
        clippy::float_arithmetic,
        clippy::cast_precision_loss,
        clippy::as_conversions,
        clippy::cast_possible_truncation
    )]
    pub fn snapshot(&self) -> TradeFlowSnapshot {
        if self.trade_count == 0 {
            return TradeFlowSnapshot {
                imbalance: 0.0,
                buy_volume: 0,
                sell_volume: 0,
                window_ns: self.window_ns,
                trade_count: 0,
                valid: false,
            };
        }

        let sum = self.sum_buy.saturating_add(self.sum_sell);
        let imbalance = if sum == 0 {
            0.0
        } else {
            let diff = self.sum_buy.saturating_sub(self.sum_sell);
            diff as f64 / sum as f64
        };

        // Truncate i128 sums to i64 for the snapshot (these are qty sums, should fit)
        let buy_volume = i64::try_from(self.sum_buy).unwrap_or(i64::MAX);
        let sell_volume = i64::try_from(self.sum_sell).unwrap_or(i64::MAX);

        TradeFlowSnapshot {
            imbalance,
            buy_volume,
            sell_volume,
            window_ns: self.window_ns,
            trade_count: self.trade_count,
            valid: true,
        }
    }

    /// Clear all entries.
    pub fn clear(&mut self) {
        self.entries.fill(FlowEntry::default());
        self.write_idx = 0;
        self.count = 0;
        self.sum_buy = 0;
        self.sum_sell = 0;
        self.trade_count = 0;
        self.latest_ts = TsNs::from_nanos(0);
    }

    /// Recompute running sums.
    #[allow(clippy::arithmetic_side_effects)]
    fn recompute_sums(&mut self) {
        let cutoff_ns = self.latest_ts.as_nanos() - self.window_ns;
        let cutoff = TsNs::from_nanos(cutoff_ns);

        let mut sum_buy: i128 = 0;
        let mut sum_sell: i128 = 0;
        let mut trade_count: u32 = 0;

        for i in 0..self.count {
            let entry = &self.entries[i];
            if entry.event_ts >= cutoff {
                match entry.aggressor {
                    Aggressor::Buy => sum_buy += i128::from(entry.qty_units),
                    Aggressor::Sell => sum_sell += i128::from(entry.qty_units),
                }
                trade_count += 1;
            }
        }

        self.sum_buy = sum_buy;
        self.sum_sell = sum_sell;
        self.trade_count = trade_count;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sb_core::{Aggressor, Qty, TsNs};

    #[test]
    fn empty_flow() {
        let flow = TradeFlowImbalance::new(1_000_000_000, 100);
        let snap = flow.snapshot();
        assert!(!snap.valid);
    }

    #[test]
    fn single_buy() {
        let mut flow = TradeFlowImbalance::new(10_000_000_000, 100);
        flow.record_trade(
            TsNs::from_nanos(1_000_000_000),
            Qty::from_units(100).unwrap(),
            Aggressor::Buy,
        );
        let snap = flow.snapshot();
        assert!(snap.valid);
        assert!((snap.imbalance - 1.0).abs() < 1e-9);
        assert_eq!(snap.buy_volume, 100);
        assert_eq!(snap.sell_volume, 0);
    }

    #[test]
    fn single_sell() {
        let mut flow = TradeFlowImbalance::new(10_000_000_000, 100);
        flow.record_trade(
            TsNs::from_nanos(1_000_000_000),
            Qty::from_units(100).unwrap(),
            Aggressor::Sell,
        );
        let snap = flow.snapshot();
        assert!(snap.valid);
        assert!((snap.imbalance - (-1.0)).abs() < 1e-9);
    }

    #[test]
    fn balanced_flow() {
        let mut flow = TradeFlowImbalance::new(10_000_000_000, 100);
        flow.record_trade(
            TsNs::from_nanos(1_000_000_000),
            Qty::from_units(100).unwrap(),
            Aggressor::Buy,
        );
        flow.record_trade(
            TsNs::from_nanos(2_000_000_000),
            Qty::from_units(100).unwrap(),
            Aggressor::Sell,
        );
        let snap = flow.snapshot();
        assert!(snap.valid);
        assert!((snap.imbalance - 0.0).abs() < 1e-9);
    }

    #[test]
    fn eviction() {
        let mut flow = TradeFlowImbalance::new(2_000_000_000, 100); // 2s window

        flow.record_trade(
            TsNs::from_nanos(1_000_000_000),
            Qty::from_units(100).unwrap(),
            Aggressor::Buy,
        );
        flow.record_trade(
            TsNs::from_nanos(4_000_000_000),
            Qty::from_units(200).unwrap(),
            Aggressor::Sell,
        );

        let snap = flow.snapshot();
        assert!(snap.valid);
        assert_eq!(snap.trade_count, 1); // Only t=4s trade
        assert!((snap.imbalance - (-1.0)).abs() < 1e-9);
    }

    #[test]
    fn imbalance_range() {
        let mut flow = TradeFlowImbalance::new(10_000_000_000, 100);
        flow.record_trade(
            TsNs::from_nanos(1_000_000_000),
            Qty::from_units(100).unwrap(),
            Aggressor::Buy,
        );
        flow.record_trade(
            TsNs::from_nanos(2_000_000_000),
            Qty::from_units(300).unwrap(),
            Aggressor::Sell,
        );
        let snap = flow.snapshot();
        assert!(snap.imbalance >= -1.0);
        assert!(snap.imbalance <= 1.0);
        // (100 - 300) / (100 + 300) = -200 / 400 = -0.5
        assert!((snap.imbalance - (-0.5)).abs() < 1e-9);
    }
}
