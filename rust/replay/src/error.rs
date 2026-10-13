//! Error types for event logging and replay.

use std::path::PathBuf;

/// Result alias for replay operations.
pub type Result<T> = std::result::Result<T, ReplayError>;

/// Errors that can occur during event-log writing, reading, or replay.
#[derive(Debug, thiserror::Error)]
pub enum ReplayError {
    /// I/O error during file operations
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    /// Protobuf encoding or decoding failure
    #[error("Protobuf error: {0}")]
    Protobuf(#[from] prost::DecodeError),

    /// Invalid magic bytes in event log header
    #[error("Invalid magic bytes: expected SBEL, found {0:?}")]
    InvalidMagic([u8; 4]),

    /// Unsupported schema version
    #[error("Unsupported schema version: {version} (max supported: {max_supported})")]
    UnsupportedSchemaVersion {
        /// Found schema version
        version: u32,
        /// Maximum supported schema version
        max_supported: u32,
    },

    /// Corrupted file or unexpected EOF
    #[error("Corrupted event log file at {path:?}: {reason}")]
    CorruptedFile {
        /// File path
        path: PathBuf,
        /// Description of the corruption
        reason: String,
    },

    /// Order book application error during replay
    #[error("Book error during replay: {0}")]
    Book(String),

    /// The writer channel was disconnected unexpectedly
    #[error("Writer channel disconnected")]
    ChannelDisconnected,

    /// Log record dropped due to full channel in live mode
    #[error("Log record dropped (writer channel full)")]
    RecordDropped,
}
