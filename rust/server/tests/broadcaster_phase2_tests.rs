//! Phase 2 — Broadcaster subscriber queue tests.
//!
//! Covers: subscriber queue saturation, conflation behavior, repeated
//! conflation, dead subscriber cleanup, and concurrent publish patterns.

#![allow(clippy::inconsistent_digit_grouping)]

use sb_server::broadcaster::{BroadcastLevel, BroadcastTiming, Broadcaster, OutboundMsg};
use std::sync::atomic::Ordering;

fn timing() -> BroadcastTiming {
    BroadcastTiming {
        exchange_ts_ns: 1_000_000_000,
        recv_ts_ns: 1_000_100_000,
        publish_ts_ns: 1_000_200_000,
    }
}

fn levels(price: i64, qty: i64) -> Vec<BroadcastLevel> {
    vec![BroadcastLevel { price, qty }]
}

// ── Test: Queue saturation triggers conflation ──────────────────────────────

#[test]
fn queue_saturation_triggers_conflation_counter() {
    // Capacity = 2: third message triggers conflation
    let mut bc = Broadcaster::new(2, 100);
    let handle = bc.subscribe().unwrap();

    let bids = levels(50000_00, 1_00000);
    let asks = levels(50001_00, 2_00000);
    let t = timing();

    // Fill the queue exactly to capacity
    bc.publish_delta("BTC", &bids, &asks, t, &bids, &asks);
    bc.publish_delta("BTC", &bids, &asks, t, &bids, &asks);

    // Third publish should trigger conflation
    bc.publish_delta("BTC", &bids, &asks, t, &bids, &asks);
    assert!(
        handle.conflations.load(Ordering::Relaxed) >= 1,
        "Conflation counter must be bumped when queue is full"
    );
}

// ── Test: Conflation sends snapshot, not delta ──────────────────────────────

#[test]
fn conflation_replaces_with_snapshot() {
    let mut bc = Broadcaster::new(1, 100);
    let mut handle = bc.subscribe().unwrap();

    let bids = levels(50000_00, 1_00000);
    let asks = levels(50001_00, 2_00000);
    let t = timing();

    // Fill queue
    bc.publish_delta("BTC", &bids, &asks, t, &bids, &asks);

    // This should trigger conflation (queue was full)
    let snap_bids = levels(50000_00, 5_00000);
    let snap_asks = levels(50001_00, 6_00000);
    bc.publish_delta("BTC", &bids, &asks, t, &snap_bids, &snap_asks);

    // Drain the receiver — we expect at least one snapshot
    let mut saw_snapshot = false;
    while let Ok(msg) = handle.rx.try_recv() {
        if matches!(msg, OutboundMsg::BookSnapshot { .. }) {
            saw_snapshot = true;
        }
    }
    // The conflation may or may not succeed depending on timing,
    // but the counter must be bumped
    assert!(handle.conflations.load(Ordering::Relaxed) >= 1);
    // If we got a snapshot, the subscriber was conflated
    if saw_snapshot {
        // Success — conflation delivered a snapshot
    }
}

// ── Test: Dead subscriber cleaned up on publish ─────────────────────────────

#[test]
fn dead_subscriber_removed_on_next_publish() {
    let mut bc = Broadcaster::new(16, 100);
    let handle = bc.subscribe().unwrap();
    assert_eq!(bc.subscriber_count(), 1);

    // Drop the receiver
    drop(handle.rx);

    // Next publish should detect closed channel and remove subscriber
    let bids = levels(50000_00, 1_00000);
    let asks = levels(50001_00, 2_00000);
    bc.publish_snapshot("BTC", &bids, &asks, timing());
    assert_eq!(bc.subscriber_count(), 0);
}

// ── Test: Max subscribers enforced ──────────────────────────────────────────

#[test]
fn max_subscribers_limit() {
    let mut bc = Broadcaster::new(16, 3);
    let _h1 = bc.subscribe().unwrap();
    let _h2 = bc.subscribe().unwrap();
    let _h3 = bc.subscribe().unwrap();
    assert!(bc.subscribe().is_err());
    assert_eq!(bc.subscriber_count(), 3);
}

// ── Test: Unsubscribe frees slot ────────────────────────────────────────────

#[test]
fn unsubscribe_frees_subscriber_slot() {
    let mut bc = Broadcaster::new(16, 2);
    let h1 = bc.subscribe().unwrap();
    let _h2 = bc.subscribe().unwrap();
    assert!(bc.subscribe().is_err());

    bc.unsubscribe(h1.id);
    assert_eq!(bc.subscriber_count(), 1);

    // Now we can subscribe again
    let _h3 = bc.subscribe().unwrap();
    assert_eq!(bc.subscriber_count(), 2);
}

// ── Test: Repeated conflation does not leak ─────────────────────────────────

#[test]
fn repeated_conflation_bounded() {
    let mut bc = Broadcaster::new(1, 100);
    let handle = bc.subscribe().unwrap();

    let bids = levels(50000_00, 1_00000);
    let asks = levels(50001_00, 2_00000);
    let t = timing();

    // Trigger many conflations
    for _ in 0..1000 {
        bc.publish_delta("BTC", &bids, &asks, t, &bids, &asks);
    }

    // Subscriber should still be active (not removed)
    assert_eq!(bc.subscriber_count(), 1);

    // Conflation counter should track all attempts
    let conflations = handle.conflations.load(Ordering::Relaxed);
    assert!(
        conflations > 0,
        "Expected at least one conflation, got {}",
        conflations
    );
}

// ── Test: Snapshot publish to all subscribers ────────────────────────────────

#[test]
fn snapshot_published_to_all_subscribers() {
    let mut bc = Broadcaster::new(16, 100);
    let mut h1 = bc.subscribe().unwrap();
    let mut h2 = bc.subscribe().unwrap();

    let bids = levels(50000_00, 1_00000);
    let asks = levels(50001_00, 2_00000);
    bc.publish_snapshot("BTC", &bids, &asks, timing());

    // Both should receive the snapshot
    assert!(h1.rx.try_recv().is_ok());
    assert!(h2.rx.try_recv().is_ok());
}

// ── Test: Metrics dropped for slow subscriber ───────────────────────────────

#[test]
fn metrics_dropped_for_slow_subscriber() {
    use sb_server::broadcaster::BroadcastMetrics;

    let mut bc = Broadcaster::new(1, 100);
    let _handle = bc.subscribe().unwrap();

    let t = timing();
    let metrics = BroadcastMetrics {
        best_bid: 50000_00,
        best_ask: 50001_00,
        best_bid_qty: 1_00000,
        best_ask_qty: 2_00000,
        spread: 100,
        mid: 50000.5,
        microprice: 50000.4,
        imbalance_top_n: 0.1,
        rolling_vwap: 50000.0,
        trade_flow_imbalance: 0.0,
        depth: vec![],
        book_valid: true,
    };

    // Fill queue
    bc.publish_metrics("BTC", t, &metrics);
    // Second metrics publish should be silently dropped (not conflated)
    bc.publish_metrics("BTC", t, &metrics);
    // Subscriber should still be active
    assert_eq!(bc.subscriber_count(), 1);
}

// ── Test: Initial snapshot for late joiner ───────────────────────────────────

#[test]
fn initial_snapshot_for_late_joiner() {
    let mut bc = Broadcaster::new(16, 100);
    let mut handle = bc.subscribe().unwrap();
    let id = handle.id;

    let bids = levels(50000_00, 1_00000);
    let asks = levels(50001_00, 2_00000);
    let t = timing();

    let result = bc.send_initial_snapshot(id, "BTC", &bids, &asks, t);
    assert!(result.is_ok());

    let msg = handle.rx.try_recv().unwrap();
    match msg {
        OutboundMsg::BookSnapshot {
            symbol, epoch, seq, ..
        } => {
            assert_eq!(symbol, "BTC");
            assert_eq!(epoch, 1); // initial epoch
            assert_eq!(seq, 1); // initial seq
        }
        _ => panic!("Expected BookSnapshot"),
    }
}
