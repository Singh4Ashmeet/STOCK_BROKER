//! Binance spot market data integration.
//!
//! Submodules:
//! - [`normalizer`]: allocation-free and float-free parsing of Binance diff depth and trade JSON payloads.
//! - [`sync`]: order book synchronization state machine implementing the spec in `docs/06-ORDER-BOOK-SPEC.md`.
//! - [`connector`]: [`BinanceConnector`] implementing the [`Exchange`](crate::Exchange) trait.

pub mod connector;
pub mod normalizer;
pub mod sync;

pub use connector::{BinanceConfig, BinanceConnector};
pub use normalizer::{parse_binance_frame, parse_binance_snapshot};
pub use sync::OrderBookSynchronizer;
