//! Exchange abstraction trait for market data feeds.

use crate::error::ConnectorError;
use crate::types::{DiffEvent, RawSnapshot, StreamSpec};
use sb_core::{InstrumentSpec, SymbolId, Trade, TsNs};
use std::future::Future;

/// Outcome of parsing a raw WebSocket payload.
#[derive(Debug, Clone)]
pub enum ParsedMessage {
    /// Depth diff update event
    Diff(DiffEvent),
    /// Executed trade event
    Trade(Trade),
    /// Server keepalive/pong
    Pong,
    /// Known control response or non-actionable message (e.g. subscription ack)
    Ignored,
}

/// Abstract interface for a cryptocurrency or equity exchange feed.
pub trait Exchange: Send + Sync {
    /// Venue identifier (e.g. "BINANCE").
    fn venue(&self) -> &'static str;

    /// Instrument metadata specifications supported by this connector.
    fn instruments(&self) -> &[InstrumentSpec];

    /// Build WebSocket URLs / stream specifications for given symbols.
    fn subscribe_specs(&self, symbols: &[SymbolId]) -> Vec<StreamSpec>;

    /// Parse a raw inbound frame into a normalized message without allocating heap strings.
    ///
    /// `recv_ts` is stamped at socket read time.
    fn parse(&self, frame: &[u8], recv_ts: TsNs) -> Result<ParsedMessage, ConnectorError>;

    /// Asynchronously fetch a REST order book snapshot.
    fn fetch_snapshot(
        &self,
        symbol: SymbolId,
    ) -> impl Future<Output = Result<RawSnapshot, ConnectorError>> + Send;
}
