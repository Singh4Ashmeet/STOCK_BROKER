//! `sb-replay`: Deterministic event-log writer thread (ADR-0006) and replay driver.
//!
//! # Architecture
//!
//! - **Writer Thread** ([`writer`]): An isolated background thread that writes length-delimited
//!   Protobuf records in zstd-compressed frames (`.pbz`), rotating one file per symbol per hour.
//!   Hot-path worker threads enqueue records through a lock-free channel handle. In live mode,
//!   buffer saturation drops records and increments a counter rather than delaying the order book.
//! - **Schema** ([`schema`]): Protobuf schema version 1 definitions (`LogHeader`, `LogRecord`,
//!   snapshots, deltas, trades, and anomaly markers for gaps and resyncs).
//! - **Reader** ([`reader`]): Validates magic bytes (`b"SBEL"`), checks schema version compatibility
//!   (supporting versions $N$ and $N-1$), and iterates decompressed event records.
//! - **Replay Driver** ([`replay`]): Drives `sb-book::Book` and `sb-metrics::MetricsEngine`
//!   through the exact same code path as the live feed, ensuring 100% deterministic replay.

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

pub mod error;
pub mod path;
pub mod reader;
pub mod replay;
pub mod schema;
pub mod writer;

// Re-exports
pub use error::{ReplayError, Result};
pub use path::{HourBucket, LogPathBuilder};
pub use reader::EventLogReader;
pub use replay::{ReplayDriver, ReplaySummary};
pub use schema::{
    LogHeader, LogRecord, RecordKind, SCHEMA_VERSION, make_delta_record, make_gap_record,
    make_resync_record, make_snapshot_record, make_trade_record,
};
pub use writer::{EventLogWriter, LogWriterHandle, WriterConfig, WriterStats};
