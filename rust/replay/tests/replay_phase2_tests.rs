//! Phase 2 — Replay gap signaling and overflow tests.
//!
//! Covers: writer overflow with gap markers, replay gap detection,
//! incomplete recording flagging, and deterministic replay with gaps.

use tempfile::TempDir;

use sb_book::BookConfig;
use sb_core::{InstrumentSpec, Level, Price, Qty, TsNs};
use sb_metrics::engine::MetricsConfig;
use sb_replay::{EventLogWriter, ReplayDriver, WriterConfig, make_delta_record};
use std::sync::atomic::Ordering;

fn btc_spec() -> InstrumentSpec {
    InstrumentSpec::new(2, 5, 10, 100, 0).unwrap()
}

fn book_config() -> BookConfig {
    BookConfig::new(2048, 100, 50).unwrap()
}

fn metrics_config() -> MetricsConfig {
    MetricsConfig {
        depth_levels: 10,
        vwap_window_ns: 3_600_000_000_000,
        trade_flow_window_ns: 3_600_000_000_000,
        vwap_capacity: 4096,
        trade_flow_capacity: 4096,
    }
}

fn level(p: i64, q: i64) -> Level {
    Level::new(Price::from_units(p).unwrap(), Qty::from_units(q).unwrap())
}

fn ts(n: i64) -> TsNs {
    TsNs::from_nanos(n)
}

// ── Test: Gap marker in replay flags incomplete ─────────────────────────────

#[test]
fn gap_marker_in_replay_flags_incomplete() {
    let temp_dir = TempDir::new().unwrap();
    let spec = btc_spec();

    let writer_config = WriterConfig {
        base_dir: temp_dir.path().to_path_buf(),
        venue: "BINANCE".to_string(),
        channel_capacity: 65_536,
        drop_on_full: false,
        zstd_level: 1,
        writer_version: "0.1.0".to_string(),
    };

    let writer = EventLogWriter::start(writer_config);
    let handle = writer.handle();

    // Base timestamp: 2026-10-09 12:00:00 UTC
    let base_ns = 1_791_547_200_000_000_000i64;

    // Write a snapshot
    let bids = vec![level(6000000, 500000)];
    let asks = vec![level(6001000, 400000)];
    handle
        .log_snapshot("BTC-USDT", &spec, ts(base_ns), ts(base_ns), &bids, &asks)
        .unwrap();

    // Write a few deltas
    for i in 1..=5i64 {
        let t = ts(base_ns + i * 1_000_000_000);
        let d_bids = vec![level(6000000, 500000 + i * 10000)];
        let d_asks = vec![level(6001000, 400000 + i * 5000)];
        handle
            .log_delta("BTC-USDT", &spec, t, t, &d_bids, &d_asks)
            .unwrap();
    }

    // Write a gap marker (simulating dropped records)
    let gap_ts = ts(base_ns + 6_000_000_000);
    handle
        .log_gap("BTC-USDT", &spec, gap_ts, gap_ts, 6, 10, "channel_overflow")
        .unwrap();

    // Write more deltas after the gap
    for i in 7..=10i64 {
        let t = ts(base_ns + i * 1_000_000_000);
        let d_bids = vec![level(6000000, 600000)];
        let d_asks = vec![level(6001000, 500000)];
        handle
            .log_delta("BTC-USDT", &spec, t, t, &d_bids, &d_asks)
            .unwrap();
    }

    writer.shutdown().unwrap();

    // Replay and verify incomplete flag
    let mut driver = ReplayDriver::new(spec, book_config(), metrics_config()).unwrap();
    let summary = driver
        .replay_day(temp_dir.path(), "BINANCE", "BTC-USDT", "2026-10-09")
        .unwrap();

    assert!(summary.total_records > 0);
    assert_eq!(summary.snapshots_count, 1);
    assert_eq!(summary.gaps_detected, 1, "Gap marker must be detected");
    assert!(
        summary.incomplete,
        "Summary must flag incomplete when gaps exist"
    );
}

// ── Test: No gap markers means complete replay ──────────────────────────────

#[test]
fn no_gap_markers_means_complete_replay() {
    let temp_dir = TempDir::new().unwrap();
    let spec = btc_spec();

    let writer_config = WriterConfig {
        base_dir: temp_dir.path().to_path_buf(),
        venue: "BINANCE".to_string(),
        channel_capacity: 65_536,
        drop_on_full: false,
        zstd_level: 1,
        writer_version: "0.1.0".to_string(),
    };

    let writer = EventLogWriter::start(writer_config);
    let handle = writer.handle();

    let base_ns = 1_791_547_200_000_000_000i64;

    let bids = vec![level(6000000, 500000)];
    let asks = vec![level(6001000, 400000)];
    handle
        .log_snapshot("BTC-USDT", &spec, ts(base_ns), ts(base_ns), &bids, &asks)
        .unwrap();

    for i in 1..=10i64 {
        let t = ts(base_ns + i * 1_000_000_000);
        handle
            .log_delta("BTC-USDT", &spec, t, t, &bids, &asks)
            .unwrap();
    }

    writer.shutdown().unwrap();

    let mut driver = ReplayDriver::new(spec, book_config(), metrics_config()).unwrap();
    let summary = driver
        .replay_day(temp_dir.path(), "BINANCE", "BTC-USDT", "2026-10-09")
        .unwrap();

    assert_eq!(summary.gaps_detected, 0);
    assert!(!summary.incomplete, "No gaps = complete replay");
}

// ── Test: Writer drops records when channel is full ─────────────────────────

#[test]
fn writer_drops_records_when_channel_full() {
    let temp_dir = TempDir::new().unwrap();
    let spec = btc_spec();

    // Very small channel capacity
    let writer_config = WriterConfig {
        base_dir: temp_dir.path().to_path_buf(),
        venue: "BINANCE".to_string(),
        channel_capacity: 4, // tiny
        drop_on_full: true,  // live mode: drop on full
        zstd_level: 1,
        writer_version: "0.1.0".to_string(),
    };

    let writer = EventLogWriter::start(writer_config);
    let handle = writer.handle();

    let base_ns = 1_791_547_200_000_000_000i64;

    // Flood the channel much faster than the writer can process
    for i in 0..10_000i64 {
        let t = ts(base_ns + i * 100_000);
        let bids = vec![level(6000000, 500000)];
        let asks = vec![level(6001000, 400000)];
        let rec = make_delta_record(t, t, &bids, &asks);
        let _ = handle.log("BTC-USDT", &spec, rec);
    }

    writer.shutdown().unwrap();

    // With a 4-element channel, many records should have been dropped
    let stats = handle.stats();
    let stat_dropped = stats.dropped_count.load(Ordering::Relaxed);
    assert!(
        stat_dropped > 0,
        "Expected dropped records with tiny channel, got 0. Total attempted: 10000"
    );
}

// ── Test: Replay with multiple gap markers counts all ────────────────────────

#[test]
fn replay_counts_multiple_gap_markers() {
    let temp_dir = TempDir::new().unwrap();
    let spec = btc_spec();

    let writer_config = WriterConfig {
        base_dir: temp_dir.path().to_path_buf(),
        venue: "BINANCE".to_string(),
        channel_capacity: 65_536,
        drop_on_full: false,
        zstd_level: 1,
        writer_version: "0.1.0".to_string(),
    };

    let writer = EventLogWriter::start(writer_config);
    let handle = writer.handle();

    let base_ns = 1_791_547_200_000_000_000i64;

    // Snapshot
    handle
        .log_snapshot(
            "BTC-USDT",
            &spec,
            ts(base_ns),
            ts(base_ns),
            &[level(6000000, 500000)],
            &[level(6001000, 400000)],
        )
        .unwrap();

    // Three gap markers spread across the recording
    for g in 0..3i64 {
        let t = ts(base_ns + (g + 1) * 1_000_000_000);
        handle
            .log_gap(
                "BTC-USDT",
                &spec,
                t,
                t,
                g as u64,
                (g + 5) as u64,
                "test_gap",
            )
            .unwrap();
    }

    // A resync marker (should count as marker but not gap)
    let resync_ts = ts(base_ns + 5_000_000_000);
    handle
        .log_resync("BTC-USDT", &spec, resync_ts, resync_ts, 2, "test_resync")
        .unwrap();

    writer.shutdown().unwrap();

    let mut driver = ReplayDriver::new(spec, book_config(), metrics_config()).unwrap();
    let summary = driver
        .replay_day(temp_dir.path(), "BINANCE", "BTC-USDT", "2026-10-09")
        .unwrap();

    assert_eq!(summary.gaps_detected, 3, "Must count all 3 gap markers");
    assert_eq!(summary.markers_count, 4, "3 gaps + 1 resync = 4 markers");
    assert!(summary.incomplete, "3 gaps = incomplete");
}

// ── Test: Replay determinism preserved with gaps ────────────────────────────

#[test]
fn replay_determinism_preserved_with_gaps() {
    let temp_dir = TempDir::new().unwrap();
    let spec = btc_spec();

    let writer_config = WriterConfig {
        base_dir: temp_dir.path().to_path_buf(),
        venue: "BINANCE".to_string(),
        channel_capacity: 65_536,
        drop_on_full: false,
        zstd_level: 1,
        writer_version: "0.1.0".to_string(),
    };

    let writer = EventLogWriter::start(writer_config);
    let handle = writer.handle();

    let base_ns = 1_791_547_200_000_000_000i64;

    // Write snapshot + deltas + gap + more deltas
    handle
        .log_snapshot(
            "BTC-USDT",
            &spec,
            ts(base_ns),
            ts(base_ns),
            &[level(6000000, 500000)],
            &[level(6001000, 400000)],
        )
        .unwrap();

    for i in 1..=5i64 {
        let t = ts(base_ns + i * 1_000_000_000);
        handle
            .log_delta(
                "BTC-USDT",
                &spec,
                t,
                t,
                &[level(6000000, 500000 + i * 10000)],
                &[level(6001000, 400000)],
            )
            .unwrap();
    }

    // Gap marker
    handle
        .log_gap(
            "BTC-USDT",
            &spec,
            ts(base_ns + 6_000_000_000),
            ts(base_ns + 6_000_000_000),
            6,
            10,
            "overflow",
        )
        .unwrap();

    for i in 7..=10i64 {
        let t = ts(base_ns + i * 1_000_000_000);
        handle
            .log_delta(
                "BTC-USDT",
                &spec,
                t,
                t,
                &[level(6000000, 600000)],
                &[level(6001000, 500000)],
            )
            .unwrap();
    }

    writer.shutdown().unwrap();

    // Two replays must produce identical results
    let mut d1 = ReplayDriver::new(spec, book_config(), metrics_config()).unwrap();
    let s1 = d1
        .replay_day(temp_dir.path(), "BINANCE", "BTC-USDT", "2026-10-09")
        .unwrap();

    let mut d2 = ReplayDriver::new(spec, book_config(), metrics_config()).unwrap();
    let s2 = d2
        .replay_day(temp_dir.path(), "BINANCE", "BTC-USDT", "2026-10-09")
        .unwrap();

    assert_eq!(s1, s2, "Replays must be byte-identical even with gaps");
    assert_eq!(s1.book_checksum, s2.book_checksum);
    assert!(s1.incomplete);
    assert!(s2.incomplete);
}
