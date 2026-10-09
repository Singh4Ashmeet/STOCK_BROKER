//! sb-core: Core types for `stock_broker`
//!
//! This crate provides the foundational types used across the `stock_broker` platform:
//! - Fixed-point decimal parsing and formatting (allocation-free)
//! - Instrument specifications (scales, tick/lot sizes, min notional)
//! - Price and Quantity newtypes with alignment checks
//! - Timestamp handling (UTC nanoseconds)
//! - Symbol interning and parsing
//! - Core market event types (Level, Trade, Timing, etc.)
//!
//! # Design principles
//!
//! - **No I/O, no async, no threads** — pure library code
//! - **No floats for money/quantities** — everything is scaled integers
//! - **Allocation-free hot paths** — only `SymbolTable` (startup) and `to_decimal_string` (cold) allocate
//! - **No panics on external input** — every fallible operation returns Result
//! - **Checked arithmetic everywhere** — overflow is an error, not a panic
//!
//! # Example
//!
//! ```
//! use sb_core::{InstrumentSpec, Price, Qty, parse_scaled};
//!
//! let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap(); // BTC-USDT like
//! let price = Price::parse(b"64321.10", &spec).unwrap();
//! assert_eq!(price.units(), 6432110);
//! ```

#![forbid(unsafe_code)]
#![deny(missing_docs)]
#![deny(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::float_arithmetic
)]
#![warn(clippy::pedantic)]
#![allow(clippy::must_use_candidate)]
#![allow(clippy::return_self_not_must_use)]
#![allow(clippy::missing_panics_doc)]
#![allow(clippy::missing_errors_doc)]
#![allow(clippy::items_after_statements)]
#![allow(clippy::derivable_impls)]
#![allow(clippy::unreadable_literal)]
#![allow(clippy::wildcard_in_or_patterns)]
#![allow(clippy::empty_line_after_outer_attr)]
#![allow(clippy::empty_line_after_doc_comments)]

pub mod decimal;
pub mod error;
pub mod instrument;
pub mod money;
pub mod symbol;
pub mod time;
pub mod types;

/// Re-export commonly used types
pub use decimal::{StackWriter, parse_scaled, to_decimal_string, write_scaled};
pub use error::{CoreError, ParseError};
pub use instrument::InstrumentSpec;
pub use money::{Price, Qty, notional};
pub use symbol::{SymbolId, SymbolParts, SymbolTable, parse_symbol};
pub use time::{Clock, ManualClock, SystemClock, Timing, TsNs};
pub use types::{Aggressor, BookSide, Epoch, Level, Seq, Trade};
