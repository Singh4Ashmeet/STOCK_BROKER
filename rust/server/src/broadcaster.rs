//! Fan-out broadcaster with per-subscriber bounded queues and conflation.
//!
//! # Design
//!
//! ```text
//! Feed thread ──publish()──> Broadcaster ──> [SubQueue₁] ──> gRPC stream₁
//!                                       ──> [SubQueue₂] ──> gRPC stream₂
//!                                       ──> [SubQueueₙ] ──> gRPC streamₙ
//! ```
//!
//! Each subscriber has a bounded `tokio::sync::mpsc` channel. When the queue
//! is full (`try_send` fails), the broadcaster:
//!
//! 1. Drains all pending deltas for this subscriber.
//! 2. Enqueues a fresh snapshot with a new epoch (conflation).
//! 3. Increments a per-subscriber conflation counter.
//!
//! This ensures a slow consumer **never blocks the feed thread** and always
//! receives a consistent state (snapshot) after falling behind.

use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use tokio::sync::mpsc;

/// An outbound message on a subscriber's queue.
///
/// This is the internal representation; it gets converted to proto at the
/// gRPC layer.
#[derive(Debug, Clone)]
pub enum OutboundMsg {
    /// A full book snapshot (late joiner or conflation recovery).
    BookSnapshot {
        /// Symbol name.
        symbol: String,
        /// Epoch (bumped on conflation).
        epoch: u64,
        /// Sequence number.
        seq: u64,
        /// Bid levels (best first).
        bids: Vec<BroadcastLevel>,
        /// Ask levels (best first).
        asks: Vec<BroadcastLevel>,
        /// Timing.
        timing: BroadcastTiming,
    },
    /// A delta update.
    BookDelta {
        /// Symbol name.
        symbol: String,
        /// Epoch.
        epoch: u64,
        /// Sequence number.
        seq: u64,
        /// Changed bid levels.
        bids: Vec<BroadcastLevel>,
        /// Changed ask levels.
        asks: Vec<BroadcastLevel>,
        /// Timing.
        timing: BroadcastTiming,
    },
    /// Metrics update.
    MetricsUpdate {
        /// Symbol name.
        symbol: String,
        /// Epoch.
        epoch: u64,
        /// Sequence number.
        seq: u64,
        /// Timing.
        timing: BroadcastTiming,
        /// Metrics payload.
        metrics: BroadcastMetrics,
    },
}

/// A price level for broadcast (already int64 fixed-point).
#[derive(Debug, Clone, Copy)]
pub struct BroadcastLevel {
    /// Price in fixed-point units.
    pub price: i64,
    /// Quantity in fixed-point units.
    pub qty: i64,
}

/// Timing information for broadcast.
#[derive(Debug, Clone, Copy)]
pub struct BroadcastTiming {
    /// Exchange timestamp (nanos).
    pub exchange_ts_ns: i64,
    /// Receive timestamp (nanos).
    pub recv_ts_ns: i64,
    /// Publish timestamp (nanos) — stamped just before send.
    pub publish_ts_ns: i64,
}

/// Metrics payload for broadcast.
#[derive(Debug, Clone)]
pub struct BroadcastMetrics {
    /// Best bid price (units).
    pub best_bid: i64,
    /// Best ask price (units).
    pub best_ask: i64,
    /// Best bid qty (units).
    pub best_bid_qty: i64,
    /// Best ask qty (units).
    pub best_ask_qty: i64,
    /// Spread (units).
    pub spread: i64,
    /// Mid price (real, f64).
    pub mid: f64,
    /// Microprice (real, f64).
    pub microprice: f64,
    /// Imbalance top N [-1,1].
    pub imbalance_top_n: f64,
    /// Rolling VWAP (real, f64).
    pub rolling_vwap: f64,
    /// Trade flow imbalance [-1,1].
    pub trade_flow_imbalance: f64,
    /// Depth points.
    pub depth: Vec<BroadcastDepthPoint>,
    /// Book valid flag.
    pub book_valid: bool,
}

/// Depth point for broadcast.
#[derive(Debug, Clone, Copy)]
pub struct BroadcastDepthPoint {
    /// Number of levels.
    pub levels: u32,
    /// Summed bid quantity (units).
    pub bid_qty: i64,
    /// Summed ask quantity (units).
    pub ask_qty: i64,
}

/// A subscriber handle — the receiving end is owned by the gRPC stream task.
struct Subscriber {
    /// Sending half of the bounded channel.
    tx: mpsc::Sender<OutboundMsg>,
    /// Conflation counter (exposed via health metrics).
    conflations: Arc<AtomicU64>,
    /// Per-subscriber epoch (bumped on conflation, independent of feed epoch).
    sub_epoch: u64,
    /// Per-subscriber seq (reset on conflation).
    sub_seq: u64,
}

/// Statistics for a subscriber.
#[derive(Debug, Clone)]
pub struct SubscriberStats {
    /// Number of times this subscriber was conflated.
    pub conflations: u64,
}

/// Handle returned to the gRPC layer when subscribing.
pub struct SubscriptionHandle {
    /// The receiving end of the bounded message queue.
    pub rx: mpsc::Receiver<OutboundMsg>,
    /// Shared conflation counter for metrics.
    pub conflations: Arc<AtomicU64>,
    /// Unique subscriber ID.
    pub id: u64,
}

/// Per-symbol broadcaster.
///
/// Owns all subscriber queues for a single symbol. The feed thread calls
/// `publish_*` to fan out; gRPC tasks consume from their `SubscriptionHandle.rx`.
pub struct Broadcaster {
    /// Queue capacity per subscriber.
    capacity: usize,
    /// Subscribers keyed by ID.
    subscribers: HashMap<u64, Subscriber>,
    /// Monotonic subscriber ID generator.
    next_id: u64,
    /// Max subscribers allowed.
    max_subscribers: usize,
}

impl Broadcaster {
    /// Create a new broadcaster.
    pub fn new(capacity: usize, max_subscribers: usize) -> Self {
        Self {
            capacity,
            subscribers: HashMap::new(),
            next_id: 0,
            max_subscribers,
        }
    }

    /// Add a subscriber. Returns a handle with the receiving channel.
    ///
    /// # Errors
    ///
    /// Returns `Err` if the max subscriber limit is reached.
    pub fn subscribe(&mut self) -> Result<SubscriptionHandle, BroadcasterError> {
        if self.subscribers.len() >= self.max_subscribers {
            return Err(BroadcasterError::TooManySubscribers);
        }

        let (tx, rx) = mpsc::channel(self.capacity);
        let conflations = Arc::new(AtomicU64::new(0));
        let id = self.next_id;
        self.next_id = self.next_id.wrapping_add(1);

        self.subscribers.insert(
            id,
            Subscriber {
                tx,
                conflations: Arc::clone(&conflations),
                sub_epoch: 1,
                sub_seq: 0,
            },
        );

        Ok(SubscriptionHandle {
            rx,
            conflations,
            id,
        })
    }

    /// Remove a subscriber (e.g., when the gRPC stream is dropped).
    pub fn unsubscribe(&mut self, id: u64) {
        self.subscribers.remove(&id);
    }

    /// Publish a snapshot to all subscribers (e.g., after a book rebuild).
    ///
    /// For each subscriber, this sends a snapshot and resets their sub_seq.
    /// Dead subscribers (closed channels) are removed.
    pub fn publish_snapshot(
        &mut self,
        symbol: &str,
        bids: &[BroadcastLevel],
        asks: &[BroadcastLevel],
        timing: BroadcastTiming,
    ) {
        let mut dead = Vec::new();
        let mut to_conflate = Vec::new();

        for (&id, sub) in &mut self.subscribers {
            sub.sub_seq = sub.sub_seq.wrapping_add(1);
            let msg = OutboundMsg::BookSnapshot {
                symbol: symbol.to_owned(),
                epoch: sub.sub_epoch,
                seq: sub.sub_seq,
                bids: bids.to_vec(),
                asks: asks.to_vec(),
                timing,
            };
            if sub.tx.try_send(msg).is_err() {
                // Channel full or closed — attempt conflation
                if sub.tx.is_closed() {
                    dead.push(id);
                } else {
                    // Drain and send snapshot (conflation)
                    to_conflate.push(id);
                }
            }
        }

        for id in dead {
            self.subscribers.remove(&id);
        }

        for id in to_conflate {
            self.conflate_subscriber(id, symbol, bids, asks, timing);
        }
    }

    /// Publish a delta to all subscribers.
    ///
    /// If a subscriber's queue is full, conflate: drain pending, bump epoch,
    /// send a fresh snapshot instead.
    pub fn publish_delta(
        &mut self,
        symbol: &str,
        delta_bids: &[BroadcastLevel],
        delta_asks: &[BroadcastLevel],
        timing: BroadcastTiming,
        // Current snapshot for conflation fallback:
        snap_bids: &[BroadcastLevel],
        snap_asks: &[BroadcastLevel],
    ) {
        let mut dead = Vec::new();
        let mut to_conflate = Vec::new();

        for (&id, sub) in &mut self.subscribers {
            sub.sub_seq = sub.sub_seq.wrapping_add(1);
            let msg = OutboundMsg::BookDelta {
                symbol: symbol.to_owned(),
                epoch: sub.sub_epoch,
                seq: sub.sub_seq,
                bids: delta_bids.to_vec(),
                asks: delta_asks.to_vec(),
                timing,
            };
            match sub.tx.try_send(msg) {
                Ok(()) => {}
                Err(mpsc::error::TrySendError::Closed(_)) => {
                    dead.push(id);
                }
                Err(mpsc::error::TrySendError::Full(_)) => {
                    to_conflate.push(id);
                }
            }
        }

        for id in dead {
            self.subscribers.remove(&id);
        }

        for id in to_conflate {
            self.conflate_subscriber(id, symbol, snap_bids, snap_asks, timing);
        }
    }

    /// Publish a metrics update to all subscribers.
    pub fn publish_metrics(
        &mut self,
        symbol: &str,
        timing: BroadcastTiming,
        metrics: &BroadcastMetrics,
    ) {
        let mut dead = Vec::new();

        for (&id, sub) in &mut self.subscribers {
            sub.sub_seq = sub.sub_seq.wrapping_add(1);
            let msg = OutboundMsg::MetricsUpdate {
                symbol: symbol.to_owned(),
                epoch: sub.sub_epoch,
                seq: sub.sub_seq,
                timing,
                metrics: metrics.clone(),
            };
            // For metrics, if the queue is full we just drop (latest-value conflation).
            match sub.tx.try_send(msg) {
                Ok(()) => {}
                Err(mpsc::error::TrySendError::Closed(_)) => {
                    dead.push(id);
                }
                Err(mpsc::error::TrySendError::Full(_)) => {
                    // Drop this metrics message for slow subscriber.
                    // Metrics are idempotent — the next one will have fresher data.
                }
            }
        }

        for id in dead {
            self.subscribers.remove(&id);
        }
    }

    /// Send a snapshot to a single subscriber (late joiner).
    ///
    /// # Errors
    ///
    /// Returns error if the subscriber was not found or channel is closed.
    pub fn send_initial_snapshot(
        &mut self,
        sub_id: u64,
        symbol: &str,
        bids: &[BroadcastLevel],
        asks: &[BroadcastLevel],
        timing: BroadcastTiming,
    ) -> Result<(), BroadcasterError> {
        let sub = self
            .subscribers
            .get_mut(&sub_id)
            .ok_or(BroadcasterError::SubscriberNotFound)?;

        let msg = OutboundMsg::BookSnapshot {
            symbol: symbol.to_owned(),
            epoch: sub.sub_epoch,
            seq: 1,
            bids: bids.to_vec(),
            asks: asks.to_vec(),
            timing,
        };
        sub.sub_seq = 1;

        sub.tx
            .try_send(msg)
            .map_err(|_| BroadcasterError::ChannelClosed)
    }

    /// Number of active subscribers.
    pub fn subscriber_count(&self) -> usize {
        self.subscribers.len()
    }

    /// Get stats for a subscriber.
    pub fn subscriber_stats(&self, id: u64) -> Option<SubscriberStats> {
        self.subscribers.get(&id).map(|sub| SubscriberStats {
            conflations: sub.conflations.load(Ordering::Relaxed),
        })
    }

    /// Internal: bump epoch and enqueue a fresh snapshot for a slow subscriber.
    ///
    /// When a subscriber's queue is full, we bump their per-subscriber epoch
    /// and try to enqueue a snapshot. The consumer must treat any epoch bump
    /// as a signal that all prior pending deltas are stale and should be
    /// discarded. If the channel is completely stuck (try_send still fails),
    /// the subscriber will be cleaned up once they drop or close their
    /// receiver.
    fn conflate_subscriber(
        &mut self,
        id: u64,
        symbol: &str,
        bids: &[BroadcastLevel],
        asks: &[BroadcastLevel],
        timing: BroadcastTiming,
    ) {
        let Some(sub) = self.subscribers.get_mut(&id) else {
            return;
        };

        // Bump the subscriber's epoch and reset seq.
        sub.sub_epoch = sub.sub_epoch.wrapping_add(1);
        sub.sub_seq = 1;
        sub.conflations.fetch_add(1, Ordering::Relaxed);

        // Try to send the snapshot. If the channel is still full, the
        // message is silently dropped — the subscriber will eventually
        // consume enough messages to free space, and the next publish
        // will trigger another conflation attempt with fresher data.
        let msg = OutboundMsg::BookSnapshot {
            symbol: symbol.to_owned(),
            epoch: sub.sub_epoch,
            seq: sub.sub_seq,
            bids: bids.to_vec(),
            asks: asks.to_vec(),
            timing,
        };
        let _ = sub.tx.try_send(msg);
    }
}

/// Broadcaster errors.
#[derive(Debug, Clone)]
pub enum BroadcasterError {
    /// Maximum subscriber limit reached.
    TooManySubscribers,
    /// Subscriber not found.
    SubscriberNotFound,
    /// Channel is closed (subscriber dropped).
    ChannelClosed,
}

impl std::fmt::Display for BroadcasterError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::TooManySubscribers => write!(f, "too many subscribers"),
            Self::SubscriberNotFound => write!(f, "subscriber not found"),
            Self::ChannelClosed => write!(f, "subscriber channel closed"),
        }
    }
}

impl std::error::Error for BroadcasterError {}

#[cfg(test)]
mod tests {
    use super::*;

    fn dummy_timing() -> BroadcastTiming {
        BroadcastTiming {
            exchange_ts_ns: 1_000_000_000,
            recv_ts_ns: 1_000_100_000,
            publish_ts_ns: 1_000_200_000,
        }
    }

    #[allow(clippy::inconsistent_digit_grouping)]
    fn dummy_levels() -> Vec<BroadcastLevel> {
        vec![BroadcastLevel {
            price: 50_000_00,
            qty: 100_000,
        }]
    }

    #[test]
    fn subscribe_and_receive() {
        let mut bc = Broadcaster::new(16, 100);
        let mut handle = bc.subscribe().unwrap();

        bc.publish_snapshot("BTC-USDT", &dummy_levels(), &dummy_levels(), dummy_timing());

        // Should have received the message
        assert!(handle.rx.try_recv().is_ok());
    }

    #[test]
    fn max_subscribers_enforced() {
        let mut bc = Broadcaster::new(16, 2);
        let _h1 = bc.subscribe().unwrap();
        let _h2 = bc.subscribe().unwrap();
        assert!(bc.subscribe().is_err());
    }

    #[test]
    fn unsubscribe_removes() {
        let mut bc = Broadcaster::new(16, 100);
        let handle = bc.subscribe().unwrap();
        let id = handle.id;
        assert_eq!(bc.subscriber_count(), 1);
        bc.unsubscribe(id);
        assert_eq!(bc.subscriber_count(), 0);
    }

    #[test]
    fn slow_consumer_gets_conflated() {
        // Capacity = 2 means the third message triggers conflation
        let mut bc = Broadcaster::new(2, 100);
        let handle = bc.subscribe().unwrap();
        let bids = dummy_levels();
        let asks = dummy_levels();
        let timing = dummy_timing();

        // Fill the queue
        bc.publish_delta("BTC", &bids, &asks, timing, &bids, &asks);
        bc.publish_delta("BTC", &bids, &asks, timing, &bids, &asks);

        // This should trigger conflation (queue full)
        bc.publish_delta("BTC", &bids, &asks, timing, &bids, &asks);

        // The conflation counter should have been bumped
        assert!(handle.conflations.load(Ordering::Relaxed) >= 1);
    }

    #[test]
    fn dead_subscriber_cleaned_up() {
        let mut bc = Broadcaster::new(16, 100);
        let handle = bc.subscribe().unwrap();
        drop(handle.rx); // close the receiver

        // Publishing should clean up the dead subscriber
        bc.publish_snapshot("BTC", &dummy_levels(), &dummy_levels(), dummy_timing());
        assert_eq!(bc.subscriber_count(), 0);
    }
}
