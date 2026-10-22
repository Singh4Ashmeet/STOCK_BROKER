//! Price and Quantity types with alignment checks

use core::fmt;

use crate::decimal::{parse_scaled, write_scaled};
use crate::error::{CoreError, ParseError};
use crate::instrument::InstrumentSpec;

/// Price in price units (scaled integer).
///
/// Always non-negative. The scale is provided by the associated `InstrumentSpec`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Price(i64);

impl Price {
    /// Create a Price from raw units (scaled integer).
    ///
    /// # Errors
    ///
    /// Returns `CoreError::Negative` if value < 0.
    pub fn from_units(units: i64) -> Result<Self, CoreError> {
        if units < 0 {
            return Err(CoreError::Negative);
        }
        Ok(Self(units))
    }

    /// Get the raw units (scaled integer).
    #[must_use]
    pub fn units(self) -> i64 {
        self.0
    }

    /// Parse a price from a decimal string using the instrument spec for scale.
    ///
    /// # Examples
    ///
    /// ```
    /// use sb_core::{InstrumentSpec, Price};
    ///
    /// let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
    /// let price = Price::parse(b"64321.10", &spec).unwrap();
    /// assert_eq!(price.units(), 6_432_110);
    /// ```
    pub fn parse(bytes: &[u8], spec: &InstrumentSpec) -> Result<Self, ParseError> {
        let units = parse_scaled(bytes, spec.price_scale())?;
        Ok(Self(units))
    }

    /// Write the price as a decimal string using the instrument spec.
    pub fn write_decimal<W: fmt::Write>(&self, spec: &InstrumentSpec, out: &mut W) -> fmt::Result {
        write_scaled(self.0, spec.price_scale(), out)
    }

    /// Check if the price is aligned to the tick size.
    #[must_use]
    pub fn is_tick_aligned(&self, spec: &InstrumentSpec) -> bool {
        self.0 % spec.tick_size() == 0
    }

    /// Get the price in ticks (number of tick sizes).
    ///
    /// # Errors
    ///
    /// Returns `CoreError::NotTickAligned` if not divisible by tick size.
    pub fn ticks(&self, spec: &InstrumentSpec) -> Result<i64, CoreError> {
        if !self.is_tick_aligned(spec) {
            return Err(CoreError::NotTickAligned);
        }
        Ok(self.0 / spec.tick_size())
    }

    /// Create a Price from a tick count.
    ///
    /// # Errors
    ///
    /// Returns `CoreError::Overflow` if multiplication overflows.
    pub fn from_ticks(ticks: i64, spec: &InstrumentSpec) -> Result<Self, CoreError> {
        let units = ticks
            .checked_mul(spec.tick_size())
            .ok_or(CoreError::Overflow)?;
        Self::from_units(units)
    }

    /// Zero price.
    pub const ZERO: Self = Self(0);
}

/// Quantity in qty units (scaled integer).
///
/// Always non-negative.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Qty(i64);

impl Qty {
    /// Create a Qty from raw units (scaled integer).
    ///
    /// # Errors
    ///
    /// Returns `CoreError::Negative` if value < 0.
    pub fn from_units(units: i64) -> Result<Self, CoreError> {
        if units < 0 {
            return Err(CoreError::Negative);
        }
        Ok(Self(units))
    }

    /// Get the raw units (scaled integer).
    #[must_use]
    pub fn units(self) -> i64 {
        self.0
    }

    /// Parse a quantity from a decimal string using the instrument spec for scale.
    pub fn parse(bytes: &[u8], spec: &InstrumentSpec) -> Result<Self, ParseError> {
        let units = parse_scaled(bytes, spec.qty_scale())?;
        Ok(Self(units))
    }

    /// Write the quantity as a decimal string using the instrument spec.
    pub fn write_decimal<W: fmt::Write>(&self, spec: &InstrumentSpec, out: &mut W) -> fmt::Result {
        write_scaled(self.0, spec.qty_scale(), out)
    }

    /// Check if the quantity is aligned to the lot size.
    #[must_use]
    pub fn is_lot_aligned(&self, spec: &InstrumentSpec) -> bool {
        self.0 % spec.lot_size() == 0
    }

    /// Get the quantity in lots (number of lot sizes).
    ///
    /// # Errors
    ///
    /// Returns `CoreError::NotLotAligned` if not divisible by lot size.
    pub fn lots(&self, spec: &InstrumentSpec) -> Result<i64, CoreError> {
        if !self.is_lot_aligned(spec) {
            return Err(CoreError::NotLotAligned);
        }
        Ok(self.0 / spec.lot_size())
    }

    /// Create a Qty from a lot count.
    pub fn from_lots(lots: i64, spec: &InstrumentSpec) -> Result<Self, CoreError> {
        let units = lots
            .checked_mul(spec.lot_size())
            .ok_or(CoreError::Overflow)?;
        Self::from_units(units)
    }

    /// Zero quantity.
    pub const ZERO: Self = Self(0);

    /// Check if quantity is zero.
    #[must_use]
    pub fn is_zero(&self) -> bool {
        self.0 == 0
    }

    /// Checked addition.
    ///
    /// # Errors
    ///
    /// Returns `CoreError::Overflow` on overflow, `CoreError::Negative` if result < 0.
    pub fn checked_add(self, other: Self) -> Result<Self, CoreError> {
        let units = self.0.checked_add(other.0).ok_or(CoreError::Overflow)?;
        Self::from_units(units)
    }

    /// Checked subtraction.
    ///
    /// # Errors
    ///
    /// Returns `CoreError::Overflow` on underflow, `CoreError::Negative` if result < 0.
    pub fn checked_sub(self, other: Self) -> Result<Self, CoreError> {
        let units = self.0.checked_sub(other.0).ok_or(CoreError::Overflow)?;
        Self::from_units(units)
    }

    /// Returns the value or a default if the quantity is zero.
    #[must_use]
    pub fn unwrap_or_else(self, default: impl FnOnce() -> Self) -> Self {
        if self.is_zero() { default() } else { self }
    }
}

/// Compute the notional value of a price x quantity.
///
/// Returns value in quote units scaled by (`price_scale` + `qty_scale`).
/// The result fits in i128 because `price_scale` + `qty_scale` <= 36 by `InstrumentSpec` validation.
///
/// # Examples
///
/// ```
/// use sb_core::{InstrumentSpec, Price, Qty, notional};
///
/// let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
/// let price = Price::from_units(6_432_110).unwrap(); // 64321.10
/// let qty = Qty::from_units(50_000).unwrap(); // 0.50000
/// let notional_value = notional(price, qty); // 321_605_500_000 = 32160.55 in real units
/// ```
#[must_use]
pub fn notional(price: Price, qty: Qty) -> i128 {
    // i64 * i64 always fits in i128
    i128::from(price.0) * i128::from(qty.0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::StackWriter;

    #[test]
    fn price_basic() {
        let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
        let price = Price::parse(b"64321.10", &spec).unwrap();
        assert_eq!(price.units(), 6_432_110);

        let price = Price::from_units(100).unwrap();
        assert_eq!(price.units(), 100);
    }

    #[test]
    fn price_negative_error() {
        assert!(Price::from_units(-1).is_err());
    }

    #[test]
    fn price_tick_alignment() {
        let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap(); // tick = 0.01
        let price = Price::from_units(6_432_110).unwrap(); // 64321.10
        assert!(price.is_tick_aligned(&spec));
        assert_eq!(price.ticks(&spec).unwrap(), 643_211);

        let bad_price = Price::from_units(6_432_115).unwrap(); // 64321.15 (not aligned to 0.01)
        assert!(!bad_price.is_tick_aligned(&spec));
        assert!(bad_price.ticks(&spec).is_err());
    }

    #[test]
    fn price_from_ticks() {
        let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
        let price = Price::from_ticks(643_211, &spec).unwrap();
        assert_eq!(price.units(), 6_432_110);
    }

    #[test]
    fn qty_basic() {
        let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
        let qty = Qty::parse(b"0.50000", &spec).unwrap();
        assert_eq!(qty.units(), 50_000);
    }

    #[test]
    fn qty_lot_alignment() {
        let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap(); // lot = 0.00001
        let qty = Qty::from_units(50_000).unwrap(); // 0.50000
        assert!(qty.is_lot_aligned(&spec));
        assert_eq!(qty.lots(&spec).unwrap(), 500);

        let bad_qty = Qty::from_units(50_001).unwrap(); // 0.50001
        assert!(!bad_qty.is_lot_aligned(&spec));
        assert!(bad_qty.lots(&spec).is_err());
    }

    #[test]
    fn qty_checked_math() {
        let a = Qty::from_units(100).unwrap();
        let b = Qty::from_units(200).unwrap();
        let sum = a.checked_add(b).unwrap();
        assert_eq!(sum.units(), 300);

        let diff = b.checked_sub(a).unwrap();
        assert_eq!(diff.units(), 100);

        // Underflow
        assert!(a.checked_sub(b).is_err());
    }

    #[test]
    fn notional_calculation() {
        let _spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
        let price = Price::from_units(6_432_110).unwrap(); // 64321.10
        let qty = Qty::from_units(50_000).unwrap(); // 0.50000
        let notional_value = notional(price, qty);
        // 6_432_110 * 50_000 = 321_605_500_000
        assert_eq!(notional_value, 321_605_500_000);

        // Max values
        let max_price = Price::from_units(i64::MAX).unwrap();
        let max_qty = Qty::from_units(i64::MAX).unwrap();
        let max_notional = notional(max_price, max_qty);
        assert_eq!(max_notional, i128::from(i64::MAX) * i128::from(i64::MAX));
    }

    #[test]
    fn price_write_decimal() {
        let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
        let price = Price::from_units(6_432_110).unwrap();
        let mut w = StackWriter::new();
        price.write_decimal(&spec, &mut w).unwrap();
        assert_eq!(w.as_str(), Some("64321.10"));
    }

    #[test]
    fn qty_write_decimal() {
        let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
        let qty = Qty::from_units(50_000).unwrap();
        let mut w = StackWriter::new();
        qty.write_decimal(&spec, &mut w).unwrap();
        assert_eq!(w.as_str(), Some("0.50000"));
    }
}
