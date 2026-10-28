//! TOML-based server configuration.
//!
//! All tunables live here so they are visible in one place. See the example
//! `server.toml` shipped with the crate.

use serde::Deserialize;
use std::sync::Mutex;

#[allow(dead_code)]
static ENV_MUTEX: Mutex<()> = Mutex::new(());

/// Top-level server configuration.
#[derive(Debug, Clone, Deserialize)]
pub struct ServerConfig {
    /// gRPC listen address (e.g. "127.0.0.1:50151").
    #[serde(default = "default_listen_addr")]
    pub listen_addr: String,

    /// HTTP health/metrics listen address (e.g. "127.0.0.1:9090").
    #[serde(default = "default_http_addr")]
    pub http_addr: String,

    /// Symbols to serve (e.g. ["BINANCE:BTC-USDT"]).
    pub symbols: Vec<String>,

    /// Subscriber queue capacity (messages). A slow consumer triggers
    /// conflation when this is exceeded.
    #[serde(default = "default_subscriber_queue_capacity")]
    pub subscriber_queue_capacity: usize,

    /// Maximum number of concurrent subscribers per symbol.
    #[serde(default = "default_max_subscribers")]
    pub max_subscribers: usize,

    /// Maximum depth levels allowed in a subscribe request.
    #[serde(default = "default_max_depth_levels")]
    pub max_depth_levels: u32,

    /// Metrics configuration.
    #[serde(default)]
    pub metrics: MetricsConfig,

    /// Observability configuration.
    #[serde(default)]
    pub observability: ObservabilityConfig,

    /// Instrument specs (normally fetched from the exchange; overridable for tests).
    #[serde(default)]
    pub instruments: Vec<InstrumentConfig>,
}

/// Per-instrument configuration override.
#[derive(Debug, Clone, Deserialize)]
pub struct InstrumentConfig {
    /// Canonical symbol (e.g. "BINANCE:BTC-USDT").
    pub symbol: String,
    /// Price scale (decimal digits).
    pub price_scale: u32,
    /// Quantity scale (decimal digits).
    pub qty_scale: u32,
    /// Tick size in price units.
    pub tick_size: i64,
    /// Lot size in quantity units.
    pub lot_size: i64,
    /// Minimum notional (0 if unknown).
    #[serde(default)]
    pub min_notional: i64,
}

/// Metrics engine tuning.
#[derive(Debug, Clone, Deserialize)]
pub struct MetricsConfig {
    /// Depth levels for imbalance.
    #[serde(default = "default_depth_levels")]
    pub depth_levels: usize,
    /// VWAP window in seconds.
    #[serde(default = "default_vwap_window_secs")]
    pub vwap_window_secs: u64,
    /// Trade-flow window in seconds.
    #[serde(default = "default_trade_flow_window_secs")]
    pub trade_flow_window_secs: u64,
}

impl Default for MetricsConfig {
    fn default() -> Self {
        Self {
            depth_levels: default_depth_levels(),
            vwap_window_secs: default_vwap_window_secs(),
            trade_flow_window_secs: default_trade_flow_window_secs(),
        }
    }
}

/// Observability configuration.
#[derive(Debug, Clone, Deserialize)]
pub struct ObservabilityConfig {
    /// Enable Prometheus metrics exporter.
    #[serde(default = "default_enable_prometheus")]
    pub enable_prometheus: bool,
    /// Prometheus exporter path (e.g. "/metrics").
    #[serde(default = "default_prometheus_path")]
    pub prometheus_path: String,
    /// Prometheus exporter listen port (e.g. 9100). Loopback only.
    #[serde(default = "default_prometheus_port")]
    pub prometheus_port: u16,
    /// Latency reporting interval in seconds.
    #[serde(default = "default_latency_report_interval_secs")]
    pub latency_report_interval_secs: u64,
    /// Cross-check interval in seconds (0 to disable).
    #[serde(default = "default_cross_check_interval_secs")]
    pub cross_check_interval_secs: u64,
    /// Exchange REST endpoint template for cross-check (use {symbol} placeholder).
    #[serde(default = "default_exchange_endpoint")]
    pub exchange_endpoint: String,
    /// Cross-check depth (number of top levels to compare).
    #[serde(default = "default_cross_check_depth")]
    pub cross_check_depth: usize,
    /// Quantity tolerance for cross-check mismatches.
    #[serde(default = "default_cross_check_qty_tolerance")]
    pub cross_check_qty_tolerance: i64,
}

impl Default for ObservabilityConfig {
    fn default() -> Self {
        Self {
            enable_prometheus: default_enable_prometheus(),
            prometheus_path: default_prometheus_path(),
            prometheus_port: default_prometheus_port(),
            latency_report_interval_secs: default_latency_report_interval_secs(),
            cross_check_interval_secs: default_cross_check_interval_secs(),
            exchange_endpoint: default_exchange_endpoint(),
            cross_check_depth: default_cross_check_depth(),
            cross_check_qty_tolerance: default_cross_check_qty_tolerance(),
        }
    }
}

fn default_listen_addr() -> String {
    "127.0.0.1:50151".to_owned()
}
fn default_http_addr() -> String {
    "127.0.0.1:9090".to_owned()
}
fn default_subscriber_queue_capacity() -> usize {
    256
}
fn default_max_subscribers() -> usize {
    1000
}
fn default_max_depth_levels() -> u32 {
    100
}
fn default_depth_levels() -> usize {
    10
}
fn default_vwap_window_secs() -> u64 {
    60
}
fn default_trade_flow_window_secs() -> u64 {
    60
}
fn default_enable_prometheus() -> bool {
    true
}
fn default_prometheus_path() -> String {
    "/metrics".to_owned()
}
fn default_latency_report_interval_secs() -> u64 {
    10
}
fn default_cross_check_interval_secs() -> u64 {
    60
}
fn default_exchange_endpoint() -> String {
    "https://api.binance.com/api/v3/depth?symbol={symbol}&limit=20".to_owned()
}
fn default_cross_check_depth() -> usize {
    10
}
fn default_cross_check_qty_tolerance() -> i64 {
    100
}
fn default_prometheus_port() -> u16 {
    9100
}

/// Load configuration from a TOML file with environment variable overrides.
///
/// # Errors
///
/// Returns an error if the file cannot be read or parsed, or if an
/// environment variable contains an invalid value.
pub fn load_config(path: &str) -> Result<ServerConfig, Box<dyn std::error::Error>> {
    let contents = std::fs::read_to_string(path)?;
    let mut config: ServerConfig = toml::from_str(&contents)?;

    // Apply environment variable override for Prometheus port
    if let Ok(env_val) = std::env::var("SB_FEED_PROMETHEUS_PORT") {
        let port: u16 = env_val.parse().map_err(|_| {
            format!(
                "invalid SB_FEED_PROMETHEUS_PORT value '{}': must be a valid u16",
                env_val
            )
        })?;
        if port == 0 {
            return Err("SB_FEED_PROMETHEUS_PORT must be non-zero".into());
        }
        config.observability.prometheus_port = port;
    }

    Ok(config)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_minimal_config() {
        let toml = r#"
            symbols = ["BINANCE:BTC-USDT"]
        "#;
        let cfg: ServerConfig = toml::from_str(toml).unwrap();
        assert_eq!(cfg.symbols.len(), 1);
        assert_eq!(cfg.listen_addr, "127.0.0.1:50151");
        assert_eq!(cfg.http_addr, "127.0.0.1:9090");
        assert_eq!(cfg.subscriber_queue_capacity, 256);
        assert!(cfg.observability.enable_prometheus);
        assert_eq!(cfg.observability.prometheus_port, 9100);
    }

    #[test]
    fn parse_full_config() {
        let toml = r#"
            listen_addr = "0.0.0.0:9090"
            http_addr = "0.0.0.0:9091"
            symbols = ["BINANCE:BTC-USDT", "BINANCE:ETH-USDT"]
            subscriber_queue_capacity = 512
            max_subscribers = 2000
            max_depth_levels = 50

            [metrics]
            depth_levels = 5
            vwap_window_secs = 120
            trade_flow_window_secs = 30

            [observability]
            enable_prometheus = true
            latency_report_interval_secs = 5
            cross_check_interval_secs = 30
            prometheus_port = 9100

            [[instruments]]
            symbol = "BINANCE:BTC-USDT"
            price_scale = 2
            qty_scale = 5
            tick_size = 10
            lot_size = 100
        "#;
        let cfg: ServerConfig = toml::from_str(toml).unwrap();
        assert_eq!(cfg.listen_addr, "0.0.0.0:9090");
        assert_eq!(cfg.http_addr, "0.0.0.0:9091");
        assert_eq!(cfg.symbols.len(), 2);
        assert_eq!(cfg.subscriber_queue_capacity, 512);
        assert_eq!(cfg.max_subscribers, 2000);
        assert_eq!(cfg.metrics.depth_levels, 5);
        assert_eq!(cfg.observability.latency_report_interval_secs, 5);
        assert_eq!(cfg.observability.cross_check_interval_secs, 30);
        assert_eq!(cfg.observability.prometheus_port, 9100);
        assert_eq!(cfg.instruments.len(), 1);
        assert_eq!(cfg.instruments[0].price_scale, 2);
    }

    #[test]
    fn env_override_prometheus_port() {
        use std::env;
        let _guard = ENV_MUTEX.lock().unwrap();
        let original = env::var("SB_FEED_PROMETHEUS_PORT").ok();
        unsafe {
            env::set_var("SB_FEED_PROMETHEUS_PORT", "9200");
        }

        let toml = r#"
            symbols = ["BINANCE:BTC-USDT"]
        "#;
        let cfg = load_config_from_str(toml).unwrap();
        assert_eq!(cfg.observability.prometheus_port, 9200);

        if let Some(val) = original {
            unsafe {
                env::set_var("SB_FEED_PROMETHEUS_PORT", val);
            }
        } else {
            unsafe {
                env::remove_var("SB_FEED_PROMETHEUS_PORT");
            }
        }
    }

    #[test]
    fn env_override_takes_precedence_over_toml() {
        use std::env;
        let _guard = ENV_MUTEX.lock().unwrap();
        let original = env::var("SB_FEED_PROMETHEUS_PORT").ok();
        unsafe {
            env::set_var("SB_FEED_PROMETHEUS_PORT", "9300");
        }

        let toml = r#"
            symbols = ["BINANCE:BTC-USDT"]
            [observability]
            prometheus_port = 9400
        "#;
        let cfg = load_config_from_str(toml).unwrap();
        assert_eq!(cfg.observability.prometheus_port, 9300);

        if let Some(val) = original {
            unsafe {
                env::set_var("SB_FEED_PROMETHEUS_PORT", val);
            }
        } else {
            unsafe {
                env::remove_var("SB_FEED_PROMETHEUS_PORT");
            }
        }
    }

    #[test]
    fn invalid_env_value_rejected() {
        use std::env;
        let _guard = ENV_MUTEX.lock().unwrap();
        let original = env::var("SB_FEED_PROMETHEUS_PORT").ok();
        unsafe {
            env::set_var("SB_FEED_PROMETHEUS_PORT", "not_a_number");
        }

        let toml = r#"
            symbols = ["BINANCE:BTC-USDT"]
        "#;
        let result = load_config_from_str(toml);
        assert!(result.is_err());
        let err = result.unwrap_err().to_string();
        assert!(err.contains("invalid SB_FEED_PROMETHEUS_PORT value"));

        if let Some(val) = original {
            unsafe {
                env::set_var("SB_FEED_PROMETHEUS_PORT", val);
            }
        } else {
            unsafe {
                env::remove_var("SB_FEED_PROMETHEUS_PORT");
            }
        }
    }

    #[test]
    fn zero_port_rejected() {
        use std::env;
        let _guard = ENV_MUTEX.lock().unwrap();
        let original = env::var("SB_FEED_PROMETHEUS_PORT").ok();
        unsafe {
            env::set_var("SB_FEED_PROMETHEUS_PORT", "0");
        }

        let toml = r#"
            symbols = ["BINANCE:BTC-USDT"]
        "#;
        let result = load_config_from_str(toml);
        assert!(result.is_err());
        let err = result.unwrap_err().to_string();
        assert!(err.contains("non-zero"));

        if let Some(val) = original {
            unsafe {
                env::set_var("SB_FEED_PROMETHEUS_PORT", val);
            }
        } else {
            unsafe {
                env::remove_var("SB_FEED_PROMETHEUS_PORT");
            }
        }
    }

    fn load_config_from_str(toml: &str) -> Result<ServerConfig, Box<dyn std::error::Error>> {
        let mut config: ServerConfig = toml::from_str(toml)?;

        if let Ok(env_val) = std::env::var("SB_FEED_PROMETHEUS_PORT") {
            let port: u16 = env_val.parse().map_err(|_| {
                format!(
                    "invalid SB_FEED_PROMETHEUS_PORT value '{}': must be a valid u16",
                    env_val
                )
            })?;
            if port == 0 {
                return Err("SB_FEED_PROMETHEUS_PORT must be non-zero".into());
            }
            config.observability.prometheus_port = port;
        }

        Ok(config)
    }
}
