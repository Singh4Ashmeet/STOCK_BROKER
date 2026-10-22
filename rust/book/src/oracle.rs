//! Oracle book - `BTreeMap` reference implementation for testing
//!
//! This module provides a simple, obviously-correct order book using `BTreeMap`
//! per side. It is used as a test oracle for property testing and as a baseline
//! in benchmarks. Not used in production paths.

#![allow(
    clippy::arithmetic_side_effects,
    clippy::as_conversions,
    clippy::cast_possible_truncation,
    clippy::explicit_iter_loop,
    clippy::unwrap_used,
    clippy::doc_markdown
)]

use std::collections::BTreeMap;

use crate::BookError;
use crate::types::{ApplyResult, FillLevel, FillPlan};
use sb_core::{InstrumentSpec, Level, Price, Qty};

/// Oracle book using `BTreeMap` per side (reference implementation)
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OracleBook {
    /// Bids sorted descending by price (highest first)
    bids: BTreeMap<Price, Qty>,
    /// Asks sorted ascending by price (lowest first)
    asks: BTreeMap<Price, Qty>,
    /// Optional instrument spec for tick/lot validation
    spec: Option<InstrumentSpec>,
    /// Validity flag
    is_valid: bool,
}

impl Default for OracleBook {
    fn default() -> Self {
        Self::new()
    }
}

impl OracleBook {
    /// Create a new empty oracle book without instrument spec validation
    #[must_use]
    pub fn new() -> Self {
        Self {
            bids: BTreeMap::new(),
            asks: BTreeMap::new(),
            spec: None,
            is_valid: false,
        }
    }

    /// Create a new oracle book with an authoritative instrument spec
    #[must_use]
    pub fn with_spec(spec: InstrumentSpec) -> Self {
        Self {
            bids: BTreeMap::new(),
            asks: BTreeMap::new(),
            spec: Some(spec),
            is_valid: false,
        }
    }

    /// Check if the book is in a valid, uncrossed state
    #[must_use]
    pub fn is_valid(&self) -> bool {
        self.is_valid && !self.is_crossed()
    }

    /// Apply a full snapshot to the book transactionally.
    ///
    /// # Errors
    ///
    /// Returns `BookError::Crossed` if the snapshot is crossed, or validation errors
    /// if price/quantity fail alignment checks. State is untouched on error.
    pub fn apply_snapshot(&mut self, bids: &[Level], asks: &[Level]) -> Result<(), BookError> {
        let mut temp_bids = BTreeMap::new();
        let mut temp_asks = BTreeMap::new();

        if let Some(spec) = &self.spec {
            for level in bids {
                if !level.is_removal() {
                    level.price.ticks(spec)?;
                    if !level.qty.is_lot_aligned(spec) {
                        return Err(BookError::NotLotAligned);
                    }
                }
            }
            for level in asks {
                if !level.is_removal() {
                    level.price.ticks(spec)?;
                    if !level.qty.is_lot_aligned(spec) {
                        return Err(BookError::NotLotAligned);
                    }
                }
            }
        }

        for level in bids {
            if !level.is_removal() {
                temp_bids.insert(level.price, level.qty);
            }
        }
        for level in asks {
            if !level.is_removal() {
                temp_asks.insert(level.price, level.qty);
            }
        }

        match (temp_bids.iter().next_back(), temp_asks.iter().next()) {
            (Some((&best_bid, _)), Some((&best_ask, _))) if best_bid >= best_ask => {
                return Err(BookError::Crossed);
            }
            _ => {}
        }

        self.bids = temp_bids;
        self.asks = temp_asks;
        self.is_valid = true;
        Ok(())
    }

    /// Apply a delta to the book transactionally on validation errors.
    ///
    /// # Errors
    ///
    /// Returns `BookError` if price/quantity validation fails.
    /// Returns `Ok(ApplyResult::Crossed)` if the book became crossed.
    pub fn apply_delta(
        &mut self,
        bids: &[Level],
        asks: &[Level],
    ) -> Result<ApplyResult, BookError> {
        if let Some(spec) = &self.spec {
            for level in bids {
                level.price.ticks(spec)?;
                if !level.is_removal() && !level.qty.is_lot_aligned(spec) {
                    return Err(BookError::NotLotAligned);
                }
            }
            for level in asks {
                level.price.ticks(spec)?;
                if !level.is_removal() && !level.qty.is_lot_aligned(spec) {
                    return Err(BookError::NotLotAligned);
                }
            }
        }

        let mut temp_bids = self.bids.clone();
        let mut temp_asks = self.asks.clone();

        for level in bids {
            if level.is_removal() {
                temp_bids.remove(&level.price);
            } else {
                temp_bids.insert(level.price, level.qty);
            }
        }
        for level in asks {
            if level.is_removal() {
                temp_asks.remove(&level.price);
            } else {
                temp_asks.insert(level.price, level.qty);
            }
        }

        self.bids = temp_bids;
        self.asks = temp_asks;

        if self.is_crossed() {
            self.is_valid = false;
            return Ok(ApplyResult::Crossed);
        }
        self.is_valid = true;
        Ok(ApplyResult::Ok)
    }

    /// Get the best bid (highest price)
    #[must_use]
    pub fn best_bid(&self) -> Option<Level> {
        self.bids
            .iter()
            .next_back()
            .map(|(&price, &qty)| Level::new(price, qty))
    }

    /// Get the best ask (lowest price)
    #[must_use]
    pub fn best_ask(&self) -> Option<Level> {
        self.asks
            .iter()
            .next()
            .map(|(&price, &qty)| Level::new(price, qty))
    }

    /// Get up to `n` levels for a side into the output slice
    ///
    /// Returns the number of levels written.
    pub fn levels(&self, side: sb_core::BookSide, n: usize, out: &mut [Level]) -> usize {
        let mut count = 0;
        match side {
            sb_core::BookSide::Bid => {
                for (&price, &qty) in self.bids.iter().rev().take(n) {
                    if count < out.len() {
                        out[count] = Level::new(price, qty);
                        count += 1;
                    } else {
                        break;
                    }
                }
            }
            sb_core::BookSide::Ask => {
                for (&price, &qty) in self.asks.iter().take(n) {
                    if count < out.len() {
                        out[count] = Level::new(price, qty);
                        count += 1;
                    } else {
                        break;
                    }
                }
            }
        }
        count
    }

    /// Get total quantity at top N levels without silent saturation
    ///
    /// # Errors
    ///
    /// Returns `BookError::Overflow` if sum exceeds `i64::MAX`.
    pub fn depth_qty(&self, side: sb_core::BookSide, n: usize) -> Result<Qty, BookError> {
        let mut total = Qty::ZERO;
        match side {
            sb_core::BookSide::Bid => {
                for &qty in self.bids.values().rev().take(n) {
                    total = total.checked_add(qty).map_err(|_| BookError::Overflow)?;
                }
            }
            sb_core::BookSide::Ask => {
                for &qty in self.asks.values().take(n) {
                    total = total.checked_add(qty).map_err(|_| BookError::Overflow)?;
                }
            }
        }
        Ok(total)
    }

    /// Walk the market (simulate a market order fill)
    ///
    /// # Errors
    ///
    /// Returns `BookError::Overflow` if notional or quantity exceeds arithmetic bounds.
    pub fn walk_market(&self, side: sb_core::BookSide, qty: Qty) -> Result<FillPlan, BookError> {
        if self.is_crossed() {
            return Err(BookError::Crossed);
        }
        if !self.is_valid() {
            return Err(BookError::InvalidBookState);
        }

        let mut plan = FillPlan::empty();
        let mut remaining = qty;

        match side {
            sb_core::BookSide::Bid => {
                // Selling: walk bids from best to worst (highest price first)
                for (&price, &level_qty) in self.bids.iter().rev() {
                    if remaining.is_zero() {
                        break;
                    }
                    let fill_qty = if level_qty <= remaining {
                        level_qty
                    } else {
                        remaining
                    };
                    plan.levels.push(FillLevel::new(price, level_qty, fill_qty));
                    let notional_val = sb_core::notional(price, fill_qty);
                    plan.total_notional = plan
                        .total_notional
                        .checked_add(notional_val)
                        .ok_or(BookError::Overflow)?;
                    remaining = remaining.checked_sub(fill_qty).unwrap_or(Qty::ZERO);
                    plan.total_filled = plan
                        .total_filled
                        .checked_add(fill_qty)
                        .map_err(|_| BookError::Overflow)?;
                }
            }
            sb_core::BookSide::Ask => {
                // Buying: walk asks from best to worst (lowest price first)
                for (&price, &level_qty) in self.asks.iter() {
                    if remaining.is_zero() {
                        break;
                    }
                    let fill_qty = if level_qty <= remaining {
                        level_qty
                    } else {
                        remaining
                    };
                    plan.levels.push(FillLevel::new(price, level_qty, fill_qty));
                    let notional_val = sb_core::notional(price, fill_qty);
                    plan.total_notional = plan
                        .total_notional
                        .checked_add(notional_val)
                        .ok_or(BookError::Overflow)?;
                    remaining = remaining.checked_sub(fill_qty).unwrap_or(Qty::ZERO);
                    plan.total_filled = plan
                        .total_filled
                        .checked_add(fill_qty)
                        .map_err(|_| BookError::Overflow)?;
                }
            }
        }

        if !plan.total_filled.is_zero() {
            let filled_i128 = i128::from(plan.total_filled.units());
            let avg_price_units = plan
                .total_notional
                .checked_div(filled_i128)
                .ok_or(BookError::Overflow)?;
            let avg_price_i64 = i64::try_from(avg_price_units).map_err(|_| BookError::Overflow)?;
            let avg_price = Price::from_units(avg_price_i64).map_err(|_| BookError::Overflow)?;
            plan.avg_price = Some(avg_price);
        }

        Ok(plan)
    }

    /// Clear the book
    pub fn clear(&mut self) {
        self.bids.clear();
        self.asks.clear();
        self.is_valid = true;
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

    /// Get the number of bid levels
    #[must_use]
    pub fn bid_levels(&self) -> usize {
        self.bids.len()
    }

    /// Get the number of ask levels
    #[must_use]
    pub fn ask_levels(&self) -> usize {
        self.asks.len()
    }

    /// Check if the book is empty
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.bids.is_empty() && self.asks.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sb_core::{BookSide, InstrumentSpec, Price, Qty};

    fn spec() -> InstrumentSpec {
        InstrumentSpec::new(2, 5, 10, 100, 0).unwrap()
    }

    #[test]
    fn oracle_basic() {
        let mut book = OracleBook::new();
        let _spec = spec();

        let bid1 = Level::new(
            Price::from_units(1000).unwrap(),
            Qty::from_units(100).unwrap(),
        );
        let ask1 = Level::new(
            Price::from_units(1010).unwrap(),
            Qty::from_units(200).unwrap(),
        );

        book.apply_snapshot(&[bid1], &[ask1]).unwrap();

        assert_eq!(book.best_bid().unwrap().price.units(), 1000);
        assert_eq!(book.best_ask().unwrap().price.units(), 1010);
        assert!(!book.is_crossed());
        assert!(book.is_valid());
    }

    #[test]
    fn oracle_delta() {
        let mut book = OracleBook::new();
        let _spec = spec();

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

        // Update bid level
        let res = book
            .apply_delta(
                &[Level::new(
                    Price::from_units(1000).unwrap(),
                    Qty::from_units(150).unwrap(),
                )],
                &[],
            )
            .unwrap();

        assert_eq!(res, ApplyResult::Ok);
        assert_eq!(book.best_bid().unwrap().qty.units(), 150);
        assert!(book.is_valid());
    }

    #[test]
    fn oracle_remove_level() {
        let mut book = OracleBook::new();
        let _spec = spec();

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

        // Remove bid level (qty = 0)
        book.apply_delta(
            &[Level::new(Price::from_units(1000).unwrap(), Qty::ZERO)],
            &[],
        )
        .unwrap();

        assert_eq!(book.bid_levels(), 0);
        assert!(book.best_bid().is_none());
    }

    #[test]
    fn oracle_crossed() {
        let mut book = OracleBook::new();
        let _spec = spec();

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

        // Cross the book by moving bid above ask
        let result = book.apply_delta(
            &[Level::new(
                Price::from_units(1020).unwrap(),
                Qty::from_units(50).unwrap(),
            )],
            &[],
        );

        assert_eq!(result, Ok(ApplyResult::Crossed));
        assert!(book.is_crossed());
        assert!(!book.is_valid());
    }

    #[test]
    fn oracle_snapshot_crossed_atomic() {
        let mut book = OracleBook::new();
        let initial_bid = Level::new(
            Price::from_units(1000).unwrap(),
            Qty::from_units(100).unwrap(),
        );
        let initial_ask = Level::new(
            Price::from_units(1010).unwrap(),
            Qty::from_units(200).unwrap(),
        );
        book.apply_snapshot(&[initial_bid], &[initial_ask]).unwrap();

        // Attempt crossed snapshot
        let bad_bid = Level::new(
            Price::from_units(1020).unwrap(),
            Qty::from_units(100).unwrap(),
        );
        let bad_ask = Level::new(
            Price::from_units(1010).unwrap(),
            Qty::from_units(200).unwrap(),
        );
        let err = book.apply_snapshot(&[bad_bid], &[bad_ask]);
        assert_eq!(err, Err(BookError::Crossed));

        // Prior state must be completely preserved
        assert_eq!(book.best_bid().unwrap().price.units(), 1000);
        assert_eq!(book.best_ask().unwrap().price.units(), 1010);
        assert!(book.is_valid());
    }

    #[test]
    fn oracle_levels() {
        let mut book = OracleBook::new();
        let _spec = spec();

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
                    Qty::from_units(100).unwrap(),
                ),
                Level::new(
                    Price::from_units(1020).unwrap(),
                    Qty::from_units(200).unwrap(),
                ),
            ],
        )
        .unwrap();

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
    fn oracle_depth_qty() {
        let mut book = OracleBook::new();
        let _spec = spec();

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

        let depth = book.depth_qty(BookSide::Bid, 2).unwrap();
        assert_eq!(depth.units(), 300);

        let depth = book.depth_qty(BookSide::Ask, 1).unwrap();
        assert_eq!(depth.units(), 100);
    }

    #[test]
    fn oracle_walk_market() {
        let mut book = OracleBook::new();
        let _spec = spec();

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

        // Buy 150 units (walk asks)
        let plan = book
            .walk_market(BookSide::Ask, Qty::from_units(150).unwrap())
            .unwrap();
        assert_eq!(plan.total_filled.units(), 150);
        assert_eq!(plan.levels.len(), 2);
        assert_eq!(plan.levels[0].price.units(), 1010);
        assert_eq!(plan.levels[0].filled_qty.units(), 100);
        assert_eq!(plan.levels[1].price.units(), 1020);
        assert_eq!(plan.levels[1].filled_qty.units(), 50);
    }
}
