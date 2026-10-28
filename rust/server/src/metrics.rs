//! Observability: per-stage latency histograms, Prometheus metrics, and health state.
//!
//! This module provides:
//! - HdrHistogram-based latency tracking for each processing stage (T1-T4)
//! - Prometheus metrics export (`/metrics` endpoint)
//! - Health state tracking (connection, book validity, resync count, mismatches)
//! - Periodic cross-check against exchange REST snapshots

use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use hdrhistogram::Histogram;
use parking_lot::RwLock;
use prometheus::{
    CounterVec, Encoder, GaugeVec, HistogramOpts, HistogramVec, Opts, Registry, TextEncoder,
};
use sb_core::{Clock, Epoch, Level, Seq, TsNs};
use serde_json;
use tracing::{debug, warn};

/// Processing stage identifiers for latency measurement.
///
/// Stages correspond to the measurement points defined in docs/11-PERFORMANCE-AND-OBSERVABILITY.md:
/// - T1: Frame received from socket (recv_ts)
/// - T2: Parse finished
/// - T3: Book and metrics updated
/// - T4: Message handed to gRPC stream (publish_ts)
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Stage {
    /// T2 - T1: JSON parsing + normalization
    Parse,
    /// T3 - T2: Order book apply + metrics computation
    BookMetrics,
    /// T4 - T3: Enqueue to subscriber queues + gRPC handoff
    QueuePublish,
    /// T4 - T1: Full Rust internal processing (headline internal latency)
    Internal,
}

impl Stage {
    /// Prometheus label value for this stage.
    pub fn label(&self) -> &'static str {
        match self {
            Stage::Parse => "parse",
            Stage::BookMetrics => "book_metrics",
            Stage::QueuePublish => "queue_publish",
            Stage::Internal => "internal",
        }
    }
}

/// Latency recorder using HdrHistogram for accurate percentile tracking.
///
/// Pre-allocated to avoid allocations on the hot path.
pub struct LatencyRecorder {
    histograms: parking_lot::Mutex<[Histogram<u64>; 4]>,
}

impl LatencyRecorder {
    /// Create a new latency recorder with histograms covering 1us to 10s with 3 significant figures.
    pub fn new() -> Self {
        let histograms = [
            Histogram::new_with_bounds(1, 10_000_000, 3).expect("parse histogram"),
            Histogram::new_with_bounds(1, 10_000_000, 3).expect("book_metrics histogram"),
            Histogram::new_with_bounds(1, 10_000_000, 3).expect("queue_publish histogram"),
            Histogram::new_with_bounds(1, 10_000_000, 3).expect("internal histogram"),
        ];
        Self {
            histograms: parking_lot::Mutex::new(histograms),
        }
    }

    /// Record a latency sample in nanoseconds for the given stage.
    #[inline]
    pub fn record(&self, stage: Stage, nanos: u64) {
        let idx = stage as usize;
        let mut histograms = self.histograms.lock();
        histograms[idx].saturating_record(nanos);
    }

    /// Get a snapshot of all histograms for Prometheus export.
    pub fn snapshot(&self) -> [Histogram<u64>; 4] {
        let histograms = self.histograms.lock();
        [
            histograms[0].clone(),
            histograms[1].clone(),
            histograms[2].clone(),
            histograms[3].clone(),
        ]
    }

    /// Reset all histograms.
    pub fn reset(&self) {
        let mut histograms = self.histograms.lock();
        for h in histograms.iter_mut() {
            h.reset();
        }
    }
}

impl Default for LatencyRecorder {
    fn default() -> Self {
        Self::new()
    }
}

/// Per-symbol latency recorders.
pub type SymbolLatencyRecorders = Arc<RwLock<std::collections::HashMap<String, LatencyRecorder>>>;

/// Prometheus metrics registry and collectors.
pub struct PrometheusMetrics {
    registry: Registry,
    stage_latency: HistogramVec,
    messages_total: CounterVec,
    resync_total: CounterVec,
    mismatch_total: CounterVec,
    checks_total: CounterVec,
    reconnect_total: CounterVec,
    conflation_total: CounterVec,
    log_dropped_total: CounterVec,
    book_valid: GaugeVec,
    epoch: GaugeVec,
    queue_depth: GaugeVec,
    clock_offset_seconds: prometheus::Gauge,
    far_level_count: GaugeVec,
    recenter_total: CounterVec,
    // Percentile gauges
    latency_p50: GaugeVec,
    latency_p90: GaugeVec,
    latency_p99: GaugeVec,
    latency_p999: GaugeVec,
    latency_max: GaugeVec,
    latency_count: GaugeVec,
}

impl PrometheusMetrics {
    /// Create a new Prometheus metrics instance.
    pub fn new() -> Result<Self, prometheus::Error> {
        let registry = Registry::new();

        let stage_latency = HistogramVec::new(
            HistogramOpts::new(
                "feed_stage_latency_seconds",
                "Latency for each processing stage",
            )
            .buckets(vec![
                0.000_001, 0.000_005, 0.000_01, 0.000_05, 0.0001, 0.0005, 0.001, 0.005, 0.01, 0.05,
                0.1, 0.5, 1.0, 5.0, 10.0,
            ]),
            &["stage"],
        )?;

        let messages_total = CounterVec::new(
            Opts::new("feed_messages_total", "Total messages received by kind"),
            &["kind"],
        )?;

        let resync_total = CounterVec::new(
            Opts::new("feed_resync_total", "Total resyncs triggered"),
            &["symbol", "reason"],
        )?;

        let mismatch_total = CounterVec::new(
            Opts::new("feed_book_mismatch_total", "Total book mismatches detected"),
            &["symbol"],
        )?;

        let checks_total = CounterVec::new(
            Opts::new(
                "feed_book_checks_total",
                "Total book cross-checks performed",
            ),
            &["symbol"],
        )?;

        let reconnect_total = CounterVec::new(
            Opts::new("feed_reconnect_total", "Total reconnects"),
            &["venue"],
        )?;

        let conflation_total = CounterVec::new(
            Opts::new(
                "feed_conflation_total",
                "Total conflations (slow consumers)",
            ),
            &["symbol"],
        )?;

        let log_dropped_total = CounterVec::new(
            Opts::new("feed_log_dropped_total", "Total log records dropped"),
            &["reason"],
        )?;

        let book_valid = GaugeVec::new(
            Opts::new("feed_book_valid", "Book validity (1=valid, 0=invalid)"),
            &["symbol"],
        )?;

        let epoch = GaugeVec::new(Opts::new("feed_epoch", "Current epoch number"), &["symbol"])?;

        let queue_depth = GaugeVec::new(Opts::new("feed_queue_depth", "Queue depth"), &["queue"])?;

        let clock_offset_seconds = prometheus::Gauge::new(
            "feed_clock_offset_seconds",
            "Clock offset from NTP in seconds",
        )?;

        let far_level_count = GaugeVec::new(
            Opts::new("feed_far_level_count", "Far level count"),
            &["symbol", "side"],
        )?;

        let recenter_total = CounterVec::new(
            Opts::new("feed_recenter_total", "Total recenter operations"),
            &["symbol"],
        )?;

        // Percentile gauges
        let latency_p50 = GaugeVec::new(
            Opts::new("feed_stage_latency_p50_seconds", "Stage latency p50"),
            &["symbol", "stage"],
        )?;

        let latency_p90 = GaugeVec::new(
            Opts::new("feed_stage_latency_p90_seconds", "Stage latency p90"),
            &["symbol", "stage"],
        )?;

        let latency_p99 = GaugeVec::new(
            Opts::new("feed_stage_latency_p99_seconds", "Stage latency p99"),
            &["symbol", "stage"],
        )?;

        let latency_p999 = GaugeVec::new(
            Opts::new("feed_stage_latency_p999_seconds", "Stage latency p99.9"),
            &["symbol", "stage"],
        )?;

        let latency_max = GaugeVec::new(
            Opts::new("feed_stage_latency_max_seconds", "Stage latency max"),
            &["symbol", "stage"],
        )?;

        let latency_count = GaugeVec::new(
            Opts::new("feed_stage_latency_count", "Stage latency sample count"),
            &["symbol", "stage"],
        )?;

        // Register all metrics
        registry.register(Box::new(stage_latency.clone()))?;
        registry.register(Box::new(messages_total.clone()))?;
        registry.register(Box::new(resync_total.clone()))?;
        registry.register(Box::new(mismatch_total.clone()))?;
        registry.register(Box::new(checks_total.clone()))?;
        registry.register(Box::new(reconnect_total.clone()))?;
        registry.register(Box::new(conflation_total.clone()))?;
        registry.register(Box::new(log_dropped_total.clone()))?;
        registry.register(Box::new(book_valid.clone()))?;
        registry.register(Box::new(epoch.clone()))?;
        registry.register(Box::new(queue_depth.clone()))?;
        registry.register(Box::new(clock_offset_seconds.clone()))?;
        registry.register(Box::new(far_level_count.clone()))?;
        registry.register(Box::new(recenter_total.clone()))?;
        registry.register(Box::new(latency_p50.clone()))?;
        registry.register(Box::new(latency_p90.clone()))?;
        registry.register(Box::new(latency_p99.clone()))?;
        registry.register(Box::new(latency_p999.clone()))?;
        registry.register(Box::new(latency_max.clone()))?;
        registry.register(Box::new(latency_count.clone()))?;

        Ok(Self {
            registry,
            stage_latency,
            messages_total,
            resync_total,
            mismatch_total,
            checks_total,
            reconnect_total,
            conflation_total,
            log_dropped_total,
            book_valid,
            epoch,
            queue_depth,
            clock_offset_seconds,
            far_level_count,
            recenter_total,
            latency_p50,
            latency_p90,
            latency_p99,
            latency_p999,
            latency_max,
            latency_count,
        })
    }

    /// Get the Prometheus registry.
    pub fn registry(&self) -> &Registry {
        &self.registry
    }

    /// Encode metrics to text format for Prometheus scraping.
    pub fn gather(&self) -> String {
        let encoder = TextEncoder::new();
        let mut buffer = Vec::new();
        encoder
            .encode(&self.registry.gather(), &mut buffer)
            .unwrap();
        String::from_utf8(buffer).unwrap()
    }

    /// Record stage latency.
    pub fn record_stage_latency(&self, stage: Stage, duration_ns: u64) {
        self.stage_latency
            .with_label_values(&[stage.label()])
            .observe(duration_ns as f64 / 1_000_000_000.0);
    }

    /// Increment message counter.
    pub fn inc_messages(&self, kind: &str) {
        self.messages_total.with_label_values(&[kind]).inc();
    }

    /// Increment resync counter.
    pub fn inc_resync(&self, symbol: &str, reason: &str) {
        self.resync_total.with_label_values(&[symbol, reason]).inc();
    }

    /// Increment mismatch counter.
    pub fn inc_mismatch(&self, symbol: &str) {
        self.mismatch_total.with_label_values(&[symbol]).inc();
    }

    /// Increment check counter.
    pub fn inc_check(&self, symbol: &str) {
        self.checks_total.with_label_values(&[symbol]).inc();
    }

    /// Increment reconnect counter.
    pub fn inc_reconnect(&self, venue: &str) {
        self.reconnect_total.with_label_values(&[venue]).inc();
    }

    /// Increment conflation counter.
    pub fn inc_conflation(&self, symbol: &str) {
        self.conflation_total.with_label_values(&[symbol]).inc();
    }

    /// Increment log dropped counter.
    pub fn inc_log_dropped(&self, reason: &str) {
        self.log_dropped_total.with_label_values(&[reason]).inc();
    }

    /// Set book validity gauge.
    pub fn set_book_valid(&self, symbol: &str, valid: bool) {
        self.book_valid
            .with_label_values(&[symbol])
            .set(if valid { 1.0 } else { 0.0 });
    }

    /// Set epoch gauge.
    pub fn set_epoch(&self, symbol: &str, epoch: u64) {
        self.epoch.with_label_values(&[symbol]).set(epoch as f64);
    }

    /// Set queue depth gauge.
    pub fn set_queue_depth(&self, queue: &str, depth: usize) {
        self.queue_depth
            .with_label_values(&[queue])
            .set(depth as f64);
    }

    /// Set clock offset gauge.
    pub fn set_clock_offset(&self, offset_seconds: i64) {
        self.clock_offset_seconds.set(offset_seconds as f64);
    }

    /// Set far level count gauge.
    pub fn set_far_level_count(&self, symbol: &str, side: &str, count: usize) {
        self.far_level_count
            .with_label_values(&[symbol, side])
            .set(count as f64);
    }

    /// Increment recenter counter.
    pub fn inc_recenter(&self, symbol: &str) {
        self.recenter_total.with_label_values(&[symbol]).inc();
    }

    /// Export HdrHistogram percentiles as gauges.
    pub fn export_percentiles(&self, symbol: &str, stage: Stage, h: &Histogram<u64>) {
        let stage_str = stage.label();
        self.latency_p50
            .with_label_values(&[symbol, stage_str])
            .set(h.value_at_percentile(50.0) as f64 / 1_000_000_000.0);
        self.latency_p90
            .with_label_values(&[symbol, stage_str])
            .set(h.value_at_percentile(90.0) as f64 / 1_000_000_000.0);
        self.latency_p99
            .with_label_values(&[symbol, stage_str])
            .set(h.value_at_percentile(99.0) as f64 / 1_000_000_000.0);
        self.latency_p999
            .with_label_values(&[symbol, stage_str])
            .set(h.value_at_percentile(99.9) as f64 / 1_000_000_000.0);
        self.latency_max
            .with_label_values(&[symbol, stage_str])
            .set(h.max() as f64 / 1_000_000_000.0);
        self.latency_count
            .with_label_values(&[symbol, stage_str])
            .set(h.len() as f64);
    }
}

impl Default for PrometheusMetrics {
    fn default() -> Self {
        Self::new().expect("Failed to create Prometheus metrics")
    }
}

/// Global feed metrics using atomic counters for low-overhead hot-path tracking.
#[derive(Debug, Default)]
pub struct FeedMetrics {
    /// Total messages received
    pub messages_total: AtomicU64,
    /// Total resyncs triggered
    pub resync_total: AtomicU64,
    /// Total book mismatches detected
    pub mismatch_total: AtomicU64,
    /// Total book cross-checks performed
    pub checks_total: AtomicU64,
    /// Total reconnects
    pub reconnect_total: AtomicU64,
    /// Total conflations (slow consumers)
    pub conflation_total: AtomicU64,
    /// Total log records dropped (backpressure)
    pub log_dropped_total: AtomicU64,
    /// Current clock offset from NTP (seconds, can be negative) - stored as i64 bits
    pub clock_offset_seconds: AtomicU64,
}

impl FeedMetrics {
    /// Increment message counter (atomic only, for hot path).
    pub fn inc_messages(&self, _kind: &str) {
        self.messages_total.fetch_add(1, Ordering::Relaxed);
    }

    /// Increment resync counter (atomic only, for hot path).
    pub fn inc_resync(&self, _symbol: &str, _reason: &str) {
        self.resync_total.fetch_add(1, Ordering::Relaxed);
    }

    /// Increment mismatch counter (atomic only, for hot path).
    pub fn inc_mismatch(&self, _symbol: &str) {
        self.mismatch_total.fetch_add(1, Ordering::Relaxed);
    }

    /// Increment check counter (atomic only, for hot path).
    pub fn inc_check(&self, _symbol: &str) {
        self.checks_total.fetch_add(1, Ordering::Relaxed);
    }

    /// Increment reconnect counter (atomic only, for hot path).
    pub fn inc_reconnect(&self, _venue: &str) {
        self.reconnect_total.fetch_add(1, Ordering::Relaxed);
    }

    /// Increment conflation counter (atomic only, for hot path).
    pub fn inc_conflation(&self, _symbol: &str) {
        self.conflation_total.fetch_add(1, Ordering::Relaxed);
    }

    /// Increment log dropped counter (atomic only, for hot path).
    pub fn inc_log_dropped(&self, _reason: &str) {
        self.log_dropped_total.fetch_add(1, Ordering::Relaxed);
    }

    /// Set book validity (atomic only).
    pub fn set_book_valid(&self, _symbol: &str, _valid: bool) {
        // No-op for atomic-only version
    }

    /// Set epoch (atomic only).
    pub fn set_epoch(&self, _symbol: &str, _epoch: u64) {
        // No-op for atomic-only version
    }

    /// Set queue depth (atomic only).
    pub fn set_queue_depth(&self, _queue: &str, _depth: usize) {
        // No-op for atomic-only version
    }

    /// Set clock offset (atomic only).
    pub fn set_clock_offset(&self, offset_seconds: i64) {
        self.clock_offset_seconds
            .store(offset_seconds as u64, Ordering::Relaxed);
    }
}

/// Record a stage latency and update Prometheus histogram.
///
/// This is the hot-path function - call with `Instant::now()` deltas.
#[inline]
pub fn record_stage_latency(
    prom_metrics: &PrometheusMetrics,
    recorders: &SymbolLatencyRecorders,
    symbol: &str,
    stage: Stage,
    duration_ns: u64,
) {
    // Record to HdrHistogram
    if let Some(recorder) = recorders.read().get(symbol) {
        recorder.record(stage, duration_ns);
    }

    // Also record to Prometheus histogram
    prom_metrics.record_stage_latency(stage, duration_ns);
}

/// Health state for a single symbol.
#[derive(Debug, Clone, serde::Serialize)]
pub struct SymbolHealthState {
    pub symbol: String,
    pub book_valid: bool,
    pub epoch: u64,
    pub seq: u64,
    pub resync_count: u64,
    pub last_event_age_ms: i64,
    pub last_cross_check_ms: i64,
    pub cross_check_mismatches: u64,
    pub far_bid_levels: usize,
    pub far_ask_levels: usize,
}

/// Overall feed health state.
#[derive(Debug, Clone, serde::Serialize)]
pub struct FeedHealthState {
    pub connected: bool,
    pub symbols: Vec<SymbolHealthState>,
    pub build_version: String,
    pub uptime_seconds: u64,
}

/// Health state tracker.
pub struct HealthTracker {
    start_time: Instant,
    states: Arc<RwLock<std::collections::HashMap<String, SymbolHealthState>>>,
    resync_counts: Arc<RwLock<std::collections::HashMap<String, u64>>>,
    mismatch_counts: Arc<RwLock<std::collections::HashMap<String, u64>>>,
    #[allow(dead_code)]
    cross_check_times: Arc<RwLock<std::collections::HashMap<String, Instant>>>,
}

impl HealthTracker {
    pub fn new(symbols: &[String]) -> Self {
        let mut states = std::collections::HashMap::new();
        let mut resync_counts = std::collections::HashMap::new();
        let mut mismatch_counts = std::collections::HashMap::new();
        let mut cross_check_times = std::collections::HashMap::new();

        for sym in symbols {
            states.insert(
                sym.clone(),
                SymbolHealthState {
                    symbol: sym.clone(),
                    book_valid: false,
                    epoch: 0,
                    seq: 0,
                    resync_count: 0,
                    last_event_age_ms: 0,
                    last_cross_check_ms: 0,
                    cross_check_mismatches: 0,
                    far_bid_levels: 0,
                    far_ask_levels: 0,
                },
            );
            resync_counts.insert(sym.clone(), 0);
            mismatch_counts.insert(sym.clone(), 0);
            cross_check_times.insert(sym.clone(), Instant::now());
        }

        Self {
            start_time: Instant::now(),
            states: Arc::new(RwLock::new(states)),
            resync_counts: Arc::new(RwLock::new(resync_counts)),
            mismatch_counts: Arc::new(RwLock::new(mismatch_counts)),
            cross_check_times: Arc::new(RwLock::new(cross_check_times)),
        }
    }

    /// Update health state after a book update.
    pub fn on_book_update(
        &self,
        symbol: &str,
        epoch: Epoch,
        seq: Seq,
        book_valid: bool,
        last_event_ts: TsNs,
        far_bid: usize,
        far_ask: usize,
    ) {
        let mut states = self.states.write();
        if let Some(state) = states.get_mut(symbol) {
            state.book_valid = book_valid;
            state.epoch = epoch.id();
            state.seq = seq.id();
            state.far_bid_levels = far_bid;
            state.far_ask_levels = far_ask;

            // Age since last event
            let now_ns = sb_core::time::SystemClock::new().wall_ns().as_nanos();
            state.last_event_age_ms = (now_ns - last_event_ts.as_nanos()) / 1_000_000;
        }
        drop(states);
    }

    /// Record a resync event.
    pub fn on_resync(&self, symbol: &str, _reason: &str) {
        let mut counts = self.resync_counts.write();
        *counts.entry(symbol.to_string()).or_insert(0) += 1;
        let count = counts[symbol];
        drop(counts);

        let mut states = self.states.write();
        if let Some(state) = states.get_mut(symbol) {
            state.resync_count = count;
        }
    }

    /// Record a cross-check result.
    pub fn on_cross_check(&self, symbol: &str, mismatches: u64) {
        let mut counts = self.mismatch_counts.write();
        *counts.entry(symbol.to_string()).or_insert(0) += mismatches;
        let total = counts[symbol];
        drop(counts);

        let mut states = self.states.write();
        if let Some(state) = states.get_mut(symbol) {
            state.last_cross_check_ms = sb_core::time::SystemClock::new().wall_ns().as_millis();
            state.cross_check_mismatches = total;
        }
    }

    /// Get the current health state for all symbols.
    pub fn get_health(&self) -> FeedHealthState {
        let states = self.states.read();
        let symbols: Vec<SymbolHealthState> = states.values().cloned().collect();
        drop(states);

        FeedHealthState {
            connected: true, // TODO: track actual connection state
            symbols,
            build_version: env!("CARGO_PKG_VERSION").to_owned(),
            uptime_seconds: self.start_time.elapsed().as_secs(),
        }
    }

    /// Get health for a specific symbol.
    pub fn get_symbol_health(&self, symbol: &str) -> Option<SymbolHealthState> {
        self.states.read().get(symbol).cloned()
    }
}

/// Cross-check the local book against an exchange REST snapshot.
///
/// Returns the number of mismatched levels (price/qty differences beyond tolerance).
pub async fn cross_check_book(
    symbol: &str,
    local_bids: &[Level],
    local_asks: &[Level],
    exchange_url: &str,
) -> Result<u64, Box<dyn std::error::Error + Send + Sync>> {
    // Fetch snapshot from exchange REST API
    let client = reqwest::Client::new();
    let response = client.get(exchange_url).send().await?;

    if !response.status().is_success() {
        return Err(format!("HTTP {}", response.status()).into());
    }

    // Parse response (Binance format as example)
    let json: serde_json::Value = response.json().await?;

    let exchange_bids = json["bids"].as_array().ok_or("missing bids")?;
    let exchange_asks = json["asks"].as_array().ok_or("missing asks")?;

    // Compare top N levels
    let tolerance_qty = 100; // Allow small qty differences due to timing
    let mut mismatches = 0u64;

    // Check bids (top 10)
    for (i, (local, exch)) in local_bids
        .iter()
        .zip(exchange_bids.iter())
        .take(10)
        .enumerate()
    {
        let exch_price = exch[0].as_str().ok_or("bid price")?.parse::<i64>()?;
        let exch_qty = exch[1].as_str().ok_or("bid qty")?.parse::<i64>()?;

        if local.price.units() != exch_price || (local.qty.units() - exch_qty).abs() > tolerance_qty
        {
            mismatches += 1;
            warn!(
                "Book mismatch for {} bid[{}]: local=({},{}) exchange=({},{})",
                symbol,
                i,
                local.price.units(),
                local.qty.units(),
                exch_price,
                exch_qty
            );
        }
    }

    // Check asks (top 10)
    for (i, (local, exch)) in local_asks
        .iter()
        .zip(exchange_asks.iter())
        .take(10)
        .enumerate()
    {
        let exch_price = exch[0].as_str().ok_or("ask price")?.parse::<i64>()?;
        let exch_qty = exch[1].as_str().ok_or("ask qty")?.parse::<i64>()?;

        if local.price.units() != exch_price || (local.qty.units() - exch_qty).abs() > tolerance_qty
        {
            mismatches += 1;
            warn!(
                "Book mismatch for {} ask[{}]: local=({},{}) exchange=({},{})",
                symbol,
                i,
                local.price.units(),
                local.qty.units(),
                exch_price,
                exch_qty
            );
        }
    }

    Ok(mismatches)
}

/// Configuration for the periodic cross-check task.
#[derive(Debug, Clone)]
pub struct CrossCheckConfig {
    /// Interval between cross-checks.
    pub interval: Duration,
    /// Exchange REST endpoint template (use `{symbol}` placeholder).
    pub exchange_endpoint: String,
    /// Number of top levels to compare.
    pub depth: usize,
    /// Quantity tolerance for mismatch detection.
    pub qty_tolerance: i64,
}

impl Default for CrossCheckConfig {
    fn default() -> Self {
        Self {
            interval: Duration::from_secs(60), // Every minute
            exchange_endpoint: "https://api.binance.com/api/v3/depth?symbol={symbol}&limit=20"
                .to_string(),
            depth: 10,
            qty_tolerance: 100,
        }
    }
}

/// Start the periodic cross-check task.
///
/// Spawns a background task that periodically fetches REST snapshots from the exchange
/// and compares them against the local book state.
pub fn start_cross_check_task(
    health: Arc<HealthTracker>,
    symbols: Vec<String>,
    get_local_book: impl Fn(&str) -> Option<(Vec<Level>, Vec<Level>)> + Send + Sync + 'static,
    config: CrossCheckConfig,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(config.interval);
        let client = reqwest::Client::new();

        loop {
            interval.tick().await;

            for symbol in &symbols {
                let Some((local_bids, local_asks)) = get_local_book(symbol) else {
                    debug!("No local book for {}", symbol);
                    continue;
                };

                let url = config
                    .exchange_endpoint
                    .replace("{symbol}", &symbol.replace([':', '-'], ""));

                match fetch_and_compare(
                    &client,
                    symbol,
                    &local_bids,
                    &local_asks,
                    &url,
                    config.depth,
                    config.qty_tolerance,
                )
                .await
                {
                    Ok(mismatches) => {
                        health.on_cross_check(symbol, mismatches);
                        if mismatches > 0 {
                            warn!("Cross-check for {} found {} mismatches", symbol, mismatches);
                        } else {
                            debug!("Cross-check for {} passed", symbol);
                        }
                    }
                    Err(e) => {
                        warn!("Cross-check for {} failed: {}", symbol, e);
                        // Don't count fetch errors as mismatches
                    }
                }
            }
        }
    })
}

async fn fetch_and_compare(
    client: &reqwest::Client,
    symbol: &str,
    local_bids: &[Level],
    local_asks: &[Level],
    url: &str,
    depth: usize,
    qty_tolerance: i64,
) -> Result<u64, Box<dyn std::error::Error + Send + Sync>> {
    let response = client.get(url).send().await?;

    if !response.status().is_success() {
        return Err(format!("HTTP {}", response.status()).into());
    }

    let json: serde_json::Value = response.json().await?;

    let exchange_bids = json["bids"].as_array().ok_or("missing bids")?;
    let exchange_asks = json["asks"].as_array().ok_or("missing asks")?;

    let mut mismatches = 0u64;

    // Check bids
    for (i, (local, exch)) in local_bids
        .iter()
        .zip(exchange_bids.iter())
        .take(depth)
        .enumerate()
    {
        let exch_price = exch[0].as_str().ok_or("bid price")?.parse::<i64>()?;
        let exch_qty = exch[1].as_str().ok_or("bid qty")?.parse::<i64>()?;

        if local.price.units() != exch_price || (local.qty.units() - exch_qty).abs() > qty_tolerance
        {
            mismatches += 1;
            warn!(
                "Book mismatch for {} bid[{}]: local=({},{}) exchange=({},{})",
                symbol,
                i,
                local.price.units(),
                local.qty.units(),
                exch_price,
                exch_qty
            );
        }
    }

    // Check asks
    for (i, (local, exch)) in local_asks
        .iter()
        .zip(exchange_asks.iter())
        .take(depth)
        .enumerate()
    {
        let exch_price = exch[0].as_str().ok_or("ask price")?.parse::<i64>()?;
        let exch_qty = exch[1].as_str().ok_or("ask qty")?.parse::<i64>()?;

        if local.price.units() != exch_price || (local.qty.units() - exch_qty).abs() > qty_tolerance
        {
            mismatches += 1;
            warn!(
                "Book mismatch for {} ask[{}]: local=({},{}) exchange=({},{})",
                symbol,
                i,
                local.price.units(),
                local.qty.units(),
                exch_price,
                exch_qty
            );
        }
    }

    Ok(mismatches)
}

/// Axum router for health and metrics endpoints.
pub fn health_router(
    health: &Arc<HealthTracker>,
    prom_metrics: &Arc<PrometheusMetrics>,
) -> axum::Router {
    use axum::{Json, routing::get};

    let health_clone1 = Arc::clone(health);
    let health_clone2 = Arc::clone(health);
    let prom_metrics_clone = Arc::clone(prom_metrics);

    axum::Router::new()
        .route(
            "/health",
            get({
                move || {
                    let health = Arc::clone(&health_clone1);
                    async move { Json(health.get_health()) }
                }
            }),
        )
        .route(
            "/health/:symbol",
            get({
                move |axum::extract::Path(symbol): axum::extract::Path<String>| {
                    let health = Arc::clone(&health_clone2);
                    async move {
                        health
                            .get_symbol_health(&symbol)
                            .map(Json)
                            .ok_or(axum::http::StatusCode::NOT_FOUND)
                    }
                }
            }),
        )
        .route(
            "/metrics",
            get({
                move || {
                    let prom_metrics = Arc::clone(&prom_metrics_clone);
                    async move {
                        let body = prom_metrics.gather();
                        axum::response::Response::builder()
                            .header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                            .body(axum::body::Body::from(body))
                            .unwrap_or_default()
                    }
                }
            }),
        )
}

/// Start the Prometheus HTTP server.
///
/// Returns an error if the TCP listener cannot bind to the given address.
pub async fn start_prometheus_server(
    addr: std::net::SocketAddr,
    prom_metrics: &Arc<PrometheusMetrics>,
) -> Result<tokio::task::JoinHandle<()>, Box<dyn std::error::Error>> {
    use axum::routing::get;

    let prom_clone = Arc::clone(prom_metrics);
    let router = axum::Router::new().route(
        "/metrics",
        get({
            move || {
                let prom = Arc::clone(&prom_clone);
                async move {
                    let body = prom.gather();
                    axum::response::Response::builder()
                        .header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                        .body(axum::body::Body::from(body))
                        .unwrap_or_default()
                }
            }
        }),
    );

    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .map_err(|e| format!("failed to bind Prometheus exporter to {}: {}", addr, e))?;
    tracing::info!(%addr, "Prometheus exporter bound successfully");

    let handle = tokio::spawn(async move {
        if let Err(e) = axum::serve(listener, router).await {
            tracing::error!(%addr, "Prometheus exporter error: {}", e);
        }
    });

    Ok(handle)
}

/// Latency reporter that periodically exports `HdrHistogram` percentiles to Prometheus gauges.
///
/// This runs on a separate thread to avoid hot-path overhead.
pub fn start_latency_reporter(
    prom_metrics: Arc<PrometheusMetrics>,
    recorders: SymbolLatencyRecorders,
    interval: Duration,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(interval);
        loop {
            interval.tick().await;

            let all_recorders = recorders.read();
            for (symbol, recorder) in all_recorders.iter() {
                let snapshots = recorder.snapshot();

                for (idx, h) in snapshots.iter().enumerate() {
                    let stage = match idx {
                        0 => Stage::Parse,
                        1 => Stage::BookMetrics,
                        2 => Stage::QueuePublish,
                        3 => Stage::Internal,
                        _ => continue,
                    };

                    if !h.is_empty() {
                        prom_metrics.export_percentiles(symbol, stage, h);
                    }
                }
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn latency_recorder_basic() {
        let recorder = LatencyRecorder::new();
        recorder.record(Stage::Parse, 10_000); // 10 us
        recorder.record(Stage::Parse, 20_000); // 20 us

        let snapshots = recorder.snapshot();
        let parse_hist = &snapshots[0];
        assert_eq!(parse_hist.len(), 2);
        // HdrHistogram buckets values with precision, so min/max may have slight bucket rounding
        assert!((parse_hist.min() as i64 - 10_000).abs() <= 50);
        assert!((parse_hist.max() as i64 - 20_000).abs() <= 50);
    }

    #[test]
    fn stage_labels() {
        assert_eq!(Stage::Parse.label(), "parse");
        assert_eq!(Stage::BookMetrics.label(), "book_metrics");
        assert_eq!(Stage::QueuePublish.label(), "queue_publish");
        assert_eq!(Stage::Internal.label(), "internal");
    }

    #[test]
    fn health_tracker_basic() {
        let tracker = HealthTracker::new(&["BTC-USDT".to_string()]);
        let health = tracker.get_health();
        assert_eq!(health.symbols.len(), 1);
        assert_eq!(health.symbols[0].symbol, "BTC-USDT");
        assert!(!health.symbols[0].book_valid);
    }
}
