//! Deterministic replay driver using the exact same code path as live feed.
//!
//! Feeds recorded event-log records into `sb-book` and `sb-metrics`.
//! Guarantee: replaying the same day twice yields byte-identical books and metrics.

use std::path::Path;

use sb_book::{Book, BookConfig};
use sb_core::{InstrumentSpec, Level, TsNs};
use sb_metrics::MetricsSnapshot;
use sb_metrics::engine::{MetricsConfig, MetricsEngine};

use crate::error::{ReplayError, Result};
use crate::reader::EventLogReader;
use crate::schema::{RecordKind, decode_book_payload, decode_marker_payload, decode_trade_payload};

/// Summary of a completed replay session.
#[derive(Debug, Clone, PartialEq)]
pub struct ReplaySummary {
    /// Total log records read and dispatched
    pub total_records: u64,
    /// Order-book snapshots applied
    pub snapshots_count: u64,
    /// Order-book deltas applied
    pub deltas_count: u64,
    /// Public trades processed
    pub trades_count: u64,
    /// Anomaly markers encountered
    pub markers_count: u64,
    /// Number of gap/resync markers detected — indicates dropped or lost data
    pub gaps_detected: u64,
    /// Whether the replay is known to be incomplete (gaps > 0)
    pub incomplete: bool,
    /// Final metrics snapshot at the end of replay
    pub final_metrics: MetricsSnapshot,
    /// Deterministic fingerprint hash of the final book state
    pub book_checksum: u64,
}

/// The deterministic replay driver.
///
/// Drives `sb-book::Book` and `sb-metrics::MetricsEngine` over recorded
/// `.pbz` event logs using the exact same methods invoked by the live feed.
pub struct ReplayDriver {
    spec: InstrumentSpec,
    book: Book,
    metrics: MetricsEngine,
    records_processed: u64,
    snapshots_applied: u64,
    deltas_applied: u64,
    trades_applied: u64,
    markers_seen: u64,
    gaps_detected: u64,
}

impl ReplayDriver {
    /// Create a new `ReplayDriver` with given book and metrics configuration.
    pub fn new(
        spec: InstrumentSpec,
        book_config: BookConfig,
        metrics_config: MetricsConfig,
    ) -> Result<Self> {
        let book = Book::new(book_config, spec)
            .map_err(|e| ReplayError::Book(format!("Failed to create Book: {e:?}")))?;
        let metrics = MetricsEngine::new(metrics_config);

        Ok(Self {
            spec,
            book,
            metrics,
            records_processed: 0,
            snapshots_applied: 0,
            deltas_applied: 0,
            trades_applied: 0,
            markers_seen: 0,
            gaps_detected: 0,
        })
    }

    /// Access the instrument specification.
    #[must_use]
    pub fn spec(&self) -> &InstrumentSpec {
        &self.spec
    }

    /// Access the underlying order book.
    #[must_use]
    pub fn book(&self) -> &Book {
        &self.book
    }

    /// Access the underlying metrics engine.
    #[must_use]
    pub fn metrics(&self) -> &MetricsEngine {
        &self.metrics
    }

    /// Current metrics snapshot.
    #[must_use]
    pub fn snapshot(&self) -> MetricsSnapshot {
        *self.metrics.snapshot()
    }

    /// Process a single recorded `LogRecord` through the live feed pipeline.
    pub fn process_record(
        &mut self,
        kind: RecordKind,
        payload: &[u8],
        exchange_ts: TsNs,
    ) -> Result<()> {
        self.records_processed = self.records_processed.saturating_add(1);
        match kind {
            RecordKind::BookSnapshot => {
                let (bids, asks) = decode_book_payload(payload)?;
                self.book
                    .apply_snapshot(&bids, &asks)
                    .map_err(|e| ReplayError::Book(format!("apply_snapshot failed: {e:?}")))?;
                // Live code path: update metrics after book state changes
                self.metrics.on_book_update(&self.book);
                self.snapshots_applied = self.snapshots_applied.saturating_add(1);
            }
            RecordKind::BookDelta => {
                let (bids, asks) = decode_book_payload(payload)?;
                let _apply_res = self.book.apply_delta(&bids, &asks);
                // Live code path: update metrics after delta
                self.metrics.on_book_update(&self.book);
                self.deltas_applied = self.deltas_applied.saturating_add(1);
            }
            RecordKind::Trade => {
                let trade = decode_trade_payload(payload)?;
                // Live code path: pass trade to metrics engine with venue event time
                self.metrics
                    .on_trade(exchange_ts, trade.price, trade.qty, trade.aggressor);
                self.trades_applied = self.trades_applied.saturating_add(1);
            }
            RecordKind::GapMarker | RecordKind::ResyncMarker | RecordKind::ReconnectMarker => {
                let _marker = decode_marker_payload(payload)?;
                self.markers_seen = self.markers_seen.saturating_add(1);
                // GapMarker explicitly signals data loss — count it
                if matches!(kind, RecordKind::GapMarker) {
                    self.gaps_detected = self.gaps_detected.saturating_add(1);
                }
            }
            RecordKind::RawFrame => {
                // Raw venue frames are retained for debugging/audit; pass
            }
        }
        Ok(())
    }

    /// Replay an entire single `.pbz` event-log file.
    pub fn replay_file<P: AsRef<Path>>(&mut self, path: P) -> Result<()> {
        let mut reader = EventLogReader::open(path)?;
        while let Some(record) = reader.next_record()? {
            if let Some(kind) = RecordKind::from_i32(record.kind) {
                let exchange_ts = TsNs::from_nanos(record.exchange_ts_ns);
                self.process_record(kind, &record.payload, exchange_ts)?;
            }
        }
        Ok(())
    }

    /// Replay multiple `.pbz` files in sequence and return the `ReplaySummary`.
    pub fn replay_files<P: AsRef<Path>>(&mut self, paths: &[P]) -> Result<ReplaySummary> {
        for path in paths {
            self.replay_file(path)?;
        }
        Ok(self.summary())
    }

    /// Scan and replay all recorded hours for a given day:
    /// `{base_dir}/venue={venue}/symbol={symbol}/date={date}/hour=*/part-*.pbz`
    pub fn replay_day(
        &mut self,
        base_dir: &Path,
        venue: &str,
        symbol: &str,
        date: &str,
    ) -> Result<ReplaySummary> {
        let day_dir = base_dir
            .join(format!("venue={venue}"))
            .join(format!("symbol={symbol}"))
            .join(format!("date={date}"));

        if !day_dir.exists() {
            return Err(ReplayError::CorruptedFile {
                path: day_dir,
                reason: format!("Day directory does not exist for date {date}"),
            });
        }

        let mut files = Vec::new();
        for entry in std::fs::read_dir(&day_dir)? {
            let entry = entry?;
            let path = entry.path();
            if path.is_dir() {
                // hour=HH directory
                for file_entry in std::fs::read_dir(&path)? {
                    let file_entry = file_entry?;
                    let fpath = file_entry.path();
                    if fpath.extension().and_then(|s| s.to_str()) == Some("pbz") {
                        files.push(fpath);
                    }
                }
            }
        }

        // Sort files alphabetically to ensure strict chronological hour order
        files.sort();

        self.replay_files(&files)
    }

    /// Compute a deterministic 64-bit fingerprint hash of current book state.
    #[must_use]
    #[allow(clippy::arithmetic_side_effects)]
    pub fn compute_book_checksum(&self) -> u64 {
        use std::hash::{Hash, Hasher};
        let mut hasher = std::collections::hash_map::DefaultHasher::new();

        if let Some(bid) = self.book.best_bid() {
            1u8.hash(&mut hasher);
            bid.price.units().hash(&mut hasher);
            bid.qty.units().hash(&mut hasher);
        } else {
            0u8.hash(&mut hasher);
        }

        if let Some(ask) = self.book.best_ask() {
            1u8.hash(&mut hasher);
            ask.price.units().hash(&mut hasher);
            ask.qty.units().hash(&mut hasher);
        } else {
            0u8.hash(&mut hasher);
        }

        let Ok(p_zero) = sb_core::Price::from_units(0) else {
            return hasher.finish();
        };
        let Ok(q_zero) = sb_core::Qty::from_units(0) else {
            return hasher.finish();
        };
        let dummy = Level::new(p_zero, q_zero);

        // Top 50 levels per side
        let mut bid_levels = [dummy; 50];
        let bid_count = self
            .book
            .levels(sb_core::BookSide::Bid, 50, &mut bid_levels);
        bid_count.hash(&mut hasher);
        for l in bid_levels.iter().take(bid_count) {
            l.price.units().hash(&mut hasher);
            l.qty.units().hash(&mut hasher);
        }

        let mut ask_levels = [dummy; 50];
        let ask_count = self
            .book
            .levels(sb_core::BookSide::Ask, 50, &mut ask_levels);
        ask_count.hash(&mut hasher);
        for l in ask_levels.iter().take(ask_count) {
            l.price.units().hash(&mut hasher);
            l.qty.units().hash(&mut hasher);
        }

        hasher.finish()
    }

    /// Produce a final summary of replay execution.
    #[must_use]
    pub fn summary(&self) -> ReplaySummary {
        let gaps = self.gaps_detected;
        ReplaySummary {
            total_records: self.records_processed,
            snapshots_count: self.snapshots_applied,
            deltas_count: self.deltas_applied,
            trades_count: self.trades_applied,
            markers_count: self.markers_seen,
            gaps_detected: gaps,
            incomplete: gaps > 0,
            final_metrics: self.snapshot(),
            book_checksum: self.compute_book_checksum(),
        }
    }
}
