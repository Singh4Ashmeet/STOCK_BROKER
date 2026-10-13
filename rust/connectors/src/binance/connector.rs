//! Binance spot market data connector implementing [`Exchange`].

use crate::binance::normalizer::{parse_binance_frame, parse_binance_snapshot};
use crate::error::ConnectorError;
use crate::exchange::{Exchange, ParsedMessage};
use crate::rate_limit::RateLimiter;
use crate::types::{RawSnapshot, StreamSpec};
use sb_core::{Clock, InstrumentSpec, SymbolId, TsNs};
use std::collections::HashMap;

/// Binance spot connector configuration.
#[derive(Debug, Clone)]
pub struct BinanceConfig {
    /// Base WebSocket URL (default: `wss://stream.binance.com:9443`)
    pub ws_base_url: String,
    /// Base REST API URL (default: `https://api.binance.com`)
    pub rest_base_url: String,
    /// Snapshot depth limit (default: 1000)
    pub snapshot_depth_limit: u32,
}

impl Default for BinanceConfig {
    fn default() -> Self {
        Self {
            ws_base_url: "wss://stream.binance.com:9443".to_string(),
            rest_base_url: "https://api.binance.com".to_string(),
            snapshot_depth_limit: 1000,
        }
    }
}

/// Binance spot market data connector.
pub struct BinanceConnector {
    config: BinanceConfig,
    instruments: Vec<InstrumentSpec>,
    symbol_map: HashMap<SymbolId, (String, InstrumentSpec)>,
    rate_limiter: RateLimiter,
    http_client: reqwest::Client,
}

impl BinanceConnector {
    /// Create a new `BinanceConnector` with default config and default spot instruments.
    ///
    /// Supported default symbols:
    /// - BTC-USDT (price scale 2, qty scale 5, tick 0.01, lot 0.00001)
    /// - ETH-USDT (price scale 2, qty scale 4, tick 0.01, lot 0.0001)
    /// - SOL-USDT (price scale 2, qty scale 3, tick 0.01, lot 0.001)
    ///
    /// # Errors
    /// Returns `ConnectorError` if spec creation fails.
    pub fn new() -> Result<Self, ConnectorError> {
        Self::with_config(BinanceConfig::default())
    }

    /// Create a new `BinanceConnector` with custom configuration.
    ///
    /// # Errors
    /// Returns `ConnectorError` if spec creation fails.
    pub fn with_config(config: BinanceConfig) -> Result<Self, ConnectorError> {
        let btc_spec = InstrumentSpec::new(2, 5, 1, 1, 0).map_err(ConnectorError::Core)?;
        let eth_spec = InstrumentSpec::new(2, 4, 1, 1, 0).map_err(ConnectorError::Core)?;
        let sol_spec = InstrumentSpec::new(2, 3, 1, 1, 0).map_err(ConnectorError::Core)?;

        let mut symbol_map = HashMap::new();
        symbol_map.insert(SymbolId::new(1), ("BTCUSDT".to_string(), btc_spec));
        symbol_map.insert(SymbolId::new(2), ("ETHUSDT".to_string(), eth_spec));
        symbol_map.insert(SymbolId::new(3), ("SOLUSDT".to_string(), sol_spec));

        let instruments = vec![btc_spec, eth_spec, sol_spec];
        // 50 requests/sec limit for REST market data requests
        let rate_limiter = RateLimiter::new(50, 50.0);
        let http_client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(5))
            .build()
            .map_err(|e| ConnectorError::Http(e.to_string()))?;

        Ok(Self {
            config,
            instruments,
            symbol_map,
            rate_limiter,
            http_client,
        })
    }

    /// Register an instrument spec with an explicit symbol mapping.
    pub fn register_symbol(&mut self, id: SymbolId, exchange_symbol: &str, spec: InstrumentSpec) {
        self.symbol_map
            .insert(id, (exchange_symbol.to_string(), spec));
        self.instruments.push(spec);
    }

    /// Look up exchange symbol string (e.g. "BTCUSDT") by SymbolId.
    #[must_use]
    pub fn exchange_symbol(&self, id: SymbolId) -> Option<&str> {
        self.symbol_map.get(&id).map(|(s, _)| s.as_str())
    }

    /// Look up instrument spec by SymbolId.
    #[must_use]
    pub fn spec_by_id(&self, id: SymbolId) -> Option<&InstrumentSpec> {
        self.symbol_map.get(&id).map(|(_, spec)| spec)
    }
}

impl Default for BinanceConnector {
    fn default() -> Self {
        Self::new().expect("default BinanceConnector setup must succeed")
    }
}

impl Exchange for BinanceConnector {
    fn venue(&self) -> &'static str {
        "BINANCE"
    }

    fn instruments(&self) -> &[InstrumentSpec] {
        &self.instruments
    }

    fn subscribe_specs(&self, symbols: &[SymbolId]) -> Vec<StreamSpec> {
        let mut specs = Vec::with_capacity(symbols.len());
        for &id in symbols {
            if let Some((sym, _)) = self.symbol_map.get(&id) {
                let lower = sym.to_ascii_lowercase();
                // Combined diff depth + trade stream URL
                let url = format!(
                    "{}/stream?streams={}@depth@100ms/{}@trade",
                    self.config.ws_base_url, lower, lower
                );
                specs.push(StreamSpec { url, channel: None });
            }
        }
        specs
    }

    fn parse(&self, frame: &[u8], recv_ts: TsNs) -> Result<ParsedMessage, ConnectorError> {
        // Inspect symbol from JSON or default to first configured
        let val: serde_json::Value = serde_json::from_slice(frame)?;
        let raw_symbol = if let Some(data) = val.get("data") {
            data.get("s").and_then(|v| v.as_str())
        } else {
            val.get("s").and_then(|v| v.as_str())
        };

        let (symbol_id, spec) = if let Some(sym_str) = raw_symbol {
            self.symbol_map
                .iter()
                .find(|(_, (s, _))| s.eq_ignore_ascii_case(sym_str))
                .map(|(&id, (_, spec))| (id, spec))
                .unwrap_or((SymbolId::new(1), &self.instruments[0]))
        } else {
            (SymbolId::new(1), &self.instruments[0])
        };

        parse_binance_frame(frame, symbol_id, spec, recv_ts)
    }

    async fn fetch_snapshot(&self, symbol: SymbolId) -> Result<RawSnapshot, ConnectorError> {
        let (exchange_sym, spec) = self.symbol_map.get(&symbol).ok_or_else(|| {
            ConnectorError::UnsupportedSymbol(format!("Unknown symbol id {:?}", symbol))
        })?;

        // Respect rate limiter (weight of depth 1000 is typically 10 or 50)
        self.rate_limiter.acquire(10.0).await;

        let url = format!(
            "{}/api/v3/depth?symbol={}&limit={}",
            self.config.rest_base_url, exchange_sym, self.config.snapshot_depth_limit
        );

        let resp = self
            .http_client
            .get(&url)
            .send()
            .await
            .map_err(|e| ConnectorError::Http(e.to_string()))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(ConnectorError::Http(format!(
                "HTTP {} fetching snapshot from {}: {}",
                status, url, body
            )));
        }

        let bytes = resp
            .bytes()
            .await
            .map_err(|e| ConnectorError::Http(e.to_string()))?;

        let recv_ts = sb_core::SystemClock::new().wall_ns();
        parse_binance_snapshot(&bytes, spec, recv_ts)
    }
}
