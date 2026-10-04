//! Instrument specification

use crate::error::CoreError;

/// Instrument specification containing scale and sizing parameters.
///
/// All fields are private; use the validated constructor `InstrumentSpec::new`.
/// The struct is `Copy` so it can be freely passed by value.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct InstrumentSpec {
    price_scale: u8,
    qty_scale: u8,
    tick_size: i64,
    lot_size: i64,
    min_notional: i64,
}

impl InstrumentSpec {
    /// Create a new `InstrumentSpec` with validation.
    ///
    /// # Arguments
    ///
    /// * `price_scale` - Decimal places for price (0..=18)
    /// * `qty_scale` - Decimal places for quantity (0..=18)
    /// * `tick_size` - Minimum price increment in price units (> 0)
    /// * `lot_size` - Minimum quantity increment in qty units (> 0)
    /// * `min_notional` - Minimum notional value in quote units (>= 0, 0 = unknown)
    ///
    /// # Validation
    ///
    /// - Scales must be <= 18
    /// - `tick_size` > 0
    /// - `lot_size` > 0
    /// * `min_notional` >= 0
    /// * `price_scale` + `qty_scale` <= 36 (so notionals fit in i128)
    ///
    /// # Errors
    ///
    /// Returns `CoreError::InvalidScale` if scales > 18 or sum > 36.
    /// Returns `CoreError::InvalidTick` if `tick_size` <= 0.
    /// Returns `CoreError::InvalidLot` if `lot_size` <= 0.
    /// Returns `CoreError::Negative` if `min_notional` < 0.
    ///
    /// # Examples
    ///
    /// ```
    /// use sb_core::InstrumentSpec;
    ///
    /// // BTC-USDT like: price scale 2, qty scale 5, tick 0.01, lot 0.00001
    /// let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
    /// assert_eq!(spec.price_scale(), 2);
    /// assert_eq!(spec.qty_scale(), 5);
    /// ```
    pub fn new(
        price_scale: u8,
        qty_scale: u8,
        tick_size: i64,
        lot_size: i64,
        min_notional: i64,
    ) -> Result<Self, CoreError> {
        if price_scale > crate::decimal::MAX_SCALE {
            return Err(CoreError::InvalidScale(price_scale));
        }
        if qty_scale > crate::decimal::MAX_SCALE {
            return Err(CoreError::InvalidScale(qty_scale));
        }
        if tick_size <= 0 {
            return Err(CoreError::InvalidTick(tick_size));
        }
        if lot_size <= 0 {
            return Err(CoreError::InvalidLot(lot_size));
        }
        if min_notional < 0 {
            return Err(CoreError::Negative);
        }
        if u32::from(price_scale) + u32::from(qty_scale) > 36 {
            return Err(CoreError::InvalidScale(price_scale + qty_scale));
        }

        Ok(Self {
            price_scale,
            qty_scale,
            tick_size,
            lot_size,
            min_notional,
        })
    }

    /// Price scale (decimal places)
    #[must_use]
    pub fn price_scale(&self) -> u8 {
        self.price_scale
    }

    /// Quantity scale (decimal places)
    #[must_use]
    pub fn qty_scale(&self) -> u8 {
        self.qty_scale
    }

    /// Tick size in price units
    #[must_use]
    pub fn tick_size(&self) -> i64 {
        self.tick_size
    }

    /// Lot size in qty units
    #[must_use]
    pub fn lot_size(&self) -> i64 {
        self.lot_size
    }

    /// Minimum notional in quote units (`price_scale` + `qty_scale`)
    #[must_use]
    pub fn min_notional(&self) -> i64 {
        self.min_notional
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn valid_spec() {
        let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
        assert_eq!(spec.price_scale(), 2);
        assert_eq!(spec.qty_scale(), 5);
        assert_eq!(spec.tick_size(), 10);
        assert_eq!(spec.lot_size(), 100);
        assert_eq!(spec.min_notional(), 0);
    }

    #[test]
    fn invalid_scale() {
        assert!(InstrumentSpec::new(19, 5, 10, 100, 0).is_err());
        assert!(InstrumentSpec::new(2, 19, 10, 100, 0).is_err());
    }

    #[test]
    fn invalid_tick() {
        assert!(InstrumentSpec::new(2, 5, 0, 100, 0).is_err());
        assert!(InstrumentSpec::new(2, 5, -1, 100, 0).is_err());
    }

    #[test]
    fn invalid_lot() {
        assert!(InstrumentSpec::new(2, 5, 10, 0, 0).is_err());
        assert!(InstrumentSpec::new(2, 5, 10, -1, 100).is_err());
    }

    #[test]
    fn invalid_min_notional() {
        assert!(InstrumentSpec::new(2, 5, 10, 100, -1).is_err());
    }

    #[test]
    fn scale_sum_too_large() {
        // price_scale + qty_scale > 36
        assert!(InstrumentSpec::new(18, 19, 10, 100, 0).is_err());
        assert!(InstrumentSpec::new(19, 18, 10, 100, 0).is_err());
    }
}
