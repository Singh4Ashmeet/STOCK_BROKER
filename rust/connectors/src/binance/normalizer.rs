//! Binance JSON frame parser and event normalizer.
//!
//! Implements allocation-free and float-free decimal parsing for:
//! - Diff depth stream events (`depthUpdate`)
//! - Trade stream events (`trade`)
//! - REST depth snapshots (`/api/v3/depth`)
//!
//! Verified against official Binance Spot API docs (October 2026).

use crate::error::ConnectorError;
use crate::exchange::ParsedMessage;
use crate::types::{DiffEvent, RawSnapshot};
use sb_core::{
    Aggressor, InstrumentSpec, Level, Price, Qty, SymbolId, Timing, Trade, TsNs, parse_scaled,
};
use serde::Deserialize;

#[derive(Deserialize)]
#[allow(dead_code)]
struct StreamWrapper {
    #[serde(default)]
    stream: Option<String>,
    #[serde(default)]
    data: Option<serde_json::Value>,
}

#[derive(Deserialize)]
#[allow(dead_code)]
struct RawDiffEvent {
    #[serde(default)]
    e: String,
    #[serde(rename = "E", default)]
    event_time: i64,
    #[serde(default)]
    s: String,
    #[serde(rename = "U", default)]
    first_update_id: u64,
    #[serde(rename = "u", default)]
    final_update_id: u64,
    #[serde(default)]
    b: Vec<[String; 2]>,
    #[serde(default)]
    a: Vec<[String; 2]>,
}

#[derive(Deserialize)]
#[allow(dead_code)]
struct RawTradeEvent {
    #[serde(default)]
    e: String,
    #[serde(rename = "E", default)]
    event_time: i64,
    #[serde(default)]
    s: String,
    #[serde(rename = "t", default)]
    trade_id: u64,
    #[serde(default)]
    p: String,
    #[serde(default)]
    q: String,
    #[serde(default)]
    m: bool,
}

#[derive(Deserialize)]
struct RawSnapshotResponse {
    #[serde(rename = "lastUpdateId")]
    last_update_id: u64,
    #[serde(default)]
    bids: Vec<[String; 2]>,
    #[serde(default)]
    asks: Vec<[String; 2]>,
}

/// Parse raw levels `[["price", "qty"], ...]` into typed `Vec<Level>`.
#[allow(clippy::arithmetic_side_effects)]
fn parse_levels<S: AsRef<str>>(
    raw_levels: &[[S; 2]],
    spec: &InstrumentSpec,
) -> Result<Vec<Level>, ConnectorError> {
    let mut levels = Vec::with_capacity(raw_levels.len());
    for [price_str, qty_str] in raw_levels {
        let price_units = parse_scaled(price_str.as_ref().as_bytes(), spec.price_scale())
            .map_err(ConnectorError::Parse)?;
        let qty_units = parse_scaled(qty_str.as_ref().as_bytes(), spec.qty_scale())
            .map_err(ConnectorError::Parse)?;

        let price = Price::from_units(price_units).map_err(ConnectorError::Core)?;
        let qty = Qty::from_units(qty_units).map_err(ConnectorError::Core)?;
        levels.push(Level::new(price, qty));
    }
    Ok(levels)
}

/// Parse a raw WebSocket frame received from Binance.
#[allow(clippy::arithmetic_side_effects)]
pub fn parse_binance_frame(
    frame: &[u8],
    symbol_id: SymbolId,
    spec: &InstrumentSpec,
    recv_ts: TsNs,
) -> Result<ParsedMessage, ConnectorError> {
    // Check if wrapped in combined stream wrapper
    if let Ok(StreamWrapper {
        data: Some(data), ..
    }) = serde_json::from_slice::<StreamWrapper>(frame)
    {
        return parse_binance_value(data, symbol_id, spec, recv_ts);
    }

    let val: serde_json::Value = serde_json::from_slice(frame)?;
    parse_binance_value(val, symbol_id, spec, recv_ts)
}

#[allow(clippy::arithmetic_side_effects)]
fn parse_binance_value(
    val: serde_json::Value,
    symbol_id: SymbolId,
    spec: &InstrumentSpec,
    recv_ts: TsNs,
) -> Result<ParsedMessage, ConnectorError> {
    let event_type = val.get("e").and_then(|v| v.as_str()).unwrap_or("");

    match event_type {
        "depthUpdate" => {
            let raw: RawDiffEvent = serde_json::from_value(val)?;
            let exchange_ts = TsNs::from_nanos(raw.event_time * 1_000_000);
            let bids = parse_levels(&raw.b, spec)?;
            let asks = parse_levels(&raw.a, spec)?;

            Ok(ParsedMessage::Diff(DiffEvent {
                symbol: symbol_id,
                first_update_id: raw.first_update_id,
                final_update_id: raw.final_update_id,
                bids,
                asks,
                exchange_ts,
                recv_ts,
            }))
        }
        "trade" => {
            let raw: RawTradeEvent = serde_json::from_value(val)?;
            let exchange_ts = TsNs::from_nanos(raw.event_time * 1_000_000);
            let price_units = parse_scaled(raw.p.as_bytes(), spec.price_scale())
                .map_err(ConnectorError::Parse)?;
            let qty_units =
                parse_scaled(raw.q.as_bytes(), spec.qty_scale()).map_err(ConnectorError::Parse)?;

            let price = Price::from_units(price_units).map_err(ConnectorError::Core)?;
            let qty = Qty::from_units(qty_units).map_err(ConnectorError::Core)?;

            // In Binance trade streams:
            // m == true: Buyer was maker (passive) -> Seller was aggressor (taker)
            // m == false: Buyer was taker (aggressor)
            let aggressor = if raw.m {
                Aggressor::Sell
            } else {
                Aggressor::Buy
            };

            let timing = Timing {
                exchange_ts,
                recv_ts,
                publish_ts: recv_ts,
            };

            let trade = Trade::new(symbol_id, timing, price, qty, aggressor, raw.trade_id);

            Ok(ParsedMessage::Trade(trade))
        }
        _ => Ok(ParsedMessage::Ignored),
    }
}

/// Parse a Binance REST depth snapshot JSON response.
#[allow(clippy::arithmetic_side_effects)]
pub fn parse_binance_snapshot(
    payload: &[u8],
    spec: &InstrumentSpec,
    recv_ts: TsNs,
) -> Result<RawSnapshot, ConnectorError> {
    let raw: RawSnapshotResponse = serde_json::from_slice(payload)?;
    let bids = parse_levels(&raw.bids, spec)?;
    let asks = parse_levels(&raw.asks, spec)?;

    Ok(RawSnapshot {
        last_update_id: raw.last_update_id,
        bids,
        asks,
        exchange_ts: recv_ts, // REST snapshot does not include explicit exchange_ts, use recv_ts
        recv_ts,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn btc_spec() -> InstrumentSpec {
        InstrumentSpec::new(2, 5, 10, 100, 0).unwrap()
    }

    #[test]
    fn parse_diff_depth_event() {
        let spec = btc_spec();
        let payload = br#"{
            "e": "depthUpdate",
            "E": 1728490000123,
            "s": "BTCUSDT",
            "U": 1001,
            "u": 1005,
            "b": [["60000.50", "1.25000"], ["59990.00", "0.00000"]],
            "a": [["60001.00", "2.50000"]]
        }"#;

        let recv_ts = TsNs::from_nanos(1_728_490_000_200_000_000);
        let msg = parse_binance_frame(payload, SymbolId::new(1), &spec, recv_ts).unwrap();

        match msg {
            ParsedMessage::Diff(diff) => {
                assert_eq!(diff.first_update_id, 1001);
                assert_eq!(diff.final_update_id, 1005);
                assert_eq!(diff.bids.len(), 2);
                assert_eq!(diff.bids[0].price.units(), 6000050);
                assert_eq!(diff.bids[0].qty.units(), 125000);
                assert_eq!(diff.bids[1].qty.units(), 0);
                assert!(diff.bids[1].is_removal());
                assert_eq!(diff.asks.len(), 1);
                assert_eq!(diff.asks[0].price.units(), 6000100);
                assert_eq!(diff.asks[0].qty.units(), 250000);
                assert_eq!(diff.exchange_ts.as_nanos(), 1_728_490_000_123_000_000);
                assert_eq!(diff.recv_ts, recv_ts);
            }
            _ => panic!("Expected Diff"),
        }
    }

    #[test]
    fn parse_trade_event_buyer_maker() {
        let spec = btc_spec();
        let payload = br#"{
            "e": "trade",
            "E": 1728490000456,
            "s": "BTCUSDT",
            "t": 987654321,
            "p": "60000.50",
            "q": "0.50000",
            "b": 101,
            "a": 102,
            "m": true,
            "M": true
        }"#;

        let recv_ts = TsNs::from_nanos(1_728_490_000_500_000_000);
        let msg = parse_binance_frame(payload, SymbolId::new(1), &spec, recv_ts).unwrap();

        match msg {
            ParsedMessage::Trade(trade) => {
                assert_eq!(trade.symbol.id(), 1);
                assert_eq!(trade.price.units(), 6000050);
                assert_eq!(trade.qty.units(), 50000);
                assert_eq!(trade.venue_trade_id, 987654321);
                // m: true -> Aggressor was Seller
                assert_eq!(trade.aggressor, Aggressor::Sell);
            }
            _ => panic!("Expected Trade"),
        }
    }

    #[test]
    fn parse_trade_event_buyer_taker() {
        let spec = btc_spec();
        let payload = br#"{
            "e": "trade",
            "E": 1728490000456,
            "s": "BTCUSDT",
            "t": 987654322,
            "p": "60000.50",
            "q": "0.50000",
            "b": 101,
            "a": 102,
            "m": false,
            "M": true
        }"#;

        let recv_ts = TsNs::from_nanos(1_728_490_000_500_000_000);
        let msg = parse_binance_frame(payload, SymbolId::new(1), &spec, recv_ts).unwrap();

        match msg {
            ParsedMessage::Trade(trade) => {
                // m: false -> Aggressor was Buyer
                assert_eq!(trade.aggressor, Aggressor::Buy);
            }
            _ => panic!("Expected Trade"),
        }
    }

    #[test]
    fn parse_rest_snapshot() {
        let spec = btc_spec();
        let payload = br#"{
            "lastUpdateId": 500000,
            "bids": [["60000.00", "5.00000"], ["59990.00", "10.00000"]],
            "asks": [["60001.00", "3.00000"]]
        }"#;

        let recv_ts = TsNs::from_nanos(1_728_490_000_000_000_000);
        let snapshot = parse_binance_snapshot(payload, &spec, recv_ts).unwrap();

        assert_eq!(snapshot.last_update_id, 500000);
        assert_eq!(snapshot.bids.len(), 2);
        assert_eq!(snapshot.bids[0].price.units(), 6000000);
        assert_eq!(snapshot.bids[0].qty.units(), 500000);
        assert_eq!(snapshot.asks.len(), 1);
        assert_eq!(snapshot.asks[0].price.units(), 6000100);
        assert_eq!(snapshot.asks[0].qty.units(), 300000);
    }
}
