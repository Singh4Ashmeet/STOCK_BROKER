//! sb-book: Optimized order book and `BTreeMap` oracle for `stock_broker`
//!
//! This crate provides two order book implementations:
//!
//! ## Optimized Book (production)
//!
//! A high-performance order book using a dense window around the mid price with:
//! - **Dense window**: `Vec<Qty>` indexed by `(price_ticks - base_ticks)` covering a
//!   configurable window of ticks around the reference price
//! - **Occupancy bitset**: One bit per level for O(1)-ish next-occupied search using
//!   `trailing_zeros`/`leading_zeros`
//! - **Far levels**: A `BTreeMap` fallback for prices outside the dense window
//! - **Cached best index**: Recomputed only when the best level empties (scan via bitset)
//! - **Re-centering**: When best price approaches a window edge, rebuild the window around
//!   the new mid; rare and budgeted
//! - **Depth caches**: Running cumulative sums for top-N levels updated incrementally
//!
//! ### API
//!
//! ```ignore
//! apply_snapshot(&mut self, bids: &[Level], asks: &[Level])
//! apply_delta(&mut self, bids: &[Level], asks: &[Level]) -> ApplyResult
//! best_bid(&self) -> Option<Level>; best_ask(&self) -> Option<Level>
//! levels(&self, side, n, out: &mut [Level]) -> usize
//! depth_qty(&self, side, n) -> Qty
//! walk_market(&self, side, qty) -> FillPlan
//! clear(&mut self)
//! ```
//!
//! ## Oracle Book (test/reference)
//!
//! A simple `BTreeMap<Price, Qty>` per side used as a reference implementation for
//! property testing and benchmarks. Not used in production paths.
//!
//! # Design principles
//!
//! - **No I/O, no async, no threads** — pure library code
//! - **No floats for money/quantities** — everything is scaled integers
//! - **Allocation-free hot paths** — only re-centering and cold paths allocate
//! - **No panics on external input** — every fallible operation returns Result
//! - **Checked arithmetic everywhere** — overflow is an error, not a panic

#![forbid(unsafe_code)]
#![deny(missing_docs)]
#![deny(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::float_arithmetic,
    clippy::as_conversions,
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_possible_wrap,
    clippy::arithmetic_side_effects
)]
#![warn(clippy::pedantic)]
#![allow(clippy::must_use_candidate)]
#![allow(clippy::return_self_not_must_use)]
#![allow(clippy::missing_panics_doc)]
#![allow(clippy::missing_errors_doc)]
#![allow(clippy::items_after_statements)]
#![allow(clippy::derivable_impls)]
#![allow(clippy::unreadable_literal)]

pub mod book;
pub mod oracle;
pub mod types;

/// Re-export commonly used types
pub use book::{Book, BookConfig};
pub use oracle::OracleBook;
pub use types::{FillPlan, FillLevel, ApplyResult, BookError};
