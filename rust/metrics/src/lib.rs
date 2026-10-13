//! sb-metrics: Incremental market microstructure metrics
//!
//! This crate computes book and trade metrics incrementally (cost proportional
//! to changed levels, not the full book depth). All hot-path functions are
//! allocation-free after initial construction.
//!
//! # Metrics provided
//!
//! | Metric | Module | Formula source |
//! |---|---|---|
//! | Best bid/ask, spread, mid | [`bbo`] | docs/06-ORDER-BOOK-SPEC.md §5 |
//! | Microprice | [`bbo`] | `(bb*qa + ba*qb) / (qa+qb)` |
//! | Depth at N levels | [`depth`] | Summed qty over top N levels |
//! | Order-book imbalance | [`depth`] | `(Db-Da)/(Db+Da)` |
//! | Rolling VWAP | [`vwap`] | `Σ(p·q) / Σq` over event-time window |
//! | Trade-flow imbalance | [`trade_flow`] | `(Vbuy-Vsell)/(Vbuy+Vsell)` |
//!
//! # Design principles
//!
//! - **No I/O, no async, no threads** — pure library code
//! - **No floats for prices/quantities** — integer ticks/lots only; f64 only in
//!   derived analytics fields (mid, microprice, imbalance, VWAP)
//! - **No allocation on the hot path** — ring buffers pre-sized at construction
//! - **Event-time windows** — deterministic replay
//! - **Invalid states flagged** — `valid=false` instead of NaN
//! - **Pure functions of state** — no hidden state, no randomness

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
#![allow(clippy::module_name_repetitions)]

pub mod bbo;
pub mod depth;
pub mod engine;
pub mod trade_flow;
pub mod types;
pub mod vwap;

/// Re-export the main entry point
pub use bbo::compute_bbo;
pub use depth::compute_depth;
pub use engine::MetricsEngine;
pub use types::{BboSnapshot, DepthSnapshot, MetricsSnapshot, TradeFlowSnapshot, VwapSnapshot};
