//! Order book synchronizer state machine per `docs/06-ORDER-BOOK-SPEC.md`.
//!
//! State transitions per symbol:
//! `Disconnected -> Buffering -> Syncing -> Live`, with `Invalid` on failure/gaps.
//!
//! Enforces:
//! - Buffering WebSocket diffs while fetching REST snapshot
//! - Drop events with `u <= lastUpdateId`
//! - Stale/duplicate events in Live state (final_update_id <= prev_u) are dropped
//! - First applied event must satisfy `U <= lastUpdateId + 1 <= u`
//! - Strict gap detection during Live: `U == prev_u + 1`
//! - Crossed book detection marks Invalid
//! - Reconnect and gap trigger new epoch and fresh snapshot

use crate::error::ConnectorError;
use crate::types::{ConnectionState, DiffEvent, MarketEvent, RawSnapshot};
use sb_book::{ApplyResult, Book, BookConfig, BookError};
use sb_core::{BookSide, Clock, Epoch, InstrumentSpec, Level, Price, Qty, Seq, SymbolId, Timing};

/// Per-symbol order book synchronizer.
#[derive(Debug)]
pub struct OrderBookSynchronizer {
    symbol: SymbolId,
    spec: InstrumentSpec,
    book: Book,
    state: ConnectionState,
    epoch: Epoch,
    seq: Seq,
    buffered_diffs: Vec<DiffEvent>,
    prev_u: u64,
    /// Counter for sequence gaps detected.
    gap_count: u64,
    /// Counter for stale/duplicate events silently dropped.
    stale_count: u64,
    /// Counter for resynchronizations triggered.
    resync_count: u64,
}

impl OrderBookSynchronizer {
    /// Create a new synchronizer for a given symbol.
    ///
    /// # Errors
    /// Returns `ConnectorError` if book creation fails.
    pub fn new(
        symbol: SymbolId,
        spec: InstrumentSpec,
        book_config: BookConfig,
    ) -> Result<Self, ConnectorError> {
        let book = Book::new(book_config, spec).map_err(ConnectorError::Book)?;
        Ok(Self {
            symbol,
            spec,
            book,
            state: ConnectionState::Disconnected,
            epoch: Epoch::new(1),
            seq: Seq::new(1),
            buffered_diffs: Vec::with_capacity(1024),
            prev_u: 0,
            gap_count: 0,
            stale_count: 0,
            resync_count: 0,
        })
    }

    /// Current synchronization state.
    #[must_use]
    pub fn state(&self) -> ConnectionState {
        self.state
    }

    /// Access the underlying order book.
    #[must_use]
    pub fn book(&self) -> &Book {
        &self.book
    }

    /// Access the instrument specification.
    #[must_use]
    pub fn spec(&self) -> &InstrumentSpec {
        &self.spec
    }

    /// Access the current epoch.
    #[must_use]
    pub fn epoch(&self) -> Epoch {
        self.epoch
    }

    /// Access the current sequence number.
    #[must_use]
    pub fn seq(&self) -> Seq {
        self.seq
    }

    /// Number of sequence gaps detected.
    #[must_use]
    pub fn gap_count(&self) -> u64 {
        self.gap_count
    }

    /// Number of stale/duplicate events dropped.
    #[must_use]
    pub fn stale_count(&self) -> u64 {
        self.stale_count
    }

    /// Number of resynchronizations triggered.
    #[must_use]
    pub fn resync_count(&self) -> u64 {
        self.resync_count
    }

    /// Called on WebSocket disconnect or connection drop.
    pub fn on_disconnect(&mut self) -> MarketEvent {
        let old_state = self.state;
        self.state = ConnectionState::Disconnected;
        self.epoch = self.epoch.increment();
        self.seq = Seq::new(1);
        self.buffered_diffs.clear();
        self.prev_u = 0;
        self.book.clear();

        MarketEvent::StateChange {
            symbol: self.symbol,
            old_state,
            new_state: ConnectionState::Disconnected,
            epoch: self.epoch,
        }
    }

    /// Transition to `Buffering` state to begin buffering diff events before snapshot.
    pub fn start_buffering(&mut self) -> MarketEvent {
        let old_state = self.state;
        self.state = ConnectionState::Buffering;
        self.buffered_diffs.clear();
        self.prev_u = 0;
        self.book.clear();

        MarketEvent::StateChange {
            symbol: self.symbol,
            old_state,
            new_state: ConnectionState::Buffering,
            epoch: self.epoch,
        }
    }

    /// Ingest an incoming diff event.
    ///
    /// If in `Buffering` state, adds to buffer.
    /// If in `Live` state, validates sequence `U == prev_u + 1` and applies to the book.
    ///
    /// # Errors
    /// Returns `ConnectorError::SequenceGap` or `ConnectorError::Book` on failure and marks state `Invalid`.
    #[allow(clippy::arithmetic_side_effects)]
    pub fn on_diff(&mut self, event: DiffEvent) -> Result<Option<MarketEvent>, ConnectorError> {
        match self.state {
            ConnectionState::Disconnected => Ok(None),
            ConnectionState::Buffering | ConnectionState::Syncing => {
                self.buffered_diffs.push(event);
                Ok(None)
            }
            ConnectionState::Live => {
                // Stale/duplicate detection: drop events already covered
                if event.final_update_id <= self.prev_u {
                    self.stale_count = self.stale_count.saturating_add(1);
                    return Ok(None);
                }

                // Strict sequence check: U must be prev_u + 1
                if event.first_update_id != self.prev_u + 1 {
                    self.gap_count = self.gap_count.saturating_add(1);
                    self.mark_invalid();
                    return Err(ConnectorError::SequenceGap {
                        expected: self.prev_u + 1,
                        actual: event.first_update_id,
                    });
                }

                // Apply delta
                let apply_result =
                    self.book
                        .apply_delta(&event.bids, &event.asks)
                        .map_err(|e| {
                            self.mark_invalid();
                            ConnectorError::Book(e)
                        })?;

                if matches!(apply_result, ApplyResult::Crossed) || self.book.is_crossed() {
                    self.mark_invalid();
                    return Err(ConnectorError::Book(BookError::Crossed));
                }

                self.prev_u = event.final_update_id;
                let seq = self.seq;
                self.seq = self.seq.next().map_err(ConnectorError::Core)?;

                let timing = Timing {
                    exchange_ts: event.exchange_ts,
                    recv_ts: event.recv_ts,
                    publish_ts: sb_core::SystemClock::new().wall_ns(),
                };

                Ok(Some(MarketEvent::Delta {
                    symbol: self.symbol,
                    epoch: self.epoch,
                    seq,
                    timing,
                    bids: event.bids,
                    asks: event.asks,
                }))
            }
            ConnectionState::Invalid => Ok(None),
        }
    }

    /// Process a REST snapshot and bridge it to buffered diff updates.
    ///
    /// # Errors
    /// Returns `ConnectorError::SnapshotTooOld` or `ConnectorError::SnapshotCannotBridge` if the snapshot
    /// cannot synchronize with buffered events.
    #[allow(clippy::arithmetic_side_effects)]
    pub fn apply_snapshot(&mut self, snapshot: RawSnapshot) -> Result<MarketEvent, ConnectorError> {
        self.state = ConnectionState::Syncing;

        let l = snapshot.last_update_id;

        // Discard buffered events where u <= L
        self.buffered_diffs.retain(|e| e.final_update_id > l);

        // If no events remaining, we cannot bridge
        if self.buffered_diffs.is_empty() {
            self.mark_invalid();
            return Err(ConnectorError::SnapshotCannotBridge {
                last_update_id: l,
                first_u: 0,
            });
        }

        // Verify first event satisfies U <= L + 1 <= u (equivalent to U <= L + 1 and L < u)
        let first_u = self.buffered_diffs[0].first_update_id;
        let final_u = self.buffered_diffs[0].final_update_id;
        if !(first_u <= l + 1 && l < final_u) {
            self.mark_invalid();
            return Err(ConnectorError::SnapshotCannotBridge {
                last_update_id: l,
                first_u,
            });
        }

        // Apply snapshot to book
        self.book.clear();
        self.book
            .apply_snapshot(&snapshot.bids, &snapshot.asks)
            .map_err(|e| {
                self.mark_invalid();
                ConnectorError::Book(e)
            })?;

        self.prev_u = l;

        // Apply remaining buffered events
        let diffs = std::mem::take(&mut self.buffered_diffs);
        for event in diffs {
            if self.prev_u != l && event.first_update_id != self.prev_u + 1 {
                self.mark_invalid();
                return Err(ConnectorError::SequenceGap {
                    expected: self.prev_u + 1,
                    actual: event.first_update_id,
                });
            }

            let apply_res = self
                .book
                .apply_delta(&event.bids, &event.asks)
                .map_err(|e| {
                    self.mark_invalid();
                    ConnectorError::Book(e)
                })?;

            if matches!(apply_res, ApplyResult::Crossed) || self.book.is_crossed() {
                self.mark_invalid();
                return Err(ConnectorError::Book(BookError::Crossed));
            }

            self.prev_u = event.final_update_id;
        }

        // Enter Live state
        self.state = ConnectionState::Live;
        self.seq = Seq::new(1);

        // Collect full snapshot from book to emit to consumers
        let mut bids = vec![Level::new(Price::ZERO, Qty::ZERO); 100];
        let bid_count = self.book.levels(BookSide::Bid, 100, &mut bids);
        bids.truncate(bid_count);

        let mut asks = vec![Level::new(Price::ZERO, Qty::ZERO); 100];
        let ask_count = self.book.levels(BookSide::Ask, 100, &mut asks);
        asks.truncate(ask_count);

        let timing = Timing {
            exchange_ts: snapshot.exchange_ts,
            recv_ts: snapshot.recv_ts,
            publish_ts: sb_core::SystemClock::new().wall_ns(),
        };

        let seq = self.seq;
        self.seq = self.seq.next().map_err(ConnectorError::Core)?;

        Ok(MarketEvent::Snapshot {
            symbol: self.symbol,
            epoch: self.epoch,
            seq,
            timing,
            bids,
            asks,
        })
    }

    /// Mark synchronizer state Invalid, increment epoch, and clear internal book.
    pub fn mark_invalid(&mut self) {
        self.state = ConnectionState::Invalid;
        self.epoch = self.epoch.increment();
        self.seq = Seq::new(1);
        self.buffered_diffs.clear();
        self.prev_u = 0;
        self.resync_count = self.resync_count.saturating_add(1);
        self.book.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sb_core::TsNs;

    fn btc_spec() -> InstrumentSpec {
        InstrumentSpec::new(2, 5, 10, 100, 0).unwrap()
    }

    fn test_config() -> BookConfig {
        BookConfig::new(4096, 400, 50).unwrap()
    }

    #[test]
    fn sync_lifecycle_success() {
        let spec = btc_spec();
        let config = test_config();
        let mut sync = OrderBookSynchronizer::new(SymbolId::new(1), spec, config).unwrap();

        assert_eq!(sync.state(), ConnectionState::Disconnected);
        let _ = sync.start_buffering();
        assert_eq!(sync.state(), ConnectionState::Buffering);

        let recv_ts = TsNs::from_nanos(100);
        let ex_ts = TsNs::from_nanos(90);

        // Buffer diff with U=100, u=105
        let diff1 = DiffEvent {
            symbol: SymbolId::new(1),
            first_update_id: 100,
            final_update_id: 105,
            bids: vec![Level::new(
                Price::from_units(5000000).unwrap(),
                Qty::from_units(100000).unwrap(),
            )],
            asks: vec![Level::new(
                Price::from_units(5001000).unwrap(),
                Qty::from_units(200000).unwrap(),
            )],
            exchange_ts: ex_ts,
            recv_ts,
        };
        assert!(sync.on_diff(diff1).unwrap().is_none());

        // Snapshot with lastUpdateId = 102
        let snapshot = RawSnapshot {
            last_update_id: 102,
            bids: vec![Level::new(
                Price::from_units(5000000).unwrap(),
                Qty::from_units(150000).unwrap(),
            )],
            asks: vec![Level::new(
                Price::from_units(5001000).unwrap(),
                Qty::from_units(250000).unwrap(),
            )],
            exchange_ts: ex_ts,
            recv_ts,
        };

        let snap_event = sync.apply_snapshot(snapshot).unwrap();
        assert!(matches!(snap_event, MarketEvent::Snapshot { .. }));
        assert_eq!(sync.state(), ConnectionState::Live);

        // Live diff: U=106, u=108 (prev_u was 105, 106 == 105 + 1)
        let live_diff = DiffEvent {
            symbol: SymbolId::new(1),
            first_update_id: 106,
            final_update_id: 108,
            bids: vec![Level::new(
                Price::from_units(5000000).unwrap(),
                Qty::from_units(180000).unwrap(),
            )],
            asks: vec![],
            exchange_ts: ex_ts,
            recv_ts,
        };

        let delta_event = sync.on_diff(live_diff).unwrap();
        assert!(matches!(delta_event, Some(MarketEvent::Delta { .. })));
        assert_eq!(sync.state(), ConnectionState::Live);
    }

    #[test]
    fn sync_gap_detection_marks_invalid() {
        let spec = btc_spec();
        let config = test_config();
        let mut sync = OrderBookSynchronizer::new(SymbolId::new(1), spec, config).unwrap();
        let _ = sync.start_buffering();

        let recv_ts = TsNs::from_nanos(100);
        let ex_ts = TsNs::from_nanos(90);

        let diff1 = DiffEvent {
            symbol: SymbolId::new(1),
            first_update_id: 100,
            final_update_id: 105,
            bids: vec![Level::new(
                Price::from_units(5000000).unwrap(),
                Qty::from_units(100000).unwrap(),
            )],
            asks: vec![Level::new(
                Price::from_units(5001000).unwrap(),
                Qty::from_units(200000).unwrap(),
            )],
            exchange_ts: ex_ts,
            recv_ts,
        };
        let _ = sync.on_diff(diff1);

        let snapshot = RawSnapshot {
            last_update_id: 102,
            bids: vec![Level::new(
                Price::from_units(5000000).unwrap(),
                Qty::from_units(150000).unwrap(),
            )],
            asks: vec![Level::new(
                Price::from_units(5001000).unwrap(),
                Qty::from_units(250000).unwrap(),
            )],
            exchange_ts: ex_ts,
            recv_ts,
        };
        let _ = sync.apply_snapshot(snapshot);
        assert_eq!(sync.state(), ConnectionState::Live);

        // Injected gap: expected U=106, but got U=107
        let gapped_diff = DiffEvent {
            symbol: SymbolId::new(1),
            first_update_id: 107,
            final_update_id: 110,
            bids: vec![],
            asks: vec![],
            exchange_ts: ex_ts,
            recv_ts,
        };

        let res = sync.on_diff(gapped_diff);
        assert!(res.is_err());
        assert_eq!(sync.state(), ConnectionState::Invalid);
        assert_eq!(sync.epoch().id(), 2);
    }
}
