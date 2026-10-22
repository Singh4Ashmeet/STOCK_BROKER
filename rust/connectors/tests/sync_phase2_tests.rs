//! Phase 2 — Deterministic synchronizer tests.
//!
//! Uses synthetic messages only (no live exchange credentials).
//! Covers: normal lifecycle, missing sequence, duplicate/stale delta,
//! out-of-order, invalid snapshot, disconnect during sync, reconnect
//! with pending events, and repeated recovery.

use sb_book::BookConfig;
use sb_connectors::binance::sync::OrderBookSynchronizer;
use sb_connectors::types::{ConnectionState, DiffEvent, MarketEvent, RawSnapshot};
use sb_core::{InstrumentSpec, Level, Price, Qty, SymbolId, TsNs};

// ── Helpers ──────────────────────────────────────────────────────────────────

fn btc_spec() -> InstrumentSpec {
    // tick=10, lot=100 to match Phase 1 test specs
    InstrumentSpec::new(2, 5, 10, 100, 0).unwrap()
}

fn test_config() -> BookConfig {
    BookConfig::new(4096, 400, 50).unwrap()
}

fn ts(n: i64) -> TsNs {
    TsNs::from_nanos(n)
}

fn sym() -> SymbolId {
    SymbolId::new(1)
}

fn level(p: i64, q: i64) -> Level {
    Level::new(Price::from_units(p).unwrap(), Qty::from_units(q).unwrap())
}

fn make_diff(first_u: u64, final_u: u64, bids: Vec<Level>, asks: Vec<Level>) -> DiffEvent {
    DiffEvent {
        symbol: sym(),
        first_update_id: first_u,
        final_update_id: final_u,
        bids,
        asks,
        exchange_ts: ts(100),
        recv_ts: ts(200),
    }
}

fn make_snapshot(last_update_id: u64) -> RawSnapshot {
    RawSnapshot {
        last_update_id,
        bids: vec![level(5000000, 100000)],
        asks: vec![level(5001000, 200000)],
        exchange_ts: ts(90),
        recv_ts: ts(100),
    }
}

fn sync_to_live(sync: &mut OrderBookSynchronizer) {
    let _ = sync.start_buffering();
    let diff = make_diff(
        100,
        105,
        vec![level(5000000, 100000)],
        vec![level(5001000, 200000)],
    );
    let _ = sync.on_diff(diff);
    let snap = make_snapshot(102);
    let _ = sync.apply_snapshot(snap).unwrap();
    assert_eq!(sync.state(), ConnectionState::Live);
}

// ── Test: Normal initial snapshot and valid buffered deltas ───────────────────

#[test]
fn normal_snapshot_with_valid_buffered_deltas() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    let _ = sync.start_buffering();
    assert_eq!(sync.state(), ConnectionState::Buffering);

    // Buffer two diff events
    let diff1 = make_diff(
        100,
        105,
        vec![level(5000000, 100000)],
        vec![level(5001000, 200000)],
    );
    let diff2 = make_diff(
        106,
        110,
        vec![level(5000000, 150000)],
        vec![level(5001000, 250000)],
    );
    assert!(sync.on_diff(diff1).unwrap().is_none());
    assert!(sync.on_diff(diff2).unwrap().is_none());

    // Snapshot with lastUpdateId=102 bridges diff1 (100 <= 103 <= 105)
    let snap = make_snapshot(102);
    let event = sync.apply_snapshot(snap).unwrap();
    assert!(matches!(event, MarketEvent::Snapshot { .. }));
    assert_eq!(sync.state(), ConnectionState::Live);
    assert_eq!(sync.epoch().id(), 1);

    // Both buffered diffs should have been applied
    assert_eq!(sync.gap_count(), 0);
    assert_eq!(sync.stale_count(), 0);

    // Live diff: U=111, u=115
    let live_diff = make_diff(111, 115, vec![level(5000000, 180000)], vec![]);
    let delta = sync.on_diff(live_diff).unwrap();
    assert!(matches!(delta, Some(MarketEvent::Delta { .. })));
}

// ── Test: Missing sequence number ────────────────────────────────────────────

#[test]
fn missing_sequence_number_marks_invalid() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    sync_to_live(&mut sync);

    // prev_u should be 105 after sync_to_live
    // Expected next U = 106, inject gap: U=108
    let gapped = make_diff(108, 110, vec![], vec![]);
    let res = sync.on_diff(gapped);
    assert!(res.is_err());
    assert_eq!(sync.state(), ConnectionState::Invalid);
    assert_eq!(sync.gap_count(), 1);
    // Epoch should have been bumped by mark_invalid
    assert_eq!(sync.epoch().id(), 2);
    // Book should be cleared
    assert!(sync.book().best_bid().is_none());
}

// ── Test: Duplicate delta ────────────────────────────────────────────────────

#[test]
fn duplicate_delta_silently_dropped() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    sync_to_live(&mut sync);

    // Send valid delta U=106, u=108
    let delta1 = make_diff(106, 108, vec![level(5000000, 160000)], vec![]);
    let res1 = sync.on_diff(delta1).unwrap();
    assert!(res1.is_some());
    assert_eq!(sync.stale_count(), 0);

    // Send duplicate with same u=108 (stale: final_update_id <= prev_u)
    let dup = make_diff(106, 108, vec![level(5000000, 999900)], vec![]);
    let res2 = sync.on_diff(dup).unwrap();
    assert!(res2.is_none()); // silently dropped
    assert_eq!(sync.stale_count(), 1);
    assert_eq!(sync.state(), ConnectionState::Live); // still live, not invalid
}

// ── Test: Stale delta (older than prev_u) ────────────────────────────────────

#[test]
fn stale_delta_silently_dropped() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    sync_to_live(&mut sync);

    // prev_u = 105 after sync. Send valid delta
    let delta = make_diff(106, 110, vec![level(5000000, 200000)], vec![]);
    sync.on_diff(delta).unwrap();
    // prev_u is now 110

    // Send stale: u=103 (far behind prev_u=110)
    let stale = make_diff(100, 103, vec![level(5000000, 999900)], vec![]);
    let res = sync.on_diff(stale).unwrap();
    assert!(res.is_none());
    assert_eq!(sync.stale_count(), 1);
    assert_eq!(sync.state(), ConnectionState::Live);
}

// ── Test: Out-of-order message ───────────────────────────────────────────────

#[test]
fn out_of_order_message_detected_as_gap() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    sync_to_live(&mut sync);

    // prev_u = 105. Send U=107 (skipping U=106) - this is a gap, not stale
    // since final_update_id 107 > prev_u 105, stale check passes,
    // but first_update_id 107 != 106, so gap detected
    let oop = make_diff(107, 109, vec![], vec![]);
    let res = sync.on_diff(oop);
    assert!(res.is_err());
    assert_eq!(sync.state(), ConnectionState::Invalid);
    assert_eq!(sync.gap_count(), 1);
}

// ── Test: Invalid / failed snapshot ──────────────────────────────────────────

#[test]
fn snapshot_too_old_cannot_bridge() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    let _ = sync.start_buffering();

    // Buffer diff with U=200, u=210
    let diff = make_diff(
        200,
        210,
        vec![level(5000000, 100000)],
        vec![level(5001000, 200000)],
    );
    let _ = sync.on_diff(diff);

    // Snapshot with lastUpdateId=50 (way too old, L+1=51 < U=200)
    let old_snap = RawSnapshot {
        last_update_id: 50,
        bids: vec![level(5000000, 100000)],
        asks: vec![level(5001000, 200000)],
        exchange_ts: ts(90),
        recv_ts: ts(100),
    };
    let res = sync.apply_snapshot(old_snap);
    assert!(res.is_err());
    assert_eq!(sync.state(), ConnectionState::Invalid);
}

#[test]
fn snapshot_with_no_buffered_events_fails() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    let _ = sync.start_buffering();

    // No diffs buffered — snapshot cannot bridge
    let snap = make_snapshot(500);
    let res = sync.apply_snapshot(snap);
    assert!(res.is_err());
    assert_eq!(sync.state(), ConnectionState::Invalid);
}

#[test]
fn snapshot_with_all_stale_buffered_events_fails() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    let _ = sync.start_buffering();

    // Buffer diff with u=50
    let diff = make_diff(
        40,
        50,
        vec![level(5000000, 100000)],
        vec![level(5001000, 200000)],
    );
    let _ = sync.on_diff(diff);

    // Snapshot with lastUpdateId=100 — all buffered events have u <= 100
    let snap = RawSnapshot {
        last_update_id: 100,
        bids: vec![level(5000000, 100000)],
        asks: vec![level(5001000, 200000)],
        exchange_ts: ts(90),
        recv_ts: ts(100),
    };
    let res = sync.apply_snapshot(snap);
    assert!(res.is_err());
    assert_eq!(sync.state(), ConnectionState::Invalid);
}

// ── Test: Disconnect during synchronization ──────────────────────────────────

#[test]
fn disconnect_during_buffering_clears_state() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    let _ = sync.start_buffering();

    // Buffer some events
    let diff = make_diff(
        100,
        105,
        vec![level(5000000, 100000)],
        vec![level(5001000, 200000)],
    );
    let _ = sync.on_diff(diff);

    // Disconnect mid-buffering
    let event = sync.on_disconnect();
    assert!(matches!(
        event,
        MarketEvent::StateChange {
            new_state: ConnectionState::Disconnected,
            ..
        }
    ));
    assert_eq!(sync.state(), ConnectionState::Disconnected);
    assert!(sync.book().best_bid().is_none());

    // Epoch should be incremented
    assert_eq!(sync.epoch().id(), 2);
}

#[test]
fn disconnect_during_syncing_clears_state() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    let _ = sync.start_buffering();

    // Buffer a diff
    let diff = make_diff(
        100,
        105,
        vec![level(5000000, 100000)],
        vec![level(5001000, 200000)],
    );
    let _ = sync.on_diff(diff);

    // Snapshot applied — now in Syncing briefly, but apply_snapshot completes
    // synchronously. So we test disconnect from Live instead.
    let snap = make_snapshot(102);
    let _ = sync.apply_snapshot(snap).unwrap();
    assert_eq!(sync.state(), ConnectionState::Live);

    let event = sync.on_disconnect();
    assert_eq!(sync.state(), ConnectionState::Disconnected);
    assert!(matches!(event, MarketEvent::StateChange { .. }));
}

// ── Test: Reconnect while old events are still pending ────────────────────────

#[test]
fn reconnect_clears_old_buffered_events() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    let _ = sync.start_buffering();

    // Buffer many events
    for i in 0..50u64 {
        let diff = make_diff(
            100 + i * 5,
            104 + i * 5,
            vec![level(5000000, 100000)],
            vec![level(5001000, 200000)],
        );
        let _ = sync.on_diff(diff);
    }

    // Disconnect and re-buffer
    let _ = sync.on_disconnect();
    assert_eq!(sync.state(), ConnectionState::Disconnected);
    assert_eq!(sync.epoch().id(), 2);

    // Start new session
    let _ = sync.start_buffering();
    assert_eq!(sync.state(), ConnectionState::Buffering);

    // New events should work with new sequence space
    let new_diff = make_diff(
        500,
        510,
        vec![level(5000000, 300000)],
        vec![level(5001000, 400000)],
    );
    let _ = sync.on_diff(new_diff);

    let snap = RawSnapshot {
        last_update_id: 502,
        bids: vec![level(5000000, 300000)],
        asks: vec![level(5001000, 400000)],
        exchange_ts: ts(90),
        recv_ts: ts(100),
    };
    let event = sync.apply_snapshot(snap).unwrap();
    assert!(matches!(event, MarketEvent::Snapshot { .. }));
    assert_eq!(sync.state(), ConnectionState::Live);
}

// ── Test: Repeated recovery without unbounded growth ─────────────────────────

#[test]
fn repeated_recovery_no_unbounded_growth() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();

    for cycle in 0..100u64 {
        // Start buffering
        let _ = sync.start_buffering();

        // Buffer a diff
        let base_u = cycle * 100 + 100;
        let diff = make_diff(
            base_u,
            base_u + 5,
            vec![level(5000000, 100000)],
            vec![level(5001000, 200000)],
        );
        let _ = sync.on_diff(diff);

        // Apply snapshot
        let snap = RawSnapshot {
            last_update_id: base_u + 2,
            bids: vec![level(5000000, 100000)],
            asks: vec![level(5001000, 200000)],
            exchange_ts: ts(90),
            recv_ts: ts(100),
        };
        let event = sync.apply_snapshot(snap).unwrap();
        assert!(matches!(event, MarketEvent::Snapshot { .. }));
        assert_eq!(sync.state(), ConnectionState::Live);

        // Send a valid delta then disconnect
        let live_diff = make_diff(base_u + 6, base_u + 8, vec![level(5000000, 180000)], vec![]);
        let _ = sync.on_diff(live_diff).unwrap();

        // Disconnect
        let _ = sync.on_disconnect();
    }

    // After 100 cycles, counters should reflect work done
    assert_eq!(sync.gap_count(), 0);
    assert_eq!(sync.stale_count(), 0);
    // Each disconnect starts a new epoch
    assert!(sync.epoch().id() > 100);
}

// ── Test: Diffs received in Disconnected state are ignored ───────────────────

#[test]
fn diffs_in_disconnected_state_ignored() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    assert_eq!(sync.state(), ConnectionState::Disconnected);

    let diff = make_diff(
        100,
        105,
        vec![level(5000000, 100000)],
        vec![level(5001000, 200000)],
    );
    let result = sync.on_diff(diff).unwrap();
    assert!(result.is_none());
}

// ── Test: Diffs received in Invalid state are ignored ────────────────────────

#[test]
fn diffs_in_invalid_state_ignored() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    sync_to_live(&mut sync);

    // Force invalid
    sync.mark_invalid();
    assert_eq!(sync.state(), ConnectionState::Invalid);

    let diff = make_diff(999, 1000, vec![level(5000000, 100000)], vec![]);
    let result = sync.on_diff(diff).unwrap();
    assert!(result.is_none());
}

// ── Test: Epoch monotonically increases through state transitions ────────────

#[test]
fn epoch_monotonic_through_transitions() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    let mut max_epoch = sync.epoch().id();

    for _ in 0..10 {
        sync_to_live(&mut sync);
        assert!(sync.epoch().id() >= max_epoch);
        max_epoch = sync.epoch().id();

        // Trigger gap → Invalid
        let bad = make_diff(999, 1000, vec![], vec![]);
        let _ = sync.on_diff(bad);
        assert_eq!(sync.state(), ConnectionState::Invalid);
        assert!(sync.epoch().id() > max_epoch);
        max_epoch = sync.epoch().id();

        // Disconnect for clean restart
        let _ = sync.on_disconnect();
        assert!(sync.epoch().id() >= max_epoch);
        max_epoch = sync.epoch().id();
    }
}

// ── Test: Seq resets after each new Live state ───────────────────────────────

#[test]
fn seq_resets_on_new_live_state() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();

    for _ in 0..5 {
        let _ = sync.start_buffering();
        let diff = make_diff(
            100,
            105,
            vec![level(5000000, 100000)],
            vec![level(5001000, 200000)],
        );
        let _ = sync.on_diff(diff);
        let snap = make_snapshot(102);
        let _ = sync.apply_snapshot(snap).unwrap();

        // Seq should start at 1 after entering Live (then incremented to 2 by apply_snapshot)
        assert_eq!(sync.seq().id(), 2);

        // Send a live delta
        let delta = make_diff(106, 108, vec![level(5000000, 160000)], vec![]);
        let _ = sync.on_diff(delta).unwrap();
        assert_eq!(sync.seq().id(), 3);

        // Disconnect
        let _ = sync.on_disconnect();
    }
}

// ── Test: Crossed book during sync marks invalid ─────────────────────────────

#[test]
fn crossed_book_during_sync_marks_invalid() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    let _ = sync.start_buffering();

    // Buffer a diff that would create a crossed book
    // bid > ask after applying both snapshot and this delta
    let diff = make_diff(
        100,
        105,
        vec![level(6000000, 100000)], // bid at 60000.00
        vec![level(5000000, 200000)], // ask at 50000.00 (crossed!)
    );
    let _ = sync.on_diff(diff);

    // Snapshot with non-crossed levels
    let snap = RawSnapshot {
        last_update_id: 102,
        bids: vec![level(5000000, 100000)],
        asks: vec![level(5001000, 200000)],
        exchange_ts: ts(90),
        recv_ts: ts(100),
    };

    // The snapshot itself is fine, but applying the buffered diff creates a cross
    let res = sync.apply_snapshot(snap);
    assert!(res.is_err());
    assert_eq!(sync.state(), ConnectionState::Invalid);
}

// ── Test: Snapshot bridging boundary condition ────────────────────────────────

#[test]
fn snapshot_bridges_exactly_at_boundary() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    let _ = sync.start_buffering();

    // Buffer diff with U=100, u=100 (single update)
    let diff = make_diff(
        100,
        100,
        vec![level(5000000, 100000)],
        vec![level(5001000, 200000)],
    );
    let _ = sync.on_diff(diff);

    // Snapshot with lastUpdateId = 99
    // Bridging: U <= L+1 means 100 <= 100 ✓
    // L < u means 99 < 100 ✓
    let snap = RawSnapshot {
        last_update_id: 99,
        bids: vec![level(5000000, 100000)],
        asks: vec![level(5001000, 200000)],
        exchange_ts: ts(90),
        recv_ts: ts(100),
    };
    let event = sync.apply_snapshot(snap).unwrap();
    assert!(matches!(event, MarketEvent::Snapshot { .. }));
    assert_eq!(sync.state(), ConnectionState::Live);
}

#[test]
fn snapshot_lastupdate_equals_first_event_final_update_drops_all() {
    let mut sync = OrderBookSynchronizer::new(sym(), btc_spec(), test_config()).unwrap();
    let _ = sync.start_buffering();

    // Buffer diff with U=100, u=105
    let diff = make_diff(
        100,
        105,
        vec![level(5000000, 100000)],
        vec![level(5001000, 200000)],
    );
    let _ = sync.on_diff(diff);

    // Snapshot with lastUpdateId = 105 — all buffered events are dropped (u <= L)
    let snap = RawSnapshot {
        last_update_id: 105,
        bids: vec![level(5000000, 100000)],
        asks: vec![level(5001000, 200000)],
        exchange_ts: ts(90),
        recv_ts: ts(100),
    };
    let res = sync.apply_snapshot(snap);
    // No buffered events remain — cannot bridge
    assert!(res.is_err());
    assert_eq!(sync.state(), ConnectionState::Invalid);
}
