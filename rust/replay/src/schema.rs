//! Schema definitions and Protobuf serialization for the event log (ADR-0006).

use prost::Message;
use sb_core::{Aggressor, Level, Price, Qty, TsNs};

use crate::error::{ReplayError, Result};

/// Current event log schema version.
pub const SCHEMA_VERSION: u32 = 1;

/// Minimum supported schema version for backwards compatibility.
pub const MIN_SUPPORTED_SCHEMA_VERSION: u32 = 1;

/// 4-byte magic header identifying a `StockBroker` event log file.
pub const MAGIC_BYTES: &[u8; 4] = b"SBEL";

/// Types of records stored in the event log.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum RecordKind {
    /// Full order-book snapshot (all bids and asks)
    BookSnapshot = 1,
    /// Incremental order-book delta
    BookDelta = 2,
    /// Public trade execution
    Trade = 3,
    /// Sequence-gap anomaly marker
    GapMarker = 4,
    /// Resync / reconnect anomaly marker
    ResyncMarker = 5,
    /// Raw unparsed exchange message frame
    RawFrame = 6,
    /// Reconnect marker
    ReconnectMarker = 7,
}

impl RecordKind {
    /// Convert an integer tag to a `RecordKind`.
    #[must_use]
    pub fn from_i32(val: i32) -> Option<Self> {
        match val {
            1 => Some(Self::BookSnapshot),
            2 => Some(Self::BookDelta),
            3 => Some(Self::Trade),
            4 => Some(Self::GapMarker),
            5 => Some(Self::ResyncMarker),
            6 => Some(Self::RawFrame),
            7 => Some(Self::ReconnectMarker),
            _ => None,
        }
    }

    /// Convert `RecordKind` to its `i32` tag without `as` cast.
    #[must_use]
    pub const fn to_i32(self) -> i32 {
        match self {
            Self::BookSnapshot => 1,
            Self::BookDelta => 2,
            Self::Trade => 3,
            Self::GapMarker => 4,
            Self::ResyncMarker => 5,
            Self::RawFrame => 6,
            Self::ReconnectMarker => 7,
        }
    }
}

impl From<RecordKind> for i32 {
    fn from(kind: RecordKind) -> Self {
        kind.to_i32()
    }
}

/// Header record placed at the start of every `.pbz` event log file.
#[derive(Clone, PartialEq, Message)]
pub struct LogHeader {
    /// Schema version of the file
    #[prost(uint32, tag = "1")]
    pub schema_version: u32,
    /// Exchange venue identifier (e.g. "BINANCE")
    #[prost(string, tag = "2")]
    pub venue: String,
    /// Symbol identifier (e.g. "BTC-USDT")
    #[prost(string, tag = "3")]
    pub symbol: String,
    /// Price decimal scale from instrument spec
    #[prost(uint32, tag = "4")]
    pub price_scale: u32,
    /// Quantity decimal scale from instrument spec
    #[prost(uint32, tag = "5")]
    pub qty_scale: u32,
    /// Minimum price tick size in price units
    #[prost(int64, tag = "6")]
    pub tick_size: i64,
    /// Minimum quantity lot size in qty units
    #[prost(int64, tag = "7")]
    pub lot_size: i64,
    /// Timestamp when this log file was opened
    #[prost(int64, tag = "8")]
    pub start_ts_ns: i64,
    /// Version string of the writing service
    #[prost(string, tag = "9")]
    pub writer_version: String,
    /// Host clock / environment info
    #[prost(string, tag = "10")]
    pub host_info: String,
}

/// A single event record in the log.
#[derive(Clone, PartialEq, Message)]
pub struct LogRecord {
    /// Schema version
    #[prost(uint32, tag = "1")]
    pub schema_version: u32,
    /// Local time when frame was received (nanoseconds since UNIX epoch)
    #[prost(int64, tag = "2")]
    pub recv_ts_ns: i64,
    /// Venue event timestamp (nanoseconds since UNIX epoch)
    #[prost(int64, tag = "3")]
    pub exchange_ts_ns: i64,
    /// Record kind integer tag
    #[prost(int32, tag = "4")]
    pub kind: i32,
    /// Serialized payload bytes
    #[prost(bytes = "vec", tag = "5")]
    pub payload: Vec<u8>,
}

/// A single price level in protobuf format.
#[derive(Clone, PartialEq, Message)]
pub struct LevelProto {
    /// Price in price units
    #[prost(int64, tag = "1")]
    pub price: i64,
    /// Quantity in qty units (0 in delta = delete)
    #[prost(int64, tag = "2")]
    pub qty: i64,
}

/// Order-book update payload (bids and asks).
#[derive(Clone, PartialEq, Message)]
pub struct BookUpdateProto {
    /// Bid levels
    #[prost(message, repeated, tag = "1")]
    pub bids: Vec<LevelProto>,
    /// Ask levels
    #[prost(message, repeated, tag = "2")]
    pub asks: Vec<LevelProto>,
}

/// Trade payload.
#[derive(Clone, PartialEq, Message)]
pub struct TradeProto {
    /// Trade price in price units
    #[prost(int64, tag = "1")]
    pub price: i64,
    /// Trade quantity in qty units
    #[prost(int64, tag = "2")]
    pub qty: i64,
    /// Aggressor side (1 = Buy, 2 = Sell)
    #[prost(int32, tag = "3")]
    pub aggressor: i32,
    /// Venue trade id
    #[prost(string, tag = "4")]
    pub venue_trade_id: String,
}

/// Discontinuity / resync anomaly marker payload.
#[derive(Clone, PartialEq, Message)]
pub struct MarkerProto {
    /// Reason for the gap/resync
    #[prost(string, tag = "1")]
    pub reason: String,
    /// Synchronizer epoch if applicable
    #[prost(uint64, tag = "2")]
    pub epoch: u64,
    /// Expected sequence number
    #[prost(uint64, tag = "3")]
    pub expected_seq: u64,
    /// Received sequence number
    #[prost(uint64, tag = "4")]
    pub received_seq: u64,
}

// ─── Encoding Helpers ────────────────────────────────────────────────────────

/// Encode levels into a `BookUpdateProto` byte buffer.
#[must_use]
pub fn encode_book_payload(bids: &[Level], asks: &[Level]) -> Vec<u8> {
    let bids_proto: Vec<LevelProto> = bids
        .iter()
        .map(|l| LevelProto {
            price: l.price.units(),
            qty: l.qty.units(),
        })
        .collect();
    let asks_proto: Vec<LevelProto> = asks
        .iter()
        .map(|l| LevelProto {
            price: l.price.units(),
            qty: l.qty.units(),
        })
        .collect();

    let proto = BookUpdateProto {
        bids: bids_proto,
        asks: asks_proto,
    };
    proto.encode_to_vec()
}

/// Decode a `BookUpdateProto` byte buffer into vectors of `Level`.
pub fn decode_book_payload(payload: &[u8]) -> Result<(Vec<Level>, Vec<Level>)> {
    let proto = BookUpdateProto::decode(payload)?;
    let mut bids = Vec::with_capacity(proto.bids.len());
    for bp in proto.bids {
        if let (Ok(price), Ok(qty)) = (Price::from_units(bp.price), Qty::from_units(bp.qty)) {
            bids.push(Level::new(price, qty));
        }
    }
    let mut asks = Vec::with_capacity(proto.asks.len());
    for ap in proto.asks {
        if let (Ok(price), Ok(qty)) = (Price::from_units(ap.price), Qty::from_units(ap.qty)) {
            asks.push(Level::new(price, qty));
        }
    }
    Ok((bids, asks))
}

/// Encode a trade into a `TradeProto` byte buffer.
#[must_use]
pub fn encode_trade_payload(
    price: Price,
    qty: Qty,
    aggressor: Aggressor,
    venue_trade_id: &str,
) -> Vec<u8> {
    let aggressor_int = match aggressor {
        Aggressor::Buy => 1,
        Aggressor::Sell => 2,
    };
    let proto = TradeProto {
        price: price.units(),
        qty: qty.units(),
        aggressor: aggressor_int,
        venue_trade_id: venue_trade_id.to_string(),
    };
    proto.encode_to_vec()
}

/// Decoded trade information.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodedTrade {
    /// Trade price
    pub price: Price,
    /// Trade quantity
    pub qty: Qty,
    /// Aggressor side
    pub aggressor: Aggressor,
    /// Venue trade id
    pub venue_trade_id: String,
}

/// Decode a `TradeProto` byte buffer.
pub fn decode_trade_payload(payload: &[u8]) -> Result<DecodedTrade> {
    let proto = TradeProto::decode(payload)?;
    let price = Price::from_units(proto.price)
        .map_err(|e| ReplayError::Book(format!("Invalid trade price: {e}")))?;
    let qty = Qty::from_units(proto.qty)
        .map_err(|e| ReplayError::Book(format!("Invalid trade qty: {e}")))?;
    let aggressor = match proto.aggressor {
        1 => Aggressor::Buy,
        _ => Aggressor::Sell,
    };
    Ok(DecodedTrade {
        price,
        qty,
        aggressor,
        venue_trade_id: proto.venue_trade_id,
    })
}

/// Encode a marker into a `MarkerProto` byte buffer.
#[must_use]
pub fn encode_marker_payload(
    reason: &str,
    epoch: u64,
    expected_seq: u64,
    received_seq: u64,
) -> Vec<u8> {
    let proto = MarkerProto {
        reason: reason.to_string(),
        epoch,
        expected_seq,
        received_seq,
    };
    proto.encode_to_vec()
}

/// Decode a `MarkerProto` byte buffer.
pub fn decode_marker_payload(payload: &[u8]) -> Result<MarkerProto> {
    Ok(MarkerProto::decode(payload)?)
}

/// Create a `LogRecord` for an order-book snapshot.
#[must_use]
pub fn make_snapshot_record(
    recv_ts: TsNs,
    exchange_ts: TsNs,
    bids: &[Level],
    asks: &[Level],
) -> LogRecord {
    LogRecord {
        schema_version: SCHEMA_VERSION,
        recv_ts_ns: recv_ts.as_nanos(),
        exchange_ts_ns: exchange_ts.as_nanos(),
        kind: RecordKind::BookSnapshot.to_i32(),
        payload: encode_book_payload(bids, asks),
    }
}

/// Create a `LogRecord` for an order-book delta.
#[must_use]
pub fn make_delta_record(
    recv_ts: TsNs,
    exchange_ts: TsNs,
    bids: &[Level],
    asks: &[Level],
) -> LogRecord {
    LogRecord {
        schema_version: SCHEMA_VERSION,
        recv_ts_ns: recv_ts.as_nanos(),
        exchange_ts_ns: exchange_ts.as_nanos(),
        kind: RecordKind::BookDelta.to_i32(),
        payload: encode_book_payload(bids, asks),
    }
}

/// Create a `LogRecord` for a trade.
#[must_use]
pub fn make_trade_record(
    recv_ts: TsNs,
    exchange_ts: TsNs,
    price: Price,
    qty: Qty,
    aggressor: Aggressor,
    trade_id: &str,
) -> LogRecord {
    LogRecord {
        schema_version: SCHEMA_VERSION,
        recv_ts_ns: recv_ts.as_nanos(),
        exchange_ts_ns: exchange_ts.as_nanos(),
        kind: RecordKind::Trade.to_i32(),
        payload: encode_trade_payload(price, qty, aggressor, trade_id),
    }
}

/// Create a `LogRecord` for a sequence gap anomaly.
#[must_use]
pub fn make_gap_record(
    recv_ts: TsNs,
    exchange_ts: TsNs,
    expected_seq: u64,
    received_seq: u64,
    reason: &str,
) -> LogRecord {
    LogRecord {
        schema_version: SCHEMA_VERSION,
        recv_ts_ns: recv_ts.as_nanos(),
        exchange_ts_ns: exchange_ts.as_nanos(),
        kind: RecordKind::GapMarker.to_i32(),
        payload: encode_marker_payload(reason, 0, expected_seq, received_seq),
    }
}

/// Create a `LogRecord` for a resync anomaly.
#[must_use]
pub fn make_resync_record(recv_ts: TsNs, exchange_ts: TsNs, epoch: u64, reason: &str) -> LogRecord {
    LogRecord {
        schema_version: SCHEMA_VERSION,
        recv_ts_ns: recv_ts.as_nanos(),
        exchange_ts_ns: exchange_ts.as_nanos(),
        kind: RecordKind::ResyncMarker.to_i32(),
        payload: encode_marker_payload(reason, epoch, 0, 0),
    }
}
