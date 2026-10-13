//! Exchange connectors for stock_broker.
//!
//! Provides the [`Exchange`] trait, WebSocket connection management with reconnect
//! and exponential backoff with full jitter, rate limiting, Binance spot normalizer,
//! and order book synchronization state machine as specified in `docs/06-ORDER-BOOK-SPEC.md`.

#![deny(unsafe_code)]
#![warn(missing_docs)]

pub mod binance;
pub mod error;
pub mod exchange;
pub mod rate_limit;
pub mod retry;
pub mod types;

pub use binance::{BinanceConfig, BinanceConnector, OrderBookSynchronizer};
pub use error::ConnectorError;
pub use exchange::{Exchange, ParsedMessage};
pub use rate_limit::RateLimiter;
pub use retry::Backoff;
pub use types::{ConnectionState, DiffEvent, MarketEvent, RawRecord, RawSnapshot, StreamSpec};
