//! Types for order book operations

use core::fmt;

use sb_core::{Price, Qty};

/// Result of applying a delta to the book
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ApplyResult {
    /// Delta applied successfully
    Ok,
    /// Book became crossed after applying delta (best bid >= best ask)
    Crossed,
}

impl ApplyResult {
    /// Check if the result is an error (crossed book)
    #[must_use]
    pub fn is_error(&self) -> bool {
        matches!(self, Self::Crossed)
    }
}

impl fmt::Display for ApplyResult {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ApplyResult::Ok => write!(f, "Ok"),
            ApplyResult::Crossed => write!(f, "Crossed"),
        }
    }
}

/// Errors that can occur during book operations
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum BookError {
    /// Price level would overflow
    #[error("price level overflow")]
    Overflow,
    /// Price is not aligned to tick size
    #[error("price not tick aligned")]
    NotTickAligned,
    /// Quantity is not aligned to lot size
    #[error("qty not lot aligned")]
    NotLotAligned,
    /// Book is crossed
    #[error("book crossed")]
    Crossed,
    /// Window capacity exceeded
    #[error("window capacity exceeded")]
    WindowCapacityExceeded,
    /// Invalid window configuration
    #[error("invalid window configuration")]
    InvalidWindowConfig,
    /// Book is in an invalid state
    #[error("book in invalid state")]
    InvalidBookState,
}

impl From<sb_core::error::CoreError> for BookError {
    fn from(err: sb_core::error::CoreError) -> Self {
        match err {
            sb_core::error::CoreError::Overflow => BookError::Overflow,
            sb_core::error::CoreError::Negative
            | sb_core::error::CoreError::InvalidTick(_)
            | sb_core::error::CoreError::NotTickAligned => BookError::NotTickAligned,
            sb_core::error::CoreError::InvalidScale(_)
            | sb_core::error::CoreError::Parse(_)
            | sb_core::error::CoreError::TimestampOutOfRange
            | sb_core::error::CoreError::SymbolTableFull
            | sb_core::error::CoreError::InvalidSymbol(_) => BookError::InvalidWindowConfig,
            sb_core::error::CoreError::InvalidLot(_) | sb_core::error::CoreError::NotLotAligned => {
                BookError::NotLotAligned
            }
        }
    }
}

/// A single fill level in a market walk
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FillLevel {
    /// Price of this fill level
    pub price: Price,
    /// Quantity available at this price
    pub qty: Qty,
    /// Quantity actually filled at this price
    pub filled_qty: Qty,
}

impl FillLevel {
    /// Create a new fill level
    #[must_use]
    pub const fn new(price: Price, qty: Qty, filled_qty: Qty) -> Self {
        Self {
            price,
            qty,
            filled_qty,
        }
    }

    /// Check if this level was fully filled
    #[must_use]
    pub fn is_fully_filled(&self) -> bool {
        self.filled_qty == self.qty
    }
}

/// Result of walking the market (market order simulation)
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FillPlan {
    /// Levels consumed in order (best to worst)
    pub levels: smallvec::SmallVec<[FillLevel; 8]>,
    /// Total quantity filled
    pub total_filled: Qty,
    /// Total notional value filled (`price_scale` + `qty_scale`)
    pub total_notional: i128,
    /// Average fill price (if any filled)
    pub avg_price: Option<Price>,
}

impl FillPlan {
    /// Create an empty fill plan
    #[must_use]
    pub fn empty() -> Self {
        Self {
            levels: smallvec::SmallVec::new(),
            total_filled: Qty::ZERO,
            total_notional: 0,
            avg_price: None,
        }
    }

    /// Check if the order was fully filled
    #[must_use]
    pub fn is_fully_filled(&self, requested_qty: Qty) -> bool {
        self.total_filled == requested_qty
    }
}

impl fmt::Display for FillPlan {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "FillPlan{{levels={}, filled={}, notional={}, avg={:?}}}",
            self.levels.len(),
            self.total_filled.units(),
            self.total_notional,
            self.avg_price.map(Price::units)
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sb_core::{Price, Qty};

    #[test]
    fn fill_plan_empty() {
        let plan = FillPlan::empty();
        assert!(plan.levels.is_empty());
        assert_eq!(plan.total_filled, Qty::ZERO);
        assert_eq!(plan.total_notional, 0);
        assert!(plan.avg_price.is_none());
    }

    #[test]
    fn fill_level_creation() {
        let price = Price::from_units(100).unwrap();
        let qty = Qty::from_units(50).unwrap();
        let level = FillLevel::new(price, qty, qty);
        assert!(level.is_fully_filled());
    }
}
