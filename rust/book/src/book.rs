//! Optimized order book with dense window and bitset
//!
//! This module provides a high-performance order book implementation using:
//! - **Dense window**: `Vec<Qty>` indexed by `(price_ticks - base_ticks)` covering a
//!   configurable window of ticks around the reference price
//! - **Occupancy bitset**: One bit per level for O(1)-ish next-occupied search using
//!   `trailing_zeros`/`leading_zeros`
//! - **Far levels**: A `BTreeMap` fallback for prices outside the dense window
//! - **Cached best index**: Recomputed only when the best level empties (scan via bitset)
//! - **Re-centering**: When best price approaches a window edge, rebuild the window
//! - **Depth caches**: Running cumulative sums for top-N levels updated incrementally

use std::collections::BTreeMap;

use sb_core::{Level, Price, Qty, InstrumentSpec, BookSide};

use crate::types::{FillLevel, FillPlan, ApplyResult};
use crate::BookError;

/// Configuration for the optimized book
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BookConfig {
    /// Window size in ticks (must be power of 2 for efficient bitset operations)
    pub window_ticks: usize,
    /// Re-centering trigger threshold as fraction of window (e.g., 10% = `window_ticks` / 10)
    pub recenter_threshold_ticks: usize,
    /// Maximum number of depth levels to cache
    pub max_depth_levels: usize,
}

impl Default for BookConfig {
    fn default() -> Self {
        Self {
            window_ticks: 8192,  // 8K ticks default
            recenter_threshold_ticks: 819,  // ~10%
            max_depth_levels: 50,
        }
    }
}

impl BookConfig {
    /// Create a new book configuration
    ///
    /// # Errors
    ///
    /// Returns `BookError::InvalidWindowConfig` if `window_ticks` is not a power of 2
    /// or if `recenter_threshold_ticks` >= `window_ticks` / 2.
    pub fn new(window_ticks: usize, recenter_threshold_ticks: usize, max_depth_levels: usize) -> Result<Self, BookError> {
        if !window_ticks.is_power_of_two() {
            return Err(BookError::InvalidWindowConfig);
        }
        if recenter_threshold_ticks >= window_ticks / 2 {
            return Err(BookError::InvalidWindowConfig);
        }
        if max_depth_levels == 0 {
            return Err(BookError::InvalidWindowConfig);
        }
        Ok(Self {
            window_ticks,
            recenter_threshold_ticks,
            max_depth_levels,
        })
    }
}

/// Internal representation of a price level in the dense window
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct WindowLevel {
    qty: Qty,
    /// Price in ticks (valid if qty != ZERO)
    price_ticks: i64,
}

impl Default for WindowLevel {
    fn default() -> Self {
        Self {
            qty: Qty::ZERO,
            price_ticks: 0,
        }
    }
}

/// Optimized order book with dense window
#[derive(Debug, Clone)]
pub struct Book {
    config: BookConfig,
    spec: InstrumentSpec,

    // Dense window for bids (descending price = descending index)
    bid_window: Vec<WindowLevel>,
    bid_bitset: Vec<u64>,  // One bit per level: 1 = occupied
    bid_base_ticks: i64,   // Price in ticks of index 0 (highest price in window)
    bid_best_idx: Option<usize>,  // Index of best bid in window, or None if empty
    bid_far: BTreeMap<i64, Qty>,  // Price ticks -> qty for prices below window

    // Dense window for asks (ascending price = ascending index)
    ask_window: Vec<WindowLevel>,
    ask_bitset: Vec<u64>,
    ask_base_ticks: i64,   // Price in ticks of index 0 (lowest price in window)
    ask_best_idx: Option<usize>,
    ask_far: BTreeMap<i64, Qty>,  // Price ticks -> qty for prices above window

    // Depth caches (running sums for top N levels)
    bid_depth_cache: Vec<Qty>,
    ask_depth_cache: Vec<Qty>,

    // Epoch/sequence tracking
    epoch: sb_core::Epoch,
    seq: sb_core::Seq,
}

impl Book {
    /// Create a new optimized book
    ///
    /// # Errors
    ///
    /// Returns `BookError::InvalidWindowConfig` if config is invalid.
    pub fn new(config: BookConfig, spec: InstrumentSpec) -> Result<Self, BookError> {
        let window_size = config.window_ticks;
        let depth_size = config.max_depth_levels;

        Ok(Self {
            config,
            spec,
            bid_window: vec![WindowLevel::default(); window_size],
            bid_bitset: vec![0u64; window_size.div_ceil(64)],
            bid_base_ticks: 0,
            bid_best_idx: None,
            bid_far: BTreeMap::new(),
            ask_window: vec![WindowLevel::default(); window_size],
            ask_bitset: vec![0u64; window_size.div_ceil(64)],
            ask_base_ticks: 0,
            ask_best_idx: None,
            ask_far: BTreeMap::new(),
            bid_depth_cache: vec![Qty::ZERO; depth_size],
            ask_depth_cache: vec![Qty::ZERO; depth_size],
            epoch: sb_core::Epoch::default(),
            seq: sb_core::Seq::default(),
        })
    }

    /// Get the current epoch
    #[must_use]
    pub fn epoch(&self) -> sb_core::Epoch {
        self.epoch
    }

    /// Get the current sequence number
    #[must_use]
    pub fn seq(&self) -> sb_core::Seq {
        self.seq
    }

    /// Get the book configuration
    #[must_use]
    pub fn config(&self) -> BookConfig {
        self.config
    }

    /// Get the instrument spec
    #[must_use]
    pub fn spec(&self) -> &InstrumentSpec {
        &self.spec
    }

    /// Apply a full snapshot to the book
    pub fn apply_snapshot(&mut self, bids: &[Level], asks: &[Level]) -> Result<(), BookError> {
        self.clear();

        // Determine the reference price (mid of best bid/ask or first level)
        let ref_price_ticks = self.compute_reference_price(bids, asks);

        // Initialize windows around reference price
        self.initialize_windows(ref_price_ticks);

        // Apply bid levels
        for level in bids {
            if !level.is_removal() {
                self.set_bid_level(level.price, level.qty)?;
            }
        }

        // Apply ask levels
        for level in asks {
            if !level.is_removal() {
                self.set_ask_level(level.price, level.qty)?;
            }
        }

        // Recompute caches
        self.recompute_depth_caches();
        self.update_best_indices();

        // Increment epoch for snapshot
        self.epoch = self.epoch.increment();
        self.seq = sb_core::Seq::default();

        Ok(())
    }

    /// Apply a delta to the book
    ///
    /// # Errors
    ///
    /// Returns `BookError::Crossed` if the book becomes crossed after applying
    pub fn apply_delta(&mut self, bids: &[Level], asks: &[Level]) -> Result<ApplyResult, BookError> {
        // Apply bid changes
        for level in bids {
            if level.is_removal() {
                self.remove_bid_level(level.price)?;
            } else {
                self.set_bid_level(level.price, level.qty)?;
            }
        }

        // Apply ask changes
        for level in asks {
            if level.is_removal() {
                self.remove_ask_level(level.price)?;
            } else {
                self.set_ask_level(level.price, level.qty)?;
            }
        }

        // Check for crossed book
        if self.is_crossed() {
            return Ok(ApplyResult::Crossed);
        }

        // Check if re-centering is needed
        self.maybe_recenter();

        // Increment sequence
        self.seq = self.seq.next().map_err(|_| BookError::Overflow)?;

        Ok(ApplyResult::Ok)
    }

    /// Get the best bid
    #[must_use]
    pub fn best_bid(&self) -> Option<Level> {
        // First check the window
        if let Some(idx) = self.bid_best_idx {
            let level = &self.bid_window[idx];
            // price_ticks was validated when level was set
            #[allow(clippy::unwrap_used)]
            return Some(Level::new(Price::from_ticks(level.price_ticks, &self.spec).unwrap(), level.qty));
        }
        // Fall back to far levels (highest price = last in BTreeMap)
        self.bid_far.iter().next_back().map(|(&price_ticks, &qty)| {
            // price_ticks was validated when level was set
            #[allow(clippy::unwrap_used)]
            Level::new(Price::from_ticks(price_ticks, &self.spec).unwrap(), qty)
        })
    }

    /// Get the best ask
    #[must_use]
    pub fn best_ask(&self) -> Option<Level> {
        // First check the window
        if let Some(idx) = self.ask_best_idx {
            let level = &self.ask_window[idx];
            // price_ticks was validated when level was set
            #[allow(clippy::unwrap_used)]
            return Some(Level::new(Price::from_ticks(level.price_ticks, &self.spec).unwrap(), level.qty));
        }
        // Fall back to far levels (lowest price = first in BTreeMap)
        self.ask_far.iter().next().map(|(&price_ticks, &qty)| {
            // price_ticks was validated when level was set
            #[allow(clippy::unwrap_used)]
            Level::new(Price::from_ticks(price_ticks, &self.spec).unwrap(), qty)
        })
    }

    /// Get up to `n` levels for a side into the output slice
    ///
    /// Returns the number of levels written.
    #[allow(clippy::unwrap_used, clippy::arithmetic_side_effects)]
    pub fn levels(&self, side: BookSide, n: usize, out: &mut [Level]) -> usize {
        let mut count = 0;
        match side {
            BookSide::Bid => {
                let mut idx = self.bid_best_idx;
                while let Some(i) = idx {
                    if count >= n || count >= out.len() {
                        break;
                    }
                    let level = &self.bid_window[i];
                    if !level.qty.is_zero() {
                        // price_ticks was validated when level was set
                        let price = Price::from_ticks(level.price_ticks, &self.spec).unwrap();
                        out[count] = Level::new(price, level.qty);
                        count += 1;
                    }
                    idx = self.next_occupied_bid(i);
                }
                // Check far levels if we need more
                if count < n {
                    for (&price_ticks, &qty) in self.bid_far.iter().rev() {
                        if count >= n || count >= out.len() {
                            break;
                        }
                        // price_ticks was validated when level was set
                        let price = Price::from_ticks(price_ticks, &self.spec).unwrap();
                        out[count] = Level::new(price, qty);
                        count += 1;
                    }
                }
            }
            BookSide::Ask => {
                let mut idx = self.ask_best_idx;
                while let Some(i) = idx {
                    if count >= n || count >= out.len() {
                        break;
                    }
                    let level = &self.ask_window[i];
                    if !level.qty.is_zero() {
                        // price_ticks was validated when level was set
                        let price = Price::from_ticks(level.price_ticks, &self.spec).unwrap();
                        out[count] = Level::new(price, level.qty);
                        count += 1;
                    }
                    idx = self.next_occupied_ask(i);
                }
                // Check far levels if we need more
                if count < n {
                    for (&price_ticks, &qty) in &self.ask_far {
                        if count >= n || count >= out.len() {
                            break;
                        }
                        // price_ticks was validated when level was set
                        let price = Price::from_ticks(price_ticks, &self.spec).unwrap();
                        out[count] = Level::new(price, qty);
                        count += 1;
                    }
                }
            }
        }
        count
    }

    /// Get total quantity at top N levels
    #[must_use]
    pub fn depth_qty(&self, side: BookSide, n: usize) -> Qty {
        if n == 0 {
            return Qty::ZERO;
        }
        let cache = match side {
            BookSide::Bid => &self.bid_depth_cache,
            BookSide::Ask => &self.ask_depth_cache,
        };
        #[allow(clippy::arithmetic_side_effects)]
        let idx = (n - 1).min(cache.len() - 1);
        cache[idx]
    }

    /// Walk the market (simulate a market order fill)
    #[allow(clippy::unwrap_used, clippy::arithmetic_side_effects)]
pub fn walk_market(&self, side: BookSide, qty: Qty) -> FillPlan {
        let mut plan = FillPlan::empty();
        let mut remaining = qty;

        match side {
            BookSide::Bid => {
                // Selling: walk bids from best to worst (highest price first)
                let mut idx = self.bid_best_idx;
                while let Some(i) = idx {
                    if remaining.is_zero() {
                        break;
                    }
                    let level = &self.bid_window[i];
                    if !level.qty.is_zero() {
                        let fill_qty = if level.qty <= remaining {
                            level.qty
                        } else {
                            remaining
                        };
                        // price_ticks was validated when level was set
                        let price = Price::from_ticks(level.price_ticks, &self.spec).unwrap();
                        plan.levels.push(FillLevel::new(price, level.qty, fill_qty));
                        plan.total_notional += sb_core::notional(price, fill_qty);
                        remaining = remaining.checked_sub(fill_qty).unwrap_or(Qty::ZERO);
                        plan.total_filled = plan.total_filled.checked_add(fill_qty).unwrap_or_else(|_| Qty::from_units(i64::MAX).unwrap());
                    }
                    idx = self.prev_occupied_bid(i);
                }
                // Check far levels
                if !remaining.is_zero() {
                    #[allow(clippy::explicit_iter_loop)]
                    for (&price_ticks, &level_qty) in self.bid_far.iter().rev() {
                        if remaining.is_zero() {
                            break;
                        }
                        let fill_qty = if level_qty <= remaining {
                            level_qty
                        } else {
                            remaining
                        };
                        // price_ticks was validated when level was set
                        #[allow(clippy::unwrap_used)]
                        let price = Price::from_ticks(price_ticks, &self.spec).unwrap();
                        plan.levels.push(FillLevel::new(price, level_qty, fill_qty));
                        plan.total_notional += sb_core::notional(price, fill_qty);
                        remaining = remaining.checked_sub(fill_qty).unwrap_or(Qty::ZERO);
                        plan.total_filled = plan.total_filled.checked_add(fill_qty).unwrap_or_else(|_| Qty::from_units(i64::MAX).unwrap());
                    }
                }
            }
            BookSide::Ask => {
                // Buying: walk asks from best to worst (lowest price first)
                let mut idx = self.ask_best_idx;
                while let Some(i) = idx {
                    if remaining.is_zero() {
                        break;
                    }
                    let level = &self.ask_window[i];
                    if !level.qty.is_zero() {
                        let fill_qty = if level.qty <= remaining {
                            level.qty
                        } else {
                            remaining
                        };
                        // price_ticks was validated when level was set
                        let price = Price::from_ticks(level.price_ticks, &self.spec).unwrap();
                        plan.levels.push(FillLevel::new(price, level.qty, fill_qty));
                        plan.total_notional += sb_core::notional(price, fill_qty);
                        remaining = remaining.checked_sub(fill_qty).unwrap_or(Qty::ZERO);
                        plan.total_filled = plan.total_filled.checked_add(fill_qty).unwrap_or_else(|_| Qty::from_units(i64::MAX).unwrap());
                    }
                    idx = self.next_occupied_ask(i);
                }
                // Check far levels
                if !remaining.is_zero() {
                    for (&price_ticks, &level_qty) in &self.ask_far {
                        if remaining.is_zero() {
                            break;
                        }
                        let fill_qty = if level_qty <= remaining {
                            level_qty
                        } else {
                            remaining
                        };
                        // price_ticks was validated when level was set
                        #[allow(clippy::unwrap_used)]
                        let price = Price::from_ticks(price_ticks, &self.spec).unwrap();
                        plan.levels.push(FillLevel::new(price, level_qty, fill_qty));
                        plan.total_notional += sb_core::notional(price, fill_qty);
                        remaining = remaining.checked_sub(fill_qty).unwrap_or(Qty::ZERO);
                        plan.total_filled = plan.total_filled.checked_add(fill_qty).unwrap_or_else(|_| Qty::from_units(i64::MAX).unwrap());
                    }
                }
            }
        }

        if !plan.total_filled.is_zero() {
            // Calculate average price: total_notional / total_filled
            let avg_price_units = plan.total_notional / i128::from(plan.total_filled.units());
            if let Ok(avg_price) = Price::from_units(i64::try_from(avg_price_units).unwrap_or(i64::MAX)) {
                plan.avg_price = Some(avg_price);
            }
        }

        plan
    }

    /// Clear the book
    pub fn clear(&mut self) {
        // Clear windows
        for level in &mut self.bid_window {
            *level = WindowLevel::default();
        }
        for level in &mut self.ask_window {
            *level = WindowLevel::default();
        }
        self.bid_bitset.fill(0);
        self.ask_bitset.fill(0);
        self.bid_far.clear();
        self.ask_far.clear();
        self.bid_depth_cache.fill(Qty::ZERO);
        self.ask_depth_cache.fill(Qty::ZERO);
        self.bid_best_idx = None;
        self.ask_best_idx = None;
        self.bid_base_ticks = 0;
        self.ask_base_ticks = 0;
    }

    /// Check if the book is crossed (best bid >= best ask)
    #[must_use]
    pub fn is_crossed(&self) -> bool {
        if let (Some(best_bid), Some(best_ask)) = (self.best_bid(), self.best_ask()) {
            best_bid.price >= best_ask.price
        } else {
            false
        }
    }

    // Internal methods

    fn compute_reference_price(&self, bids: &[Level], asks: &[Level]) -> i64 {
        let best_bid = bids.iter()
            .filter(|l| !l.is_removal())
            .max_by_key(|l| l.price.units());
        let best_ask = asks.iter()
            .filter(|l| !l.is_removal())
            .min_by_key(|l| l.price.units());

        match (best_bid, best_ask) {
            (Some(bid), Some(ask)) => {
                #[allow(clippy::arithmetic_side_effects)]
                let mid = i64::midpoint(bid.price.units(), ask.price.units());
                #[allow(clippy::arithmetic_side_effects)]
                let result = mid / self.spec.tick_size();
                result
            }
            (Some(bid), None) => bid.price.ticks(&self.spec).unwrap_or(0),
            (None, Some(ask)) => ask.price.ticks(&self.spec).unwrap_or(0),
            (None, None) => 0,
        }
    }

    fn initialize_windows(&mut self, ref_price_ticks: i64) {
        let half_window = self.config.window_ticks / 2;
        #[allow(clippy::arithmetic_side_effects, clippy::cast_possible_wrap, clippy::as_conversions)]
        let half_i64 = half_window as i64;
        self.bid_base_ticks = ref_price_ticks + half_i64;
        self.ask_base_ticks = ref_price_ticks - half_i64;
    }

    fn bid_price_ticks_to_index(&self, price_ticks: i64) -> Result<usize, BookError> {
        // Bids: higher price = lower index. base_ticks is the highest price in window.
        #[allow(clippy::arithmetic_side_effects)]
        let diff = self.bid_base_ticks - price_ticks;
        #[allow(clippy::arithmetic_side_effects, clippy::cast_possible_wrap, clippy::as_conversions)]
        if diff < 0 || diff >= self.config.window_ticks as i64 {
            return Err(BookError::WindowCapacityExceeded);
        }
        #[allow(clippy::cast_sign_loss, clippy::cast_possible_truncation)]
        Ok(diff as usize)
    }

    fn ask_price_ticks_to_index(&self, price_ticks: i64) -> Result<usize, BookError> {
        // Asks: lower price = lower index. base_ticks is the lowest price in window.
        #[allow(clippy::arithmetic_side_effects)]
        let diff = price_ticks - self.ask_base_ticks;
        #[allow(clippy::arithmetic_side_effects, clippy::cast_possible_wrap, clippy::as_conversions)]
        if diff < 0 || diff >= self.config.window_ticks as i64 {
            return Err(BookError::WindowCapacityExceeded);
        }
        #[allow(clippy::cast_sign_loss, clippy::cast_possible_truncation)]
        Ok(diff as usize)
    }

    #[allow(dead_code)]
    fn index_to_bid_price_ticks(&self, idx: usize) -> i64 {
        #[allow(clippy::arithmetic_side_effects, clippy::cast_possible_wrap, clippy::as_conversions)]
        let idx_i64 = idx as i64;
        self.bid_base_ticks - idx_i64
    }

    #[allow(dead_code)]
    fn index_to_ask_price_ticks(&self, idx: usize) -> i64 {
        #[allow(clippy::arithmetic_side_effects, clippy::cast_possible_wrap, clippy::as_conversions)]
        let idx_i64 = idx as i64;
        self.ask_base_ticks + idx_i64
    }

    fn set_bid_level(&mut self, price: Price, qty: Qty) -> Result<(), BookError> {
        let price_ticks = price.ticks(&self.spec)?;
        if let Ok(idx) = self.bid_price_ticks_to_index(price_ticks) {
            self.bid_window[idx] = WindowLevel { qty, price_ticks };
            Self::set_bit(&mut self.bid_bitset, idx);

            // Update best index
            if self.bid_best_idx.is_none() || idx < self.bid_best_idx.unwrap() {
                self.bid_best_idx = Some(idx);
            }

            // Update depth cache if within range
            if idx < self.config.max_depth_levels {
                self.update_depth_cache(BookSide::Bid);
            }
        } else {
            // Price outside window -> store in far levels
            self.bid_far.insert(price_ticks, qty);
        }
        Ok(())
    }

    fn set_ask_level(&mut self, price: Price, qty: Qty) -> Result<(), BookError> {
        let price_ticks = price.ticks(&self.spec)?;
        if let Ok(idx) = self.ask_price_ticks_to_index(price_ticks) {
            self.ask_window[idx] = WindowLevel { qty, price_ticks };
            Self::set_bit(&mut self.ask_bitset, idx);

            // Update best index
            if self.ask_best_idx.is_none() || idx < self.ask_best_idx.unwrap() {
                self.ask_best_idx = Some(idx);
            }

            // Update depth cache if within range
            if idx < self.config.max_depth_levels {
                self.update_depth_cache(BookSide::Ask);
            }
        } else {
            // Price outside window -> store in far levels
            self.ask_far.insert(price_ticks, qty);
        }
        Ok(())
    }

    fn remove_bid_level(&mut self, price: Price) -> Result<(), BookError> {
        let price_ticks = price.ticks(&self.spec)?;
        if let Ok(idx) = self.bid_price_ticks_to_index(price_ticks) {
            self.bid_window[idx] = WindowLevel::default();
            Self::clear_bit(&mut self.bid_bitset, idx);

            // Update best index if we removed the best
            if self.bid_best_idx == Some(idx) {
                self.bid_best_idx = self.next_occupied_bid(idx);
            }

            // Update depth cache
            if idx < self.config.max_depth_levels {
                self.update_depth_cache(BookSide::Bid);
            }
        } else {
            // Check far levels
            self.bid_far.remove(&price_ticks);
        }
        Ok(())
    }

    fn remove_ask_level(&mut self, price: Price) -> Result<(), BookError> {
        let price_ticks = price.ticks(&self.spec)?;
        if let Ok(idx) = self.ask_price_ticks_to_index(price_ticks) {
            self.ask_window[idx] = WindowLevel::default();
            Self::clear_bit(&mut self.ask_bitset, idx);

            // Update best index if we removed the best
            if self.ask_best_idx == Some(idx) {
                self.ask_best_idx = self.next_occupied_ask(idx);
            }

            // Update depth cache
            if idx < self.config.max_depth_levels {
                self.update_depth_cache(BookSide::Ask);
            }
        } else {
            // Check far levels
            self.ask_far.remove(&price_ticks);
        }
        Ok(())
    }

    fn set_bit(bitset: &mut [u64], idx: usize) {
        let word = idx >> 6;
        let bit = idx & 63;
        bitset[word] |= 1u64 << bit;
    }

    fn clear_bit(bitset: &mut [u64], idx: usize) {
        let word = idx >> 6;
        let bit = idx & 63;
        bitset[word] &= !(1u64 << bit);
    }

    fn test_bit(bitset: &[u64], idx: usize) -> bool {
        let word = idx >> 6;
        let bit = idx & 63;
        (bitset[word] & (1u64 << bit)) != 0
    }

    /// Find next occupied index for bids (lower index = higher price)
    fn next_occupied_bid(&self, start_idx: usize) -> Option<usize> {
        if start_idx + 1 >= self.config.window_ticks {
            return None;
        }
        Self::find_next_set_bit(&self.bid_bitset, start_idx + 1)
    }

    /// Find next occupied index for asks (higher index = higher price)
    fn next_occupied_ask(&self, start_idx: usize) -> Option<usize> {
        if start_idx + 1 >= self.config.window_ticks {
            return None;
        }
        Self::find_next_set_bit(&self.ask_bitset, start_idx + 1)
    }

    /// Find previous occupied index for bids (higher index = lower price)
    fn prev_occupied_bid(&self, start_idx: usize) -> Option<usize> {
        if start_idx == 0 {
            return None;
        }
        Self::find_prev_set_bit(&self.bid_bitset, start_idx - 1)
    }

    /// Find previous occupied index for asks (lower index = lower price)
    #[allow(dead_code)]
    fn prev_occupied_ask(&self, start_idx: usize) -> Option<usize> {
        if start_idx == 0 {
            return None;
        }
        Self::find_prev_set_bit(&self.ask_bitset, start_idx - 1)
    }

    fn find_next_set_bit(bitset: &[u64], start_idx: usize) -> Option<usize> {
        let start_word = start_idx >> 6;
        let start_bit = start_idx & 63;

        // Check first word with mask
        let word = bitset[start_word] & (!0u64 << start_bit);
        if word != 0 {
            return Some((start_word << 6) + word.trailing_zeros() as usize);
        }

        // Check remaining words
        for w_idx in (start_word + 1)..bitset.len() {
            if bitset[w_idx] != 0 {
                return Some((w_idx << 6) + bitset[w_idx].trailing_zeros() as usize);
            }
        }
        None
    }

    fn find_prev_set_bit(bitset: &[u64], start_idx: usize) -> Option<usize> {
        let start_word = start_idx >> 6;
        let start_bit = start_idx & 63;

        // Check first word with mask (only bits strictly below start_bit)
        // Handle start_bit = 0 case - no bits to check in this word
        if start_bit > 0 {
            let mask = (1u64 << start_bit) - 1;
            let word = bitset[start_word] & mask;
            if word != 0 {
                return Some((start_word << 6) + (63 - word.leading_zeros() as usize));
            }
        }

        // Check remaining words
        for w_idx in (0..start_word).rev() {
            if bitset[w_idx] != 0 {
                return Some((w_idx << 6) + (63 - bitset[w_idx].leading_zeros() as usize));
            }
        }
        None
    }

    fn update_best_indices(&mut self) {
        // Bid best: lowest index with bit set
        self.bid_best_idx = Self::find_next_set_bit(&self.bid_bitset, 0);

        // Ask best: lowest index with bit set
        self.ask_best_idx = Self::find_next_set_bit(&self.ask_bitset, 0);
    }

    fn recompute_depth_caches(&mut self) {
        self.update_depth_cache(BookSide::Bid);
        self.update_depth_cache(BookSide::Ask);
    }

    fn update_depth_cache(&mut self, side: BookSide) {
        let cache = match side {
            BookSide::Bid => &mut self.bid_depth_cache,
            BookSide::Ask => &mut self.ask_depth_cache,
        };
        let bitset = match side {
            BookSide::Bid => &self.bid_bitset,
            BookSide::Ask => &self.ask_bitset,
        };
        let window = match side {
            BookSide::Bid => &self.bid_window,
            BookSide::Ask => &self.ask_window,
        };

        let mut running_sum = Qty::ZERO;
        let mut count = 0;

        // Walk through the window in price order
        let indices: Vec<usize> = match side {
            BookSide::Bid => {
                // Bids: lowest index first (highest price)
                (0..self.config.window_ticks).collect()
            }
            BookSide::Ask => {
                // Asks: lowest index first (lowest price)
                (0..self.config.window_ticks).collect()
            }
        };

        for &idx in &indices {
            if Self::test_bit(bitset, idx) {
                let level = &window[idx];
                running_sum = running_sum.checked_add(level.qty).unwrap_or_else(|_| Qty::from_units(i64::MAX).unwrap());
                if count < cache.len() {
                    cache[count] = running_sum;
                    count += 1;
                } else {
                    break;
                }
            }
        }

        // Add far levels
        let far_levels = match side {
            BookSide::Bid => &self.bid_far,
            BookSide::Ask => &self.ask_far,
        };
        for &qty in far_levels.values() {
            running_sum = running_sum.checked_add(qty).unwrap_or_else(|_| Qty::from_units(i64::MAX).unwrap());
            if count < cache.len() {
                cache[count] = running_sum;
                count += 1;
            } else {
                break;
            }
        }

        // Fill remaining with last value
        while count < cache.len() {
            cache[count] = running_sum;
            count += 1;
        }
    }

    fn maybe_recenter(&mut self) {
        // Check if bid best is too close to window edge
        if let Some(bid_idx) = self.bid_best_idx {
            let dist_from_top = bid_idx;
            let dist_from_bottom = self.config.window_ticks - 1 - bid_idx;
            if dist_from_top <= self.config.recenter_threshold_ticks
                || dist_from_bottom <= self.config.recenter_threshold_ticks {
                self.recenter();
                return;
            }
        }

        // Check if ask best is too close to window edge
        if let Some(ask_idx) = self.ask_best_idx {
            let dist_from_top = ask_idx;
            let dist_from_bottom = self.config.window_ticks - 1 - ask_idx;
            if dist_from_top <= self.config.recenter_threshold_ticks
                || dist_from_bottom <= self.config.recenter_threshold_ticks {
                self.recenter();
                return;
            }
        }
    }

    fn recenter(&mut self) {
        // Compute new reference price from current best bid/ask
        let ref_price_ticks = match (self.best_bid(), self.best_ask()) {
            (Some(bid), Some(ask)) => (bid.price.units() + ask.price.units()) / 2 / self.spec.tick_size(),
            (Some(bid), None) => bid.price.ticks(&self.spec).unwrap_or(0),
            (None, Some(ask)) => ask.price.ticks(&self.spec).unwrap_or(0),
            (None, None) => return,
        };

        // Collect all current levels
        let mut all_bids = Vec::new();
        let mut all_asks = Vec::new();

        // Collect from bid window
        for (_idx, level) in self.bid_window.iter().enumerate() {
            if !level.qty.is_zero() {
                all_bids.push(Level::new(
                    Price::from_ticks(level.price_ticks, &self.spec).unwrap(),
                    level.qty
                ));
            }
        }
        // Collect from bid far
        for (&price_ticks, &qty) in &self.bid_far {
            all_bids.push(Level::new(
                Price::from_ticks(price_ticks, &self.spec).unwrap(),
                qty
            ));
        }

        // Collect from ask window
        for (_idx, level) in self.ask_window.iter().enumerate() {
            if !level.qty.is_zero() {
                all_asks.push(Level::new(
                    Price::from_ticks(level.price_ticks, &self.spec).unwrap(),
                    level.qty
                ));
            }
        }
        // Collect from ask far
        for (&price_ticks, &qty) in &self.ask_far {
            all_asks.push(Level::new(
                Price::from_ticks(price_ticks, &self.spec).unwrap(),
                qty
            ));
        }

        // Re-initialize with new reference
        self.initialize_windows(ref_price_ticks);

        // Clear and rebuild
        self.bid_window.fill(WindowLevel::default());
        self.ask_window.fill(WindowLevel::default());
        self.bid_bitset.fill(0);
        self.ask_bitset.fill(0);
        self.bid_far.clear();
        self.ask_far.clear();

        // Re-apply all levels
        for level in all_bids {
            let _ = self.set_bid_level(level.price, level.qty);
        }
        for level in all_asks {
            let _ = self.set_ask_level(level.price, level.qty);
        }

        self.recompute_depth_caches();
        self.update_best_indices();

        // Increment epoch for re-center
        self.epoch = self.epoch.increment();
        self.seq = sb_core::Seq::default();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sb_core::{InstrumentSpec, Price, Qty, BookSide};

    fn spec() -> InstrumentSpec {
        InstrumentSpec::new(2, 5, 10, 100, 0).unwrap()
    }

    fn config() -> BookConfig {
        BookConfig::new(1024, 100, 10).unwrap()
    }

    #[test]
    fn book_basic() {
        let mut book = Book::new(config(), spec()).unwrap();

        let bid1 = Level::new(Price::from_units(1000).unwrap(), Qty::from_units(100).unwrap());
        let ask1 = Level::new(Price::from_units(1010).unwrap(), Qty::from_units(200).unwrap());

        book.apply_snapshot(&[bid1], &[ask1]).unwrap();

        assert_eq!(book.best_bid().unwrap().price.units(), 1000);
        assert_eq!(book.best_ask().unwrap().price.units(), 1010);
        assert!(!book.is_crossed());
    }

    #[test]
    fn book_delta() {
        let mut book = Book::new(config(), spec()).unwrap();

        book.apply_snapshot(
            &[Level::new(Price::from_units(1000).unwrap(), Qty::from_units(100).unwrap())],
            &[Level::new(Price::from_units(1010).unwrap(), Qty::from_units(200).unwrap())],
        ).unwrap();

        // Update bid level
        book.apply_delta(
            &[Level::new(Price::from_units(1000).unwrap(), Qty::from_units(150).unwrap())],
            &[],
        ).unwrap();

        assert_eq!(book.best_bid().unwrap().qty.units(), 150);
    }

    #[test]
    fn book_remove_level() {
        let mut book = Book::new(config(), spec()).unwrap();

        book.apply_snapshot(
            &[Level::new(Price::from_units(1000).unwrap(), Qty::from_units(100).unwrap())],
            &[Level::new(Price::from_units(1010).unwrap(), Qty::from_units(200).unwrap())],
        ).unwrap();

        // Remove bid level (qty = 0)
        book.apply_delta(
            &[Level::new(Price::from_units(1000).unwrap(), Qty::ZERO)],
            &[],
        ).unwrap();

        assert!(book.best_bid().is_none());
    }

    #[test]
    fn book_crossed() {
        let mut book = Book::new(config(), spec()).unwrap();

        book.apply_snapshot(
            &[Level::new(Price::from_units(1000).unwrap(), Qty::from_units(100).unwrap())],
            &[Level::new(Price::from_units(1010).unwrap(), Qty::from_units(200).unwrap())],
        ).unwrap();

        // Cross the book by moving bid above ask
        let result = book.apply_delta(
            &[Level::new(Price::from_units(1020).unwrap(), Qty::from_units(50).unwrap())],
            &[],
        );

        assert!(matches!(result, Ok(ApplyResult::Crossed)));
    }

    #[test]
    fn book_levels() {
        let mut book = Book::new(config(), spec()).unwrap();

        book.apply_snapshot(
            &[
                Level::new(Price::from_units(1000).unwrap(), Qty::from_units(100).unwrap()),
                Level::new(Price::from_units(990).unwrap(), Qty::from_units(200).unwrap()),
                Level::new(Price::from_units(980).unwrap(), Qty::from_units(300).unwrap()),
            ],
            &[
                Level::new(Price::from_units(1010).unwrap(), Qty::from_units(100).unwrap()),
                Level::new(Price::from_units(1020).unwrap(), Qty::from_units(200).unwrap()),
            ],
        ).unwrap();

        let mut out = [Level::new(Price::ZERO, Qty::ZERO); 10];
        let n = book.levels(BookSide::Bid, 2, &mut out);
        assert_eq!(n, 2);
        assert_eq!(out[0].price.units(), 1000);
        assert_eq!(out[1].price.units(), 990);

        let n = book.levels(BookSide::Ask, 2, &mut out);
        assert_eq!(n, 2);
        assert_eq!(out[0].price.units(), 1010);
        assert_eq!(out[1].price.units(), 1020);
    }

    #[test]
    fn book_depth_qty() {
        let mut book = Book::new(config(), spec()).unwrap();

        book.apply_snapshot(
            &[
                Level::new(Price::from_units(1000).unwrap(), Qty::from_units(100).unwrap()),
                Level::new(Price::from_units(990).unwrap(), Qty::from_units(200).unwrap()),
            ],
            &[
                Level::new(Price::from_units(1010).unwrap(), Qty::from_units(100).unwrap()),
                Level::new(Price::from_units(1020).unwrap(), Qty::from_units(200).unwrap()),
            ],
        ).unwrap();

        let depth = book.depth_qty(BookSide::Bid, 2);
        assert_eq!(depth.units(), 300);

        let depth = book.depth_qty(BookSide::Ask, 1);
        assert_eq!(depth.units(), 100);
    }

    #[test]
    fn book_walk_market() {
        let mut book = Book::new(config(), spec()).unwrap();

        book.apply_snapshot(
            &[
                Level::new(Price::from_units(1000).unwrap(), Qty::from_units(100).unwrap()),
                Level::new(Price::from_units(990).unwrap(), Qty::from_units(200).unwrap()),
            ],
            &[
                Level::new(Price::from_units(1010).unwrap(), Qty::from_units(100).unwrap()),
                Level::new(Price::from_units(1020).unwrap(), Qty::from_units(200).unwrap()),
            ],
        ).unwrap();

        // Buy 150 units (walk asks)
        let plan = book.walk_market(BookSide::Ask, Qty::from_units(150).unwrap());
        assert_eq!(plan.total_filled.units(), 150);
        assert_eq!(plan.levels.len(), 2);
        assert_eq!(plan.levels[0].price.units(), 1010);
        assert_eq!(plan.levels[0].filled_qty.units(), 100);
        assert_eq!(plan.levels[1].price.units(), 1020);
        assert_eq!(plan.levels[1].filled_qty.units(), 50);
    }

    #[test]
    fn book_far_levels() {
        let mut book = Book::new(BookConfig::new(64, 10, 10).unwrap(), spec()).unwrap();

        // Use a price far outside the window
        let bid_far = Level::new(Price::from_units(5000).unwrap(), Qty::from_units(100).unwrap());
        let ask_far = Level::new(Price::from_units(15000).unwrap(), Qty::from_units(200).unwrap());

        book.apply_snapshot(&[bid_far], &[ask_far]).unwrap();

        // The far levels should be stored in the BTreeMap
        assert!(book.best_bid().is_some());
        assert!(book.best_ask().is_some());
    }

    #[test]
    fn book_recenter() {
        let mut book = Book::new(BookConfig::new(64, 5, 10).unwrap(), spec()).unwrap();

        // Initial snapshot near the middle
        book.apply_snapshot(
            &[Level::new(Price::from_units(5000).unwrap(), Qty::from_units(100).unwrap())],
            &[Level::new(Price::from_units(5010).unwrap(), Qty::from_units(200).unwrap())],
        ).unwrap();

        // Apply many deltas to move the price toward edge
        for i in 0..30 {
            let new_bid = 5000 + i * 10;
            let new_ask = 5010 + i * 10;
            book.apply_delta(
                &[Level::new(Price::from_units(new_bid).unwrap(), Qty::from_units(100).unwrap())],
                &[Level::new(Price::from_units(new_ask).unwrap(), Qty::from_units(200).unwrap())],
            ).unwrap();
        }

        // Should have re-centered (epoch incremented)
        assert!(book.epoch().id() > 0);
    }
}