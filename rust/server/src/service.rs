//! gRPC `MarketData` service implementation.
//!
//! The service manages per-symbol broadcasters and serves the streaming RPCs
//! defined in `market.proto`. Late joiners get a snapshot then deltas with
//! epoch/seq. Each subscriber has a bounded queue; slow consumers are conflated.

use std::collections::HashMap;
use std::pin::Pin;
use std::sync::Arc;

use tokio::sync::{Mutex, RwLock};
use tokio_stream::Stream;
use tonic::{Request, Response, Status};

use crate::broadcaster::{BroadcastTiming, Broadcaster};
use crate::config::ServerConfig;
use crate::convert;
use crate::metrics::{FeedMetrics, HealthTracker, PrometheusMetrics, SymbolLatencyRecorders};
use crate::proto;
use crate::proto::market_data_server::MarketData;
use crate::state::SymbolState;
use sb_core::Clock;

/// Shared server state protected by locks.
///
/// The `RwLock` is for read-heavy access (health checks, snapshots).
/// The `Mutex` on broadcaster is fine because publish is called from the
/// feed task, not the hot path — the hot path is the per-symbol feed thread
/// that owns the `SymbolState` directly. The gRPC layer only reads.
pub struct MarketDataService {
    /// Per-symbol state. Protected by `RwLock` for concurrent reads.
    states: RwLock<HashMap<String, Arc<Mutex<SymbolState>>>>,
    /// Per-symbol broadcasters. The feed task publishes; gRPC tasks subscribe.
    broadcasters: RwLock<HashMap<String, Arc<Mutex<Broadcaster>>>>,
    /// Server configuration.
    config: Arc<ServerConfig>,
    /// Instrument specs cache.
    instruments: Vec<proto::InstrumentSpec>,
    /// Feed metrics for Prometheus (atomic counters for hot path).
    feed_metrics: Option<Arc<FeedMetrics>>,
    /// Prometheus metrics (full counters/gauges/histograms).
    prom_metrics: Option<Arc<PrometheusMetrics>>,
    /// Per-symbol latency recorders.
    latency_recorders: Option<SymbolLatencyRecorders>,
    /// Health tracker.
    health_tracker: Option<Arc<HealthTracker>>,
}

impl MarketDataService {
    /// Create a new `MarketDataService`.
    pub fn new(config: ServerConfig) -> Self {
        let instruments: Vec<proto::InstrumentSpec> = config
            .instruments
            .iter()
            .map(|i| proto::InstrumentSpec {
                symbol: i.symbol.clone(),
                venue: i.symbol.split(':').next().unwrap_or("").to_owned(),
                base_asset: String::new(),
                quote_asset: String::new(),
                price_scale: i.price_scale,
                qty_scale: i.qty_scale,
                tick_size: i.tick_size,
                lot_size: i.lot_size,
                min_notional: i.min_notional,
            })
            .collect();

        Self {
            states: RwLock::new(HashMap::new()),
            broadcasters: RwLock::new(HashMap::new()),
            config: Arc::new(config),
            instruments,
            feed_metrics: None,
            prom_metrics: None,
            latency_recorders: None,
            health_tracker: None,
        }
    }

    /// Create a new `MarketDataService` with observability components attached.
    pub fn with_observability(
        config: ServerConfig,
        feed_metrics: Arc<FeedMetrics>,
        prom_metrics: Arc<PrometheusMetrics>,
        latency_recorders: SymbolLatencyRecorders,
        health_tracker: Arc<HealthTracker>,
    ) -> Self {
        let mut svc = Self::new(config);
        svc.feed_metrics = Some(feed_metrics);
        svc.prom_metrics = Some(prom_metrics);
        svc.latency_recorders = Some(latency_recorders);
        svc.health_tracker = Some(health_tracker);
        svc
    }

    /// Register a symbol with its state and broadcaster.
    /// Called during server startup before accepting connections.
    pub async fn register_symbol(&self, symbol: String, state: SymbolState) {
        let broadcaster = Broadcaster::new(
            self.config.subscriber_queue_capacity,
            self.config.max_subscribers,
        );
        self.states
            .write()
            .await
            .insert(symbol.clone(), Arc::new(Mutex::new(state)));
        self.broadcasters
            .write()
            .await
            .insert(symbol, Arc::new(Mutex::new(broadcaster)));
    }

    /// Get the broadcaster for a symbol (for the feed task to publish into).
    pub async fn get_broadcaster(&self, symbol: &str) -> Option<Arc<Mutex<Broadcaster>>> {
        self.broadcasters.read().await.get(symbol).cloned()
    }

    /// Get the state for a symbol.
    pub async fn get_state(&self, symbol: &str) -> Option<Arc<Mutex<SymbolState>>> {
        self.states.read().await.get(symbol).cloned()
    }

    /// Publish a book update (snapshot or delta) with latency tracking.
    ///
    /// This is called by the feed task after applying a snapshot/delta to the book.
    /// It handles:
    /// - Recording stage latencies (parse, `book_metrics`, `queue_publish`, internal)
    /// - Publishing to all subscribers via the broadcaster
    /// - Updating health state
    pub async fn publish_book_update(
        &self,
        symbol: &str,
        is_snapshot: bool,
        bids: &[crate::broadcaster::BroadcastLevel],
        asks: &[crate::broadcaster::BroadcastLevel],
        recv_ts_ns: i64,
        exchange_ts_ns: i64,
    ) {
        let publish_start = std::time::Instant::now();
        let publish_ts_ns = sb_core::time::SystemClock::new().wall_ns().as_nanos();

        // Get state for epoch/seq and health
        let (epoch, seq, book_valid, far_bid, far_ask, local_bids, local_asks) = {
            let states = self.states.read().await;
            let Some(state_arc) = states.get(symbol) else {
                return;
            };
            let st = state_arc.lock().await;
            (
                st.epoch(),
                st.seq(),
                st.book_valid(),
                st.far_bid_count(),
                st.far_ask_count(),
                st.snapshot_bids().to_vec(),
                st.snapshot_asks().to_vec(),
            )
        };

        // Record queue+publish latency
        let queue_publish_ns =
            u64::try_from(publish_start.elapsed().as_nanos()).unwrap_or(u64::MAX);

        // Record internal latency (T4 - T1)
        let internal_ns = u64::try_from((publish_ts_ns - recv_ts_ns).max(0)).unwrap_or(0);

        // Record to latency recorders (HdrHistogram) and Prometheus
        if let Some(recorders) = &self.latency_recorders {
            // Record to HdrHistogram only
            if let Some(recorder) = recorders.read().get(symbol) {
                recorder.record(crate::metrics::Stage::QueuePublish, queue_publish_ns);
                recorder.record(crate::metrics::Stage::Internal, internal_ns);
            }
        }

        // Record to Prometheus metrics
        if let Some(prom) = &self.prom_metrics {
            prom.record_stage_latency(crate::metrics::Stage::QueuePublish, queue_publish_ns);
            prom.record_stage_latency(crate::metrics::Stage::Internal, internal_ns);
            prom.inc_messages(if is_snapshot { "snapshot" } else { "delta" });
        }

        // Update feed metrics (atomic counters)
        if let Some(metrics) = &self.feed_metrics {
            metrics.inc_messages(if is_snapshot { "snapshot" } else { "delta" });
        }

        // Update health tracker
        if let Some(health) = &self.health_tracker {
            let last_event_ts = sb_core::TsNs::from_nanos(exchange_ts_ns.max(recv_ts_ns));
            health.on_book_update(
                symbol,
                epoch,
                seq,
                book_valid,
                last_event_ts,
                far_bid,
                far_ask,
            );
        }

        // Update Prometheus gauges for health
        if let Some(prom) = &self.prom_metrics {
            prom.set_book_valid(symbol, book_valid);
            prom.set_epoch(symbol, epoch.id());
            prom.set_far_level_count(symbol, "bid", far_bid);
            prom.set_far_level_count(symbol, "ask", far_ask);
        }

        // Publish to broadcaster
        let broadcaster = self.get_broadcaster(symbol).await;
        if let Some(bc_arc) = broadcaster {
            let mut bc = bc_arc.lock().await;
            let timing = BroadcastTiming {
                exchange_ts_ns,
                recv_ts_ns,
                publish_ts_ns,
            };

            if is_snapshot {
                bc.publish_snapshot(symbol, bids, asks, timing);
            } else {
                bc.publish_delta(
                    symbol,
                    bids,
                    asks,
                    timing,
                    &convert::levels_to_broadcast(&local_bids),
                    &convert::levels_to_broadcast(&local_asks),
                );
            }
        }
    }

    /// Record parse latency (T2 - T1).
    pub fn record_parse_latency(&self, symbol: &str, nanos: u64) {
        if let Some(recorders) = &self.latency_recorders {
            let guard = recorders.read();
            if let Some(recorder) = guard.get(symbol) {
                recorder.record(crate::metrics::Stage::Parse, nanos);
            }
        }
        if let Some(prom) = &self.prom_metrics {
            prom.record_stage_latency(crate::metrics::Stage::Parse, nanos);
        }
    }

    /// Record book+metrics latency (T3 - T2).
    pub fn record_book_metrics_latency(&self, symbol: &str, nanos: u64) {
        if let Some(recorders) = &self.latency_recorders {
            let guard = recorders.read();
            if let Some(recorder) = guard.get(symbol) {
                recorder.record(crate::metrics::Stage::BookMetrics, nanos);
            }
        }
        if let Some(prom) = &self.prom_metrics {
            prom.record_stage_latency(crate::metrics::Stage::BookMetrics, nanos);
        }
    }

    /// Record a resync event.
    pub fn record_resync(&self, symbol: &str, reason: &str) {
        if let Some(health) = &self.health_tracker {
            health.on_resync(symbol, reason);
        }
        if let Some(metrics) = &self.feed_metrics {
            metrics.inc_resync(symbol, reason);
        }
        if let Some(prom) = &self.prom_metrics {
            prom.inc_resync(symbol, reason);
        }
    }

    /// Record a reconnect event.
    pub fn record_reconnect(&self, venue: &str) {
        if let Some(metrics) = &self.feed_metrics {
            metrics.inc_reconnect(venue);
        }
        if let Some(prom) = &self.prom_metrics {
            prom.inc_reconnect(venue);
        }
    }

    /// Record a conflation event.
    pub fn record_conflation(&self, symbol: &str) {
        if let Some(metrics) = &self.feed_metrics {
            metrics.inc_conflation(symbol);
        }
        if let Some(prom) = &self.prom_metrics {
            prom.inc_conflation(symbol);
        }
    }

    /// Get the current book snapshot for cross-checking.
    pub async fn get_book_snapshot(
        &self,
        symbol: &str,
    ) -> Option<(Vec<sb_core::Level>, Vec<sb_core::Level>)> {
        let states = self.states.read().await;
        let state_arc = states.get(symbol)?;
        let st = state_arc.lock().await;
        Some((st.snapshot_bids().to_vec(), st.snapshot_asks().to_vec()))
    }

    /// Get health state for HTTP endpoint.
    pub async fn get_health_state(&self) -> crate::metrics::FeedHealthState {
        if let Some(health) = &self.health_tracker {
            health.get_health()
        } else {
            let states = self.states.read().await;
            let mut symbols = Vec::new();
            for (sym, state_arc) in states.iter() {
                let st = state_arc.lock().await;
                symbols.push(crate::metrics::SymbolHealthState {
                    symbol: sym.clone(),
                    book_valid: st.book_valid(),
                    epoch: st.epoch().id(),
                    seq: st.seq().id(),
                    resync_count: 0,
                    last_event_age_ms: 0,
                    last_cross_check_ms: 0,
                    cross_check_mismatches: 0,
                    far_bid_levels: st.far_bid_count(),
                    far_ask_levels: st.far_ask_count(),
                });
            }
            crate::metrics::FeedHealthState {
                connected: true,
                symbols,
                build_version: env!("CARGO_PKG_VERSION").to_owned(),
                uptime_seconds: 0,
            }
        }
    }
}

type BookStream = Pin<Box<dyn Stream<Item = Result<proto::BookUpdate, Status>> + Send>>;
type MetricsStream = Pin<Box<dyn Stream<Item = Result<proto::Metrics, Status>> + Send>>;
type TradeStream = Pin<Box<dyn Stream<Item = Result<proto::Trade, Status>> + Send>>;

#[tonic::async_trait]
impl MarketData for MarketDataService {
    async fn list_instruments(
        &self,
        _request: Request<proto::ListInstrumentsRequest>,
    ) -> Result<Response<proto::ListInstrumentsResponse>, Status> {
        Ok(Response::new(proto::ListInstrumentsResponse {
            instruments: self.instruments.clone(),
        }))
    }

    type SubscribeBookStream = BookStream;

    async fn subscribe_book(
        &self,
        request: Request<proto::SubscribeRequest>,
    ) -> Result<Response<Self::SubscribeBookStream>, Status> {
        let req = request.into_inner();

        // Validate
        if req.symbols.is_empty() {
            return Err(Status::invalid_argument("symbols must not be empty"));
        }
        if req.depth_levels > self.config.max_depth_levels {
            return Err(Status::invalid_argument(format!(
                "depth_levels exceeds maximum ({})",
                self.config.max_depth_levels
            )));
        }

        // For simplicity, subscribe to the first symbol.
        // Multi-symbol subscriptions would multiplex across broadcasters.
        let symbol = &req.symbols[0];

        let broadcaster = self
            .broadcasters
            .read()
            .await
            .get(symbol)
            .cloned()
            .ok_or_else(|| Status::not_found(format!("unknown symbol: {symbol}")))?;

        let state = self
            .states
            .read()
            .await
            .get(symbol)
            .cloned()
            .ok_or_else(|| Status::not_found(format!("unknown symbol: {symbol}")))?;

        // Subscribe — get a bounded channel
        let handle = {
            let mut bc = broadcaster.lock().await;
            bc.subscribe()
                .map_err(|e| Status::resource_exhausted(e.to_string()))?
        };
        let sub_id = handle.id;

        // Send initial snapshot
        {
            let st = state.lock().await;
            let bids = convert::levels_to_broadcast(st.snapshot_bids());
            let asks = convert::levels_to_broadcast(st.snapshot_asks());
            let timing = BroadcastTiming {
                exchange_ts_ns: 0,
                recv_ts_ns: 0,
                publish_ts_ns: now_ns(),
            };
            let mut bc = broadcaster.lock().await;
            let _ = bc.send_initial_snapshot(sub_id, symbol, &bids, &asks, timing);
        }

        // Create the output stream from the receiver
        let rx = handle.rx;
        let bc_clone = Arc::clone(&broadcaster);
        let stream = async_stream::stream! {
            let mut rx = rx;
            while let Some(msg) = rx.recv().await {
                if let Some(update) = convert::outbound_to_book_update(&msg) {
                    yield Ok(update);
                }
            }
            // Clean up on stream end
            let mut bc = bc_clone.lock().await;
            bc.unsubscribe(sub_id);
        };

        Ok(Response::new(Box::pin(stream)))
    }

    type SubscribeTradesStream = TradeStream;

    async fn subscribe_trades(
        &self,
        _request: Request<proto::SubscribeRequest>,
    ) -> Result<Response<Self::SubscribeTradesStream>, Status> {
        // Trade streaming is not yet implemented (requires connectors integration).
        Err(Status::unimplemented("SubscribeTrades not yet implemented"))
    }

    type SubscribeMetricsStream = MetricsStream;

    async fn subscribe_metrics(
        &self,
        request: Request<proto::SubscribeRequest>,
    ) -> Result<Response<Self::SubscribeMetricsStream>, Status> {
        let req = request.into_inner();

        if req.symbols.is_empty() {
            return Err(Status::invalid_argument("symbols must not be empty"));
        }

        let symbol = &req.symbols[0];

        let broadcaster = self
            .broadcasters
            .read()
            .await
            .get(symbol)
            .cloned()
            .ok_or_else(|| Status::not_found(format!("unknown symbol: {symbol}")))?;

        let handle = {
            let mut bc = broadcaster.lock().await;
            bc.subscribe()
                .map_err(|e| Status::resource_exhausted(e.to_string()))?
        };
        let sub_id = handle.id;

        let rx = handle.rx;
        let bc_clone = Arc::clone(&broadcaster);
        let stream = async_stream::stream! {
            let mut rx = rx;
            while let Some(msg) = rx.recv().await {
                if let Some(metrics) = convert::outbound_to_metrics(&msg) {
                    yield Ok(metrics);
                }
            }
            let mut bc = bc_clone.lock().await;
            bc.unsubscribe(sub_id);
        };

        Ok(Response::new(Box::pin(stream)))
    }

    async fn get_snapshot(
        &self,
        request: Request<proto::SnapshotRequest>,
    ) -> Result<Response<proto::BookUpdate>, Status> {
        let req = request.into_inner();

        let state = self
            .states
            .read()
            .await
            .get(&req.symbol)
            .cloned()
            .ok_or_else(|| Status::not_found(format!("unknown symbol: {}", req.symbol)))?;

        let st = state.lock().await;
        let bids = convert::levels_to_broadcast(st.snapshot_bids());
        let asks = convert::levels_to_broadcast(st.snapshot_asks());

        Ok(Response::new(proto::BookUpdate {
            symbol: req.symbol,
            epoch: st.epoch().id(),
            seq: st.seq().id(),
            timing: Some(proto::Timing {
                exchange_ts_ns: 0,
                recv_ts_ns: 0,
                publish_ts_ns: now_ns(),
            }),
            payload: Some(proto::book_update::Payload::Snapshot(proto::BookSnapshot {
                bids: bids
                    .iter()
                    .map(|l| proto::Level {
                        price: l.price,
                        qty: l.qty,
                    })
                    .collect(),
                asks: asks
                    .iter()
                    .map(|l| proto::Level {
                        price: l.price,
                        qty: l.qty,
                    })
                    .collect(),
            })),
        }))
    }

    async fn health(
        &self,
        _request: Request<proto::HealthRequest>,
    ) -> Result<Response<proto::HealthResponse>, Status> {
        let health_state = self.get_health_state().await;

        let symbols: Vec<proto::SymbolHealth> = health_state
            .symbols
            .into_iter()
            .map(|s| proto::SymbolHealth {
                symbol: s.symbol,
                book_valid: s.book_valid,
                epoch: s.epoch,
                resync_count: s.resync_count,
                last_event_age_ms: s.last_event_age_ms,
            })
            .collect();

        Ok(Response::new(proto::HealthResponse {
            connected: health_state.connected,
            symbols,
            build_version: health_state.build_version,
        }))
    }
}

/// Get current time in nanoseconds.
fn now_ns() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_nanos()).unwrap_or(i64::MAX))
}
