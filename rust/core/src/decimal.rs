//! Allocation-free decimal parsing and formatting
//!
//! This module provides functions to parse decimal strings (from exchange feeds)
//! into scaled integers and format scaled integers back to decimal strings.
//!
//! # Design
//!
//! - **No allocation**: `parse_scaled` and `write_scaled` work on byte slices and
//!   `core::fmt::Write` implementations respectively
//! - **No floats**: All arithmetic is done with checked integer operations
//! - **Binance-compatible**: Accepts trailing zeros beyond the scale (e.g., "64321.10000000"
//!   for scale 2), pads missing fraction digits
//!
//! # Grammar
//!
//! ```text
//! decimal := digits [ "." digits ]
//! digits := "0".."9"+
//! ```
//!
//! No sign, no exponent, no whitespace, no thousands separators.

use core::fmt;

use crate::error::ParseError;

#[allow(
    clippy::match_same_arms,
    clippy::if_not_else,
    clippy::arithmetic_side_effects,
    clippy::as_conversions,
    clippy::cast_sign_loss,
    clippy::cast_possible_truncation,
    clippy::cast_possible_wrap,
    clippy::cast_possible_truncation,
    clippy::missing_panics_doc,
    clippy::expect_used,
    clippy::unreadable_literal,
    clippy::wildcard_in_or_patterns,
    clippy::duplicated_attributes,
    clippy::empty_line_after_outer_attr,
    clippy::unwrap_used,
)]

/// Maximum supported scale (18 decimal places, fits in i64 with max value)
pub const MAX_SCALE: u8 = 18;

/// Precomputed powers of 10 for scales 0..=18
const POW10: [i64; 19] = [
    1,
    10,
    100,
    1_000,
    10_000,
    100_000,
    1_000_000,
    10_000_000,
    100_000_000,
    1_000_000_000,
    10_000_000_000,
    100_000_000_000,
    1_000_000_000_000,
    10_000_000_000_000,
    100_000_000_000_000,
    1_000_000_000_000_000,
    10_000_000_000_000_000,
    100_000_000_000_000_000,
    1_000_000_000_000_000_000,
];

/// Parse a decimal string into a scaled integer without allocation or floats.
///
/// # Arguments
///
/// * `bytes` - The decimal string as bytes (e.g., b"64321.10")
/// * `scale` - The target scale (number of decimal places, 0..=18)
///
/// # Returns
///
/// The scaled integer value (e.g., 6432110 for "64321.10" with scale 2)
///
/// # Errors
///
/// Returns `ParseError` for invalid input:
/// - Empty input
/// - Invalid characters (only digits and '.' allowed)
/// - Missing integer digits (e.g., ".5")
/// - Missing fraction digits (e.g., "5.")
/// - Multiple decimal points (e.g., "1.2.3")
/// - Too many fraction digits beyond scale (unless all trailing zeros)
/// - Value overflows i64
/// - Negative sign (not allowed)
///
/// # Examples
///
/// ```
/// use sb_core::parse_scaled;
///
/// assert_eq!(parse_scaled(b"64321.10", 2).unwrap(), 6432110);
/// assert_eq!(parse_scaled(b"0.00000001", 8).unwrap(), 1);
/// assert_eq!(parse_scaled(b"0", 0).unwrap(), 0);
/// assert_eq!(parse_scaled(b"64321.10000000", 2).unwrap(), 6432110);
/// ```
pub fn parse_scaled(bytes: &[u8], scale: u8) -> Result<i64, ParseError> {
    if scale > MAX_SCALE {
        return Err(ParseError::TooPrecise);
    }

    if bytes.is_empty() {
        return Err(ParseError::Empty);
    }

    let mut integer_part = 0i64;
    let mut fraction_part = 0i64;
    let mut fraction_digits = 0u8;
    let mut seen_dot = false;
    let mut seen_digit = false;

    for (pos, &b) in bytes.iter().enumerate() {
        match b {
            b'0'..=b'9' => {
                seen_digit = true;
                let digit = i64::from(b - b'0');

                if seen_dot {
                    // Fraction part
                    if fraction_digits < scale {
                        fraction_part = fraction_part
                            .checked_mul(10)
                            .and_then(|v| v.checked_add(digit))
                            .ok_or(ParseError::Overflow)?;
                        fraction_digits += 1;
                    } else {
                        // Extra fraction digits beyond scale - must be all zeros
                        if digit != 0 {
                            return Err(ParseError::TooPrecise);
                        }
                    }
                } else {
                    // Integer part
                    integer_part = integer_part
                        .checked_mul(10)
                        .and_then(|v| v.checked_add(digit))
                        .ok_or(ParseError::Overflow)?;
                }
            }
            b'.' => {
                if seen_dot {
                    return Err(ParseError::MultipleDots);
                }
                seen_dot = true;
            }
            b'-' => return Err(ParseError::Negative),
            #[allow(clippy::wildcard_in_or_patterns)]
            b'+' | _ => return Err(ParseError::InvalidChar { pos }),
        }
    }

    if !seen_digit {
        return Err(ParseError::Empty);
    }

    // Check for missing integer digits (e.g., ".5")
    if bytes.first() == Some(&b'.') {
        return Err(ParseError::MissingIntegerDigits);
    }

    // Check for missing fraction digits (e.g., "5.")
    if seen_dot && bytes.last() == Some(&b'.') {
        return Err(ParseError::MissingFractionDigits);
    }

    // Pad fraction part if needed
    while fraction_digits < scale {
        fraction_part = fraction_part
            .checked_mul(10)
            .ok_or(ParseError::Overflow)?;
        fraction_digits += 1;
    }

    // Combine integer and fraction parts
    let scale_factor = POW10[scale as usize];
    let result = integer_part
        .checked_mul(scale_factor)
        .and_then(|v| v.checked_add(fraction_part))
        .ok_or(ParseError::Overflow)?;

    Ok(result)
}

/// A tiny fixed-capacity stack writer for formatting decimals without allocation.
///
/// Uses a 32-byte buffer which is sufficient for any i64 with scale up to 18
/// (max 19 digits + decimal point + null terminator < 32).
#[derive(Debug, Default)]
pub struct StackWriter {
    buf: [u8; 32],
    len: usize,
}

impl StackWriter {
    /// Create a new empty stack writer
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Get the written bytes
    #[must_use]
    pub fn as_bytes(&self) -> &[u8] {
        &self.buf[..self.len]
    }

    /// Get the written string (valid UTF-8 since we only write ASCII digits and '.')
    ///
    /// Returns `None` if the buffer contains invalid UTF-8 (should never happen as we only write ASCII digits and '.')
    #[must_use]
    pub fn as_str(&self) -> Option<&str> {
        // We only write ASCII digits and '.', which is valid UTF-8.
        core::str::from_utf8(self.as_bytes()).ok()
    }

    /// Clear the buffer for reuse
    pub fn clear(&mut self) {
        self.len = 0;
    }
}

impl fmt::Write for StackWriter {
    fn write_str(&mut self, s: &str) -> fmt::Result {
        let bytes = s.as_bytes();
        let new_len = self.len.checked_add(bytes.len()).ok_or(fmt::Error)?;
        if new_len > self.buf.len() {
            return Err(fmt::Error);
        }
        self.buf[self.len..new_len].copy_from_slice(bytes);
        self.len = new_len;
        Ok(())
    }
}

/// Write a scaled integer as a decimal string with exactly `scale` decimal places.
///
/// # Arguments
///
/// * `value` - The scaled integer (non-negative)
/// * `scale` - Number of decimal places (0..=18)
/// * `out` - A `core::fmt::Write` implementation to write to
///
/// # Errors
///
/// Returns an error if the underlying writer returns an error.
///
/// # Examples
///
/// ```
/// use sb_core::{write_scaled, StackWriter};
///
/// let mut w = StackWriter::new();
/// write_scaled(6432110, 2, &mut w).unwrap();
/// assert_eq!(w.as_str(), "64321.10");
///
/// let mut w = StackWriter::new();
/// write_scaled(12345, 0, &mut w).unwrap();
/// assert_eq!(w.as_str(), "12345");
/// ```
pub fn write_scaled<W: fmt::Write>(value: i64, scale: u8, out: &mut W) -> fmt::Result {
    if scale == 0 {
        return write!(out, "{value}");
    }

    let scale_factor = POW10[usize::from(scale)];
    let integer_part = value / scale_factor;
    let fraction_part = value % scale_factor;

    write!(out, "{integer_part}")?;
    write!(out, ".")?;

    // Write fraction part with leading zeros to exactly `scale` digits
    // We need to output from most significant to least significant digit
    let frac = fraction_part;
    // Pre-compute the divisor for the most significant digit
    let mut divisor = POW10[usize::from(scale - 1)];
    for _ in 0..usize::from(scale) {
        let digit = (frac / divisor) % 10;
        // digit is guaranteed to be 0-9 by modulo 10
        #[allow(clippy::unwrap_used)]
        let digit_char = char::from(b'0' + u8::try_from(digit).unwrap());
        out.write_char(digit_char)?;
        divisor /= 10;
    }

    Ok(())
}

/// Format a scaled integer as a decimal string (cold helper, allocates).
///
/// This is a convenience function that allocates a String. Use `write_scaled`
/// with a `StackWriter` for allocation-free formatting in hot paths.
///
/// # Examples
///
/// ```
/// use sb_core::to_decimal_string;
///
/// assert_eq!(to_decimal_string(6432110, 2), "64321.10");
/// assert_eq!(to_decimal_string(12345, 0), "12345");
/// assert_eq!(to_decimal_string(0, 2), "0.00");
/// ```
#[must_use]
pub fn to_decimal_string(value: i64, scale: u8) -> String {
    let mut w = StackWriter::new();
    // StackWriter write cannot fail with our implementation
    write_scaled(value, scale, &mut w).unwrap_or_else(|_| unreachable!("StackWriter write cannot fail"));
    w.as_str().unwrap_or_default().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_basic() {
        assert_eq!(parse_scaled(b"64321.10", 2).unwrap(), 6_432_110);
        assert_eq!(parse_scaled(b"0.00000001", 8).unwrap(), 1);
        assert_eq!(parse_scaled(b"0", 0).unwrap(), 0);
    }

    #[test]
    fn parse_trailing_zeros() {
        // Binance sends 8 decimal places; we accept trailing zeros beyond scale
        assert_eq!(parse_scaled(b"64321.10000000", 2).unwrap(), 6_432_110);
        assert_eq!(parse_scaled(b"0.10000000", 1).unwrap(), 1);
    }

    #[test]
    fn parse_pads_missing_fraction() {
        assert_eq!(parse_scaled(b"64321.1", 2).unwrap(), 6_432_110);
        assert_eq!(parse_scaled(b"64321", 2).unwrap(), 6_432_100);
    }

    #[test]
    fn parse_errors() {
        assert_eq!(parse_scaled(b"", 2), Err(ParseError::Empty));
        assert_eq!(parse_scaled(b"-1", 2), Err(ParseError::Negative));
        assert_eq!(parse_scaled(b"+1", 2), Err(ParseError::InvalidChar { pos: 0 }));
        assert_eq!(parse_scaled(b"1e5", 2), Err(ParseError::InvalidChar { pos: 1 }));
        assert_eq!(parse_scaled(b" 1", 2), Err(ParseError::InvalidChar { pos: 0 }));
        assert_eq!(parse_scaled(b"1,000", 2), Err(ParseError::InvalidChar { pos: 1 }));
        assert_eq!(parse_scaled(b".5", 2), Err(ParseError::MissingIntegerDigits));
        assert_eq!(parse_scaled(b"5.", 2), Err(ParseError::MissingFractionDigits));
        assert_eq!(parse_scaled(b"1.2.3", 2), Err(ParseError::MultipleDots));
        assert_eq!(parse_scaled(b"64321.123", 2), Err(ParseError::TooPrecise));
    }

    #[test]
    fn parse_overflow() {
        // i64::MAX = 9_223_372_036_854_775_807
        assert_eq!(
            parse_scaled(b"9223372036854775807", 0),
            Ok(9_223_372_036_854_775_807)
        );
        assert_eq!(parse_scaled(b"9223372036854775808", 0), Err(ParseError::Overflow));

        // Scale-18 overflow: 9.223372036854775807 * 10^18 = 9_223_372_036_854_775_807 (fits)
        assert_eq!(parse_scaled(b"9.223372036854775807", 18), Ok(9_223_372_036_854_775_807));
        // 9.223372036854775808 * 10^18 = 9_223_372_036_854_775_808 (overflows)
        assert_eq!(parse_scaled(b"9.223372036854775808", 18), Err(ParseError::Overflow));
    }

    #[test]
    fn write_basic() {
        let mut w = StackWriter::new();
        write_scaled(6_432_110, 2, &mut w).unwrap();
        assert_eq!(w.as_str(), Some("64321.10"));

        let mut w = StackWriter::new();
        write_scaled(12_345, 0, &mut w).unwrap();
        assert_eq!(w.as_str(), Some("12345"));

        let mut w = StackWriter::new();
        write_scaled(0, 2, &mut w).unwrap();
        assert_eq!(w.as_str(), Some("0.00"));
    }

    #[test]
    fn to_decimal_string_basic() {
        assert_eq!(to_decimal_string(6_432_110, 2), "64321.10");
        assert_eq!(to_decimal_string(12_345, 0), "12345");
        assert_eq!(to_decimal_string(0, 2), "0.00");
    }

    #[test]
    fn round_trip() {
        // For all scales 0..=18, parsing the formatted output should return the original value
        for scale in 0..=MAX_SCALE {
            let values = [0, 1, 10, 123, 999, 1_000, 9_223_372_036_854_775_807];
            for &v in &values {
                let s = to_decimal_string(v, scale);
                let parsed = parse_scaled(s.as_bytes(), scale).unwrap();
                assert_eq!(parsed, v, "scale={scale}, value={v}");
            }
        }
    }

    #[test]
    fn stack_writer_reuse() {
        let mut w = StackWriter::new();
        write_scaled(123, 2, &mut w).unwrap();
        assert_eq!(w.as_str(), Some("1.23"));
        w.clear();
        write_scaled(456, 1, &mut w).unwrap();
        assert_eq!(w.as_str(), Some("45.6"));
    }
}