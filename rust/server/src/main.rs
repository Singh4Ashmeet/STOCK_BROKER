//! sb-server binary entry point.
//!
//! Loads config from TOML, registers symbols, starts the gRPC server and HTTP health/metrics server.

use std::sync::Arc;
use std::time::Duration;

// remove axum::Router from top and type http_addr
use sb_book::BookConfig;
use sb_core::InstrumentSpec;
use sb_metrics::engine::MetricsConfig as EngineMetricsConfig;
use sb_server::config::{ServerConfig, load_config};
use sb_server::metrics::{
    CrossCheckConfig, FeedMetrics, HealthTracker, LatencyRecorder, PrometheusMetrics,
    SymbolLatencyRecorders, health_router, start_cross_check_task, start_latency_reporter,
    start_prometheus_server,
};
use sb_server::proto::market_data_server::MarketDataServer;
use sb_server::service::MarketDataService;
use sb_server::state::SymbolState;
use tokio::signal;
use tokio::task::JoinHandle;
use tonic::transport::Server;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Initialise tracing
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive("sb_server=info".parse()?))
        .init();

    // Load config
    let config_path = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "server.toml".to_owned());

    let server_config: ServerConfig = load_config(&config_path)?;

    tracing::info!(
        listen_addr = %server_config.listen_addr,
        http_addr = %server_config.http_addr,
        symbols = ?server_config.symbols,
        "starting sb-server"
    );

    // Create shared components
    let feed_metrics = Arc::new(FeedMetrics::default());
    let prom_metrics = Arc::new(PrometheusMetrics::new()?);
    let latency_recorders: SymbolLatencyRecorders = Arc::new(parking_lot::RwLock::new(
        server_config
            .symbols
            .iter()
            .map(|s| (s.clone(), LatencyRecorder::new()))
            .collect(),
    ));
    let health_tracker = Arc::new(HealthTracker::new(&server_config.symbols));

    // Register symbols from config
    let svc = MarketDataService::with_observability(
        server_config.clone(),
        feed_metrics.clone(),
        prom_metrics.clone(),
        latency_recorders.clone(),
        health_tracker.clone(),
    );
    for inst in &server_config.instruments {
        let spec = InstrumentSpec::new(
            inst.price_scale as u8,
            inst.qty_scale as u8,
            inst.tick_size,
            inst.lot_size,
            inst.min_notional,
        )?;

        let book_config = BookConfig::default();

        let ns_per_sec: i64 = 1_000_000_000;
        let metrics_config = EngineMetricsConfig {
            depth_levels: server_config.metrics.depth_levels,
            vwap_window_ns: i64::try_from(server_config.metrics.vwap_window_secs)?
                .checked_mul(ns_per_sec)
                .ok_or("vwap_window_ns overflow")?,
            trade_flow_window_ns: i64::try_from(server_config.metrics.trade_flow_window_secs)?
                .checked_mul(ns_per_sec)
                .ok_or("trade_flow_window_ns overflow")?,
            vwap_capacity: 4096,
            trade_flow_capacity: 4096,
        };

        let state = SymbolState::new(spec, book_config, metrics_config, 50)?;
        svc.register_symbol(inst.symbol.clone(), state).await;
        tracing::info!(symbol = %inst.symbol, "registered symbol");
    }

    let svc = Arc::new(svc);

    // Start gRPC server
    let grpc_addr = server_config.listen_addr.parse()?;
    let grpc_svc = Arc::clone(&svc);
    let grpc_handle = tokio::spawn(async move {
        tracing::info!(%grpc_addr, "gRPC server listening");
        if let Err(e) = Server::builder()
            .add_service(MarketDataServer::from_arc(grpc_svc))
            .serve(grpc_addr)
            .await
        {
            tracing::error!("gRPC server error: {}", e);
        }
    });

    // Start HTTP health/metrics server (includes /metrics endpoint)
    let http_addr: std::net::SocketAddr = server_config.http_addr.parse()?;
    let health_router = health_router(&health_tracker, &prom_metrics);
    let http_handle = tokio::spawn(async move {
        tracing::info!(%http_addr, "HTTP server listening (health + metrics)");
        if let Ok(listener) = tokio::net::TcpListener::bind(http_addr).await {
            let _ = axum::serve(listener, health_router).await;
        }
    });

    // Start Prometheus metrics exporter if enabled (on separate port)
    let mut handles: Vec<JoinHandle<()>> = vec![grpc_handle, http_handle];

    if server_config.observability.enable_prometheus {
        let prom_addr = format!("127.0.0.1:{}", server_config.observability.prometheus_port);
        let prom_addr: std::net::SocketAddr =
            prom_addr.parse().expect("invalid prometheus address");
        let prom_handle = start_prometheus_server(prom_addr, &prom_metrics).await?;
        handles.push(prom_handle);
        tracing::info!(%prom_addr, "Prometheus exporter listening");
    }

    // Start latency reporter
    let latency_interval =
        Duration::from_secs(server_config.observability.latency_report_interval_secs);
    let latency_handle = start_latency_reporter(
        prom_metrics.clone(),
        latency_recorders.clone(),
        latency_interval,
    );
    handles.push(latency_handle);

    // Start cross-check task if enabled
    if server_config.observability.cross_check_interval_secs > 0 {
        let cross_check_config = CrossCheckConfig {
            interval: Duration::from_secs(server_config.observability.cross_check_interval_secs),
            exchange_endpoint: server_config.observability.exchange_endpoint.clone(),
            depth: server_config.observability.cross_check_depth,
            qty_tolerance: server_config.observability.cross_check_qty_tolerance,
        };

        let get_local_book = {
            let _svc = Arc::clone(&svc);
            move |_symbol: &str| -> Option<(Vec<sb_core::Level>, Vec<sb_core::Level>)> {
                // This would need access to the SymbolState - for now we'll use a simpler approach
                // The service would need to expose a way to get the current book state
                None // TODO: wire this up properly
            }
        };

        let cross_check_handle = start_cross_check_task(
            health_tracker.clone(),
            server_config.symbols.clone(),
            get_local_book,
            cross_check_config,
        );
        handles.push(cross_check_handle);
        tracing::info!(
            "Cross-check task started (interval: {}s)",
            server_config.observability.cross_check_interval_secs
        );
    }

    // Wait for shutdown signal
    signal::ctrl_c().await?;
    tracing::info!("Shutdown signal received, stopping...");

    // Abort all tasks
    for handle in handles {
        handle.abort();
    }

    Ok(())
}
