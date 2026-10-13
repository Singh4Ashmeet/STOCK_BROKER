//! Error types for exchange connectors.

use thiserror::Error;

/// Errors that can occur in exchange connectors.
#[derive(Debug, Error)]
pub enum ConnectorError {
    /// WebSocket transport error
    #[error("WebSocket error: {0}")]
    WebSocket(String),

    /// HTTP request error
    #[error("HTTP error: {0}")]
    Http(String),

    /// Serialization/deserialization error
    #[error("JSON parse error: {0}")]
    Json(#[from] serde_json::Error),

    /// Missing or invalid field in message
    #[error("Protocol error: {0}")]
    Protocol(String),

    /// Rate limit exceeded
    #[error("Rate limited, retry after {0:?}")]
    RateLimited(Option<std::time::Duration>),

    /// Symbol not found or unsupported
    #[error("Unsupported symbol: {0}")]
    UnsupportedSymbol(String),

    /// Decimal parse error
    #[error("Parse error: {0}")]
    Parse(#[from] sb_core::ParseError),

    /// Core library error
    #[error("Core error: {0}")]
    Core(#[from] sb_core::CoreError),

    /// Book error
    #[error("Book error: {0}")]
    Book(#[from] sb_book::BookError),

    /// Connection closed or timed out
    #[error("Connection closed: {0}")]
    ConnectionClosed(String),

    /// Sequence gap detected
    #[error("Sequence gap detected: expected U={expected}, got U={actual}")]
    SequenceGap {
        /// Expected update id
        expected: u64,
        /// Actual update id
        actual: u64,
    },

    /// Snapshot too old to bridge to buffered events
    #[error("Snapshot too old: lastUpdateId={last_update_id}, first buffered U={first_u}")]
    SnapshotTooOld {
        /// Snapshot's lastUpdateId
        last_update_id: u64,
        /// First buffered message's U
        first_u: u64,
    },

    /// Snapshot cannot bridge to buffered events
    #[error("Snapshot cannot bridge: lastUpdateId={last_update_id}, first buffered u={first_u}")]
    SnapshotCannotBridge {
        /// Snapshot's lastUpdateId
        last_update_id: u64,
        /// First buffered message's u
        first_u: u64,
    },
}
