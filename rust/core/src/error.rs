//! Error types for sb-core

use thiserror::Error;

/// Errors that can occur during parsing of decimal strings
#[derive(Debug, Clone, Copy, PartialEq, Eq, Error)]
pub enum ParseError {
    /// Empty input
    #[error("empty input")]
    Empty,
    /// Invalid character at position
    #[error("invalid character at position {pos}")]
    InvalidChar {
        /// Position of the invalid character in the input
        pos: usize,
    },
    /// Missing integer digits before decimal point
    #[error("missing integer digits")]
    MissingIntegerDigits,
    /// Missing fraction digits after decimal point
    #[error("missing fraction digits")]
    MissingFractionDigits,
    /// Multiple decimal points
    #[error("multiple decimal points")]
    MultipleDots,
    /// Too many fraction digits for the given scale
    #[error("too precise for scale")]
    TooPrecise,
    /// Value overflows i64
    #[error("value overflows i64")]
    Overflow,
    /// Negative value not allowed
    #[error("negative value not allowed")]
    Negative,
}

/// Core error type for sb-core operations
#[derive(Debug, Error)]
pub enum CoreError {
    /// Invalid scale (must be <= 18)
    #[error("invalid scale: {0} (must be <= 18)")]
    InvalidScale(u8),
    /// Invalid tick size (must be > 0)
    #[error("invalid tick size: {0} (must be > 0)")]
    InvalidTick(i64),
    /// Invalid lot size (must be > 0)
    #[error("invalid lot size: {0} (must be > 0)")]
    InvalidLot(i64),
    /// Arithmetic overflow
    #[error("arithmetic overflow")]
    Overflow,
    /// Negative value not allowed
    #[error("negative value not allowed")]
    Negative,
    /// Price not aligned to tick size
    #[error("price not aligned to tick size")]
    NotTickAligned,
    /// Quantity not aligned to lot size
    #[error("quantity not aligned to lot size")]
    NotLotAligned,
    /// Invalid symbol format
    #[error("invalid symbol: {0}")]
    InvalidSymbol(&'static str),
    /// Symbol table full
    #[error("symbol table full")]
    SymbolTableFull,
    /// Timestamp out of valid range
    #[error("timestamp out of range")]
    TimestampOutOfRange,
    /// Parse error
    #[error("parse error: {0}")]
    Parse(ParseError),
}

impl From<ParseError> for CoreError {
    fn from(err: ParseError) -> Self {
        CoreError::Parse(err)
    }
}