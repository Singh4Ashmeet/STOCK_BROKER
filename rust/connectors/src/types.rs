//! Shared data structures and event representations for exchange connectors.

use sb_core::{Epoch, Level, Seq, SymbolId, Timing, Trade, TsNs};

/// State of an order book synchronizer for a specific symbol.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionState {
    /// Socket is closed or disconnected
    Disconnected,
    /// Connected to WebSocket, buffering diff depth events while awaiting snapshot
    Buffering,
    /// Snapshot fetched, applying buffered diffs to catch up
    Syncing,
    /// In sync and streaming live updates
    Live,
    /// Discontinuity, crossed book, or corruption detected; pending resync
    Invalid,
}

impl ConnectionState {
    /// Returns true if the synchronizer is live and its book is valid to serve.
    #[must_use]
    pub fn is_live(self) -> bool {
        matches!(self, Self::Live)
    }
}

/// Specification for a WebSocket stream subscription.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StreamSpec {
    /// Target WebSocket endpoint URL
    pub url: String,
    /// Subscription message/channel (if JSON payload subscription is used)
    pub channel: Option<String>,
}

/// Unprocessed raw snapshot fetched from REST.
#[derive(Debug, Clone)]
pub struct RawSnapshot {
    /// Exchange update ID of the snapshot (e.g. Binance `lastUpdateId`)
    pub last_update_id: u64,
    /// Snapshot bid levels (sorted descending by price)
    pub bids: Vec<Level>,
    /// Snapshot ask levels (sorted ascending by price)
    pub asks: Vec<Level>,
    /// Exchange timestamp when the snapshot was generated
    pub exchange_ts: TsNs,
    /// Local time when snapshot response was received
    pub recv_ts: TsNs,
}

/// A parsed diff depth event from the exchange stream.
#[derive(Debug, Clone)]
pub struct DiffEvent {
    /// Symbol ID
    pub symbol: SymbolId,
    /// First update ID in this event (e.g. `U`)
    pub first_update_id: u64,
    /// Final update ID in this event (e.g. `u`)
    pub final_update_id: u64,
    /// Bid level updates (absolute quantities, 0 means remove)
    pub bids: Vec<Level>,
    /// Ask level updates (absolute quantities, 0 means remove)
    pub asks: Vec<Level>,
    /// Exchange timestamp
    pub exchange_ts: TsNs,
    /// Local receive timestamp
    pub recv_ts: TsNs,
}

/// Normalized market events output by connectors.
#[derive(Debug, Clone)]
pub enum MarketEvent {
    /// Full order book snapshot emitted when entering `Live` or upon resync
    Snapshot {
        /// Symbol ID
        symbol: SymbolId,
        /// Rebuild epoch
        epoch: Epoch,
        /// Monotonic sequence within this epoch
        seq: Seq,
        /// Latency timing
        timing: Timing,
        /// Full bid levels
        bids: Vec<Level>,
        /// Full ask levels
        asks: Vec<Level>,
    },
    /// Incremental order book delta
    Delta {
        /// Symbol ID
        symbol: SymbolId,
        /// Rebuild epoch
        epoch: Epoch,
        /// Monotonic sequence within this epoch
        seq: Seq,
        /// Latency timing
        timing: Timing,
        /// Updated bid levels
        bids: Vec<Level>,
        /// Updated ask levels
        asks: Vec<Level>,
    },
    /// Executed trade
    Trade {
        /// Symbol ID
        symbol: SymbolId,
        /// Rebuild epoch
        epoch: Epoch,
        /// Monotonic sequence within this epoch
        seq: Seq,
        /// Latency timing
        timing: Timing,
        /// Normalized trade details
        trade: Trade,
    },
    /// State machine transition
    StateChange {
        /// Symbol ID
        symbol: SymbolId,
        /// Prior state
        old_state: ConnectionState,
        /// New state
        new_state: ConnectionState,
        /// Current epoch
        epoch: Epoch,
    },
}

/// Raw recorded frame for offline captures and tests.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct RawRecord {
    /// Receive timestamp in nanoseconds UTC
    pub recv_ts_ns: i64,
    /// Exchange identifier, e.g. "BINANCE"
    pub venue: String,
    /// Channel or stream name, e.g. "btcusdt@depth@100ms" or "REST:depth"
    pub stream: String,
    /// Raw payload string (JSON text or base64)
    pub payload: String,
}
