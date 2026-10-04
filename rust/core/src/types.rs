//! Core market event types

use core::fmt;

use crate::money::{Price, Qty};
use crate::symbol::SymbolId;
use crate::time::Timing;

/// Order book side (bid or ask)
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum BookSide {
    /// Bid side (buy orders)
    Bid,
    /// Ask side (sell orders)
    Ask,
}

impl BookSide {
    /// Get the opposite side.
    #[must_use]
    pub fn opposite(&self) -> Self {
        match self {
            BookSide::Bid => BookSide::Ask,
            BookSide::Ask => BookSide::Bid,
        }
    }
}

impl fmt::Display for BookSide {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            BookSide::Bid => write!(f, "Bid"),
            BookSide::Ask => write!(f, "Ask"),
        }
    }
}

/// Trade aggressor (who crossed the spread)
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Aggressor {
    /// Aggressor was a buyer (took from ask side)
    Buy,
    /// Aggressor was a seller (took from bid side)
    Sell,
}

impl Aggressor {
    /// Get the opposite aggressor.
    #[must_use]
    pub fn opposite(&self) -> Self {
        match self {
            Aggressor::Buy => Aggressor::Sell,
            Aggressor::Sell => Aggressor::Buy,
        }
    }

    /// Get the book side that was hit (the passive side).
    #[must_use]
    pub fn passive_side(&self) -> BookSide {
        match self {
            Aggressor::Buy => BookSide::Ask,
            Aggressor::Sell => BookSide::Bid,
        }
    }
}

impl fmt::Display for Aggressor {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Aggressor::Buy => write!(f, "Buy"),
            Aggressor::Sell => write!(f, "Sell"),
        }
    }
}

/// Epoch number (increments on book rebuild: reconnect, resync, config change)
///
/// `Copy`, `Eq`, `Hash` - suitable for map keys.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Default)]
pub struct Epoch(u64);

impl Epoch {
    /// Create a new epoch.
    #[must_use]
    pub const fn new(n: u64) -> Self {
        Self(n)
    }

    /// Get the raw epoch number.
    #[must_use]
    pub fn id(self) -> u64 {
        self.0
    }

    /// Increment the epoch (saturating).
    #[must_use]
    pub fn increment(&self) -> Self {
        Self(self.0.saturating_add(1))
    }
}

impl fmt::Display for Epoch {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

/// Sequence number within an epoch (increments by 1 per message).
///
/// Overflow is practically impossible (`u64::MAX` messages per epoch).
/// Returns `CoreError::Overflow` on overflow rather than wrapping.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Default)]
pub struct Seq(u64);

impl Seq {
    /// Create a new sequence number.
    #[must_use]
    pub const fn new(n: u64) -> Self {
        Self(n)
    }

    /// Get the raw sequence number.
    #[must_use]
    pub fn id(self) -> u64 {
        self.0
    }

    /// Get the next sequence number.
    ///
    /// # Errors
    ///
    /// Returns `CoreError::Overflow` on `u64::MAX` (practically impossible).
    pub fn next(&self) -> Result<Self, crate::error::CoreError> {
        self.0
            .checked_add(1)
            .map(Self)
            .ok_or(crate::error::CoreError::Overflow)
    }
}

impl fmt::Display for Seq {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

/// Order book price level (price + quantity)
///
/// `Copy` - small enough to pass by value.
/// `qty == 0` in a delta means "remove this level".
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Level {
    /// Price of this level
    pub price: Price,
    /// Quantity at this price level; 0 means remove this level
    pub qty: Qty,
}

impl Level {
    /// Create a new level.
    #[must_use]
    pub const fn new(price: Price, qty: Qty) -> Self {
        Self { price, qty }
    }

    /// Check if this level is a removal marker (qty == 0).
    #[must_use]
    pub fn is_removal(&self) -> bool {
        self.qty.is_zero()
    }
}

impl fmt::Display for Level {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}x{}", self.qty.units(), self.price.units())
    }
}

/// Trade event (mirrors proto Trade with `SymbolId` instead of string)
///
/// Note: the proto uses a string `venue_trade_id`; conversion happens at the gRPC edge.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Trade {
    /// Symbol identifier
    pub symbol: SymbolId,
    /// Timing information (`exchange_ts`, `recv_ts`, `publish_ts`)
    pub timing: Timing,
    /// Trade price in price units
    pub price: Price,
    /// Trade quantity in qty units
    pub qty: Qty,
    /// Aggressor side (Buy or Sell)
    pub aggressor: Aggressor,
    /// Venue-specific trade ID (e.g., Binance trade ID as u64).
    /// The proto uses a string; this is a numeric variant for efficiency.
    pub venue_trade_id: u64,
}

impl Trade {
    /// Create a new trade.
    #[must_use]
    pub fn new(
        symbol: SymbolId,
        timing: Timing,
        price: Price,
        qty: Qty,
        aggressor: Aggressor,
        venue_trade_id: u64,
    ) -> Self {
        Self {
            symbol,
            timing,
            price,
            qty,
            aggressor,
            venue_trade_id,
        }
    }
}

impl fmt::Display for Trade {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "Trade{{symbol={}, price={}, qty={}, aggressor={}, id={}}}",
            self.symbol, self.price.units(), self.qty.units(), self.aggressor, self.venue_trade_id
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::time::Timing;

    #[test]
    fn book_side_opposite() {
        assert_eq!(BookSide::Bid.opposite(), BookSide::Ask);
        assert_eq!(BookSide::Ask.opposite(), BookSide::Bid);
    }

    #[test]
    fn aggressor_opposite() {
        assert_eq!(Aggressor::Buy.opposite(), Aggressor::Sell);
        assert_eq!(Aggressor::Sell.opposite(), Aggressor::Buy);
    }

    #[test]
    fn aggressor_passive_side() {
        assert_eq!(Aggressor::Buy.passive_side(), BookSide::Ask);
        assert_eq!(Aggressor::Sell.passive_side(), BookSide::Bid);
    }

    #[test]
    fn epoch_increment() {
        let e = Epoch::new(1);
        assert_eq!(e.increment().id(), 2);
        assert_eq!(Epoch::default().id(), 0);
        // Saturating
        assert_eq!(Epoch::new(u64::MAX).increment().id(), u64::MAX);
    }

    #[test]
    fn seq_next() {
        let s = Seq::new(42);
        assert_eq!(s.next().unwrap().id(), 43);
        assert_eq!(Seq::default().id(), 0);
        // Overflow
        assert!(Seq::new(u64::MAX).next().is_err());
    }

    #[test]
    fn level_creation() {
        let _spec = crate::instrument::InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
        let price = crate::money::Price::from_units(6_432_110).unwrap();
        let qty = crate::money::Qty::from_units(50_000).unwrap();
        let level = Level::new(price, qty);
        assert!(!level.is_removal());
        assert_eq!(level.price.units(), 6_432_110);
        assert_eq!(level.qty.units(), 50_000);

        // Removal level
        let removal = Level::new(price, Qty::ZERO);
        assert!(removal.is_removal());
    }

    #[test]
    fn trade_creation() {
        let _spec = crate::instrument::InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
        let timing = Timing {
            exchange_ts: crate::time::TsNs::from_nanos(1_000_000_000),
            recv_ts: crate::time::TsNs::from_nanos(1_000_100_000),
            publish_ts: crate::time::TsNs::from_nanos(1_000_150_000),
        };
        let symbol = crate::symbol::SymbolId::new(0);
        let price = crate::money::Price::from_units(6_432_110).unwrap();
        let qty = crate::money::Qty::from_units(50_000).unwrap();

        let trade = Trade::new(symbol, timing, price, qty, Aggressor::Buy, 12_345);
        assert_eq!(trade.symbol.id(), 0);
        assert_eq!(trade.price.units(), 6_432_110);
        assert_eq!(trade.qty.units(), 50_000);
        assert_eq!(trade.aggressor, Aggressor::Buy);
        assert_eq!(trade.venue_trade_id, 12_345);
    }
}
