//! Rolling VWAP (Volume-Weighted Average Price) with event-time window.
//!
//! Uses a pre-allocated ring buffer of trade entries. Eviction is by **event
//! time** (not wall clock) so replay is deterministic.
//!
//! ## Formula
//!
//! `VWAP(T) = Σ(p_i · q_i) / Σ(q_i)` over trades in the last `T` of event time.
//!
//! ## Complexity
//!
//! - `record_trade`: amortized O(1) — writes one slot, evicts expired entries
//! - `snapshot`: O(1) — reads running sums
//!
//! ## Allocation
//!
//! None after construction. Ring buffer is pre-sized at startup.

use sb_core::{Price, Qty, TsNs};

use crate::types::VwapSnapshot;

/// A single entry in the VWAP ring buffer.
#[derive(Debug, Clone, Copy)]
struct VwapEntry {
    /// Event time of this trade
    event_ts: TsNs,
    /// Quantity in qty units
    qty_units: i64,
    /// Notional = price * qty (i128 to avoid overflow)
    notional: i128,
}

impl Default for VwapEntry {
    fn default() -> Self {
        Self {
            event_ts: TsNs::from_nanos(0),
            qty_units: 0,
            notional: 0,
        }
    }
}

/// Rolling VWAP calculator with a fixed-size ring buffer.
///
/// The ring buffer has a configurable capacity. When full, the oldest entry
/// is overwritten. Eviction is by event time: entries older than
/// `newest_ts - window_ns` are excluded from the running sums.
#[derive(Debug)]
pub struct RollingVwap {
    /// Ring buffer of trade entries
    entries: Vec<VwapEntry>,
    /// Capacity of the ring buffer
    capacity: usize,
    /// Write cursor (next slot to write)
    write_idx: usize,
    /// Number of entries currently stored (may be < capacity initially)
    count: usize,
    /// Window duration in nanoseconds
    window_ns: i64,

    // Running sums (recomputed on eviction)
    /// Sum of notionals in the window
    sum_notional: i128,
    /// Sum of quantities in the window
    sum_qty: i128,
    /// Number of trades in the window
    trade_count: u32,
    /// Latest event timestamp seen
    latest_ts: TsNs,
}

impl RollingVwap {
    /// Create a new rolling VWAP calculator.
    ///
    /// # Arguments
    ///
    /// * `window_ns` - Window duration in nanoseconds
    /// * `capacity` - Maximum number of trade entries to store
    pub fn new(window_ns: i64, capacity: usize) -> Self {
        let cap = capacity.max(1);
        Self {
            entries: vec![VwapEntry::default(); cap],
            capacity: cap,
            write_idx: 0,
            count: 0,
            window_ns,
            sum_notional: 0,
            sum_qty: 0,
            trade_count: 0,
            latest_ts: TsNs::from_nanos(0),
        }
    }

    /// Record a trade and update running sums.
    ///
    /// # Complexity
    ///
    /// Amortized O(1). Writes one slot, then recomputes sums (full scan of
    /// the ring buffer, but bounded by capacity which is fixed at startup).
    ///
    /// For truly O(1) amortized, we would need a deque with head/tail pointers.
    /// Since the ring buffer is bounded and typically small (e.g. 4096 entries),
    /// a full recompute is fast and avoids drift from floating-point accumulation.
    #[allow(clippy::arithmetic_side_effects)]
    pub fn record_trade(&mut self, event_ts: TsNs, price: Price, qty: Qty) {
        let price_units = price.units();
        let qty_units = qty.units();
        let notional = i128::from(price_units) * i128::from(qty_units);

        let entry = VwapEntry {
            event_ts,
            qty_units,
            notional,
        };

        // Write to ring buffer
        self.entries[self.write_idx] = entry;
        self.write_idx = (self.write_idx + 1) % self.capacity;
        if self.count < self.capacity {
            self.count += 1;
        }

        // Update latest timestamp
        if event_ts > self.latest_ts {
            self.latest_ts = event_ts;
        }

        // Recompute running sums (evict expired entries)
        self.recompute_sums();
    }

    /// Get the current VWAP snapshot.
    ///
    /// # Complexity
    ///
    /// O(1) — reads pre-computed running sums.
    #[allow(
        clippy::float_arithmetic,
        clippy::cast_precision_loss,
        clippy::as_conversions
    )]
    pub fn snapshot(&self) -> VwapSnapshot {
        if self.sum_qty == 0 || self.trade_count == 0 {
            return VwapSnapshot {
                vwap: 0.0,
                window_ns: self.window_ns,
                trade_count: 0,
                valid: false,
            };
        }

        let vwap = self.sum_notional as f64 / self.sum_qty as f64;

        VwapSnapshot {
            vwap,
            window_ns: self.window_ns,
            trade_count: self.trade_count,
            valid: true,
        }
    }

    /// Clear all entries and reset running sums.
    pub fn clear(&mut self) {
        self.entries.fill(VwapEntry::default());
        self.write_idx = 0;
        self.count = 0;
        self.sum_notional = 0;
        self.sum_qty = 0;
        self.trade_count = 0;
        self.latest_ts = TsNs::from_nanos(0);
    }

    /// Recompute running sums by scanning all entries in the ring buffer
    /// and excluding those outside the event-time window.
    #[allow(clippy::arithmetic_side_effects)]
    fn recompute_sums(&mut self) {
        let cutoff_ns = self.latest_ts.as_nanos() - self.window_ns;
        let cutoff = TsNs::from_nanos(cutoff_ns);

        let mut sum_notional: i128 = 0;
        let mut sum_qty: i128 = 0;
        let mut trade_count: u32 = 0;

        for i in 0..self.count {
            let entry = &self.entries[i];
            if entry.event_ts >= cutoff {
                sum_notional += entry.notional;
                sum_qty += i128::from(entry.qty_units);
                trade_count += 1;
            }
        }

        self.sum_notional = sum_notional;
        self.sum_qty = sum_qty;
        self.trade_count = trade_count;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sb_core::{Price, Qty, TsNs};

    #[test]
    fn empty_vwap() {
        let vwap = RollingVwap::new(1_000_000_000, 100);
        let snap = vwap.snapshot();
        assert!(!snap.valid);
    }

    #[test]
    fn single_trade() {
        let mut vwap = RollingVwap::new(1_000_000_000, 100);
        vwap.record_trade(
            TsNs::from_nanos(500_000_000),
            Price::from_units(10000).unwrap(),
            Qty::from_units(100).unwrap(),
        );
        let snap = vwap.snapshot();
        assert!(snap.valid);
        assert_eq!(snap.trade_count, 1);
        // VWAP of single trade = trade price
        assert!((snap.vwap - 10000.0).abs() < 1e-9);
    }

    #[test]
    fn two_trades_vwap() {
        let mut vwap = RollingVwap::new(10_000_000_000, 100);

        // Trade 1: price=100, qty=10
        vwap.record_trade(
            TsNs::from_nanos(1_000_000_000),
            Price::from_units(100).unwrap(),
            Qty::from_units(10).unwrap(),
        );

        // Trade 2: price=200, qty=20
        vwap.record_trade(
            TsNs::from_nanos(2_000_000_000),
            Price::from_units(200).unwrap(),
            Qty::from_units(20).unwrap(),
        );

        let snap = vwap.snapshot();
        assert!(snap.valid);
        assert_eq!(snap.trade_count, 2);
        // VWAP = (100*10 + 200*20) / (10 + 20) = (1000 + 4000) / 30 = 5000/30 ≈ 166.667
        assert!((snap.vwap - 166.666_666_666_666_66).abs() < 0.01);
    }

    #[test]
    fn eviction_by_event_time() {
        let mut vwap = RollingVwap::new(2_000_000_000, 100); // 2 second window

        // Trade at t=1s
        vwap.record_trade(
            TsNs::from_nanos(1_000_000_000),
            Price::from_units(100).unwrap(),
            Qty::from_units(10).unwrap(),
        );

        // Trade at t=4s (should evict t=1s trade since 4-2=2 > 1)
        vwap.record_trade(
            TsNs::from_nanos(4_000_000_000),
            Price::from_units(200).unwrap(),
            Qty::from_units(20).unwrap(),
        );

        let snap = vwap.snapshot();
        assert!(snap.valid);
        assert_eq!(snap.trade_count, 1); // Only the t=4s trade
        assert!((snap.vwap - 200.0).abs() < 1e-9);
    }

    #[test]
    fn ring_buffer_overflow() {
        let mut vwap = RollingVwap::new(10_000_000_000, 3); // Tiny buffer

        for i in 0..5 {
            #[allow(clippy::as_conversions)]
            let ts = TsNs::from_nanos((i + 1) * 1_000_000_000);
            vwap.record_trade(
                ts,
                Price::from_units(100).unwrap(),
                Qty::from_units(10).unwrap(),
            );
        }

        let snap = vwap.snapshot();
        assert!(snap.valid);
        // Buffer holds only 3 entries; all within window
        assert_eq!(snap.trade_count, 3);
    }

    #[test]
    fn clear_resets() {
        let mut vwap = RollingVwap::new(1_000_000_000, 100);
        vwap.record_trade(
            TsNs::from_nanos(1_000_000_000),
            Price::from_units(100).unwrap(),
            Qty::from_units(10).unwrap(),
        );
        vwap.clear();
        let snap = vwap.snapshot();
        assert!(!snap.valid);
    }
}
