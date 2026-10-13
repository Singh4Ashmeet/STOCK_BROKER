//! Per-symbol market state with epoch/seq tracking.
//!
//! The `SymbolState` holds the latest snapshot of the book and metrics for a
//! given symbol. The broadcaster reads from here when a new subscriber joins
//! (late-joiner snapshot) or when a slow subscriber needs conflation.

use sb_book::{Book, BookConfig};
use sb_core::{Epoch, InstrumentSpec, Level, Seq};
use sb_metrics::engine::{MetricsConfig, MetricsEngine};

/// Per-symbol mutable state owned by the feed thread.
///
/// Not shared across threads — the feed thread owns one `SymbolState` per
/// symbol and publishes to subscriber queues via the `Broadcaster`.
pub struct SymbolState {
    /// Current epoch.
    epoch: Epoch,
    /// Next sequence number within the current epoch.
    seq: Seq,
    /// The order book.
    book: Book,
    /// Metrics engine.
    metrics: MetricsEngine,
    /// Whether the book is currently valid (synced with the exchange).
    book_valid: bool,
    /// Cached latest snapshot levels (bids, then asks) for conflation.
    /// Updated on every book change so we can hand it to slow consumers
    /// without re-scanning the book while holding a lock.
    snapshot_bids: Vec<Level>,
    snapshot_asks: Vec<Level>,
    /// Maximum depth to serve.
    max_depth: usize,
}

impl SymbolState {
    /// Create a new symbol state.
    pub fn new(
        spec: InstrumentSpec,
        book_config: BookConfig,
        metrics_config: MetricsConfig,
        max_depth: usize,
    ) -> Result<Self, sb_book::BookError> {
        Ok(Self {
            epoch: Epoch::new(1),
            seq: Seq::new(0),
            book: Book::new(book_config, spec)?,
            metrics: MetricsEngine::new(metrics_config),
            book_valid: false,
            snapshot_bids: Vec::with_capacity(max_depth),
            snapshot_asks: Vec::with_capacity(max_depth),
            max_depth,
        })
    }

    /// Apply a full snapshot (e.g., from exchange REST or conflation resync).
    /// Bumps epoch and resets seq.
    ///
    /// # Errors
    ///
    /// Returns `BookError` if the book rejects the snapshot.
    pub fn apply_snapshot(
        &mut self,
        bids: &[Level],
        asks: &[Level],
    ) -> Result<(), sb_book::BookError> {
        self.book.apply_snapshot(bids, asks)?;
        self.epoch = self.epoch.increment();
        self.seq = Seq::new(1);
        self.book_valid = true;
        self.metrics.on_book_update(&self.book);
        self.cache_snapshot();
        Ok(())
    }

    /// Apply a delta update.
    ///
    /// # Errors
    ///
    /// Returns `BookError` if the book rejects the delta, or `CoreError` on seq overflow.
    pub fn apply_delta(
        &mut self,
        bids: &[Level],
        asks: &[Level],
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        self.book.apply_delta(bids, asks)?;
        self.seq = self.seq.next()?;
        self.metrics.on_book_update(&self.book);
        self.cache_snapshot();
        Ok(())
    }

    /// Record a trade.
    pub fn record_trade(
        &mut self,
        event_ts: sb_core::TsNs,
        price: sb_core::Price,
        qty: sb_core::Qty,
        aggressor: sb_core::Aggressor,
    ) {
        self.metrics.on_trade(event_ts, price, qty, aggressor);
    }

    /// Mark the book as invalid (e.g., during resync).
    pub fn set_invalid(&mut self) {
        self.book_valid = false;
    }

    /// Current epoch.
    pub fn epoch(&self) -> Epoch {
        self.epoch
    }

    /// Current seq.
    pub fn seq(&self) -> Seq {
        self.seq
    }

    /// Whether the book is valid.
    pub fn book_valid(&self) -> bool {
        self.book_valid
    }

    /// Reference to the book.
    pub fn book(&self) -> &Book {
        &self.book
    }

    /// Reference to the metrics engine.
    pub fn metrics(&self) -> &MetricsEngine {
        &self.metrics
    }

    /// Get the cached snapshot bids (best first).
    pub fn snapshot_bids(&self) -> &[Level] {
        &self.snapshot_bids
    }

    /// Get the cached snapshot asks (best first).
    pub fn snapshot_asks(&self) -> &[Level] {
        &self.snapshot_asks
    }

    /// Get the number of far bid levels (outside the dense window).
    pub fn far_bid_count(&self) -> usize {
        self.book.test_bid_far().len()
    }

    /// Get the number of far ask levels (outside the dense window).
    pub fn far_ask_count(&self) -> usize {
        self.book.test_ask_far().len()
    }

    fn cache_snapshot(&mut self) {
        self.snapshot_bids.clear();
        self.snapshot_asks.clear();

        // Re-use the pre-allocated Vecs (clear keeps capacity).
        let mut buf = [Level::new(
            sb_core::Price::ZERO,
            sb_core::Qty::ZERO,
        ); 128];

        let n = if self.max_depth <= 128 {
            self.max_depth
        } else {
            128
        };

        let bid_count = self.book.levels(sb_core::BookSide::Bid, n, &mut buf);
        for level in &buf[..bid_count] {
            self.snapshot_bids.push(*level);
        }

        let ask_count = self.book.levels(sb_core::BookSide::Ask, n, &mut buf);
        for level in &buf[..ask_count] {
            self.snapshot_asks.push(*level);
        }
    }
}
