//! Unit and integration tests for the background event-log writer thread (ADR-0006).

use std::sync::atomic::Ordering;
use tempfile::TempDir;

use sb_core::{Aggressor, InstrumentSpec, Level, Price, Qty, TsNs};
use sb_replay::{EventLogReader, EventLogWriter, RecordKind, SCHEMA_VERSION, WriterConfig};

fn btc_spec() -> InstrumentSpec {
    InstrumentSpec::new(2, 5, 10, 100, 0).unwrap()
}

#[test]
fn test_writer_creates_hour_partitioned_files_with_schema() {
    let temp_dir = TempDir::new().unwrap();
    let config = WriterConfig {
        base_dir: temp_dir.path().to_path_buf(),
        venue: "BINANCE".to_string(),
        channel_capacity: 1024,
        drop_on_full: false,
        zstd_level: 1,
        writer_version: "0.1.0".to_string(),
    };

    let spec = btc_spec();
    let writer = EventLogWriter::start(config);
    let handle = writer.handle();

    // Event 1: Hour 10 (2026-10-09 10:15:00 UTC)
    // 2026-10-09 10:00:00 UTC = 1_791_540_000 s -> 1_791_540_900_000_000_000 ns
    let h10_ts = TsNs::from_nanos(1_791_540_900_000_000_000);
    let bid1 = Level::new(
        Price::from_units(6000000).unwrap(),
        Qty::from_units(100000).unwrap(),
    );
    let ask1 = Level::new(
        Price::from_units(6001000).unwrap(),
        Qty::from_units(200000).unwrap(),
    );

    handle
        .log_snapshot("BTC-USDT", &spec, h10_ts, h10_ts, &[bid1], &[ask1])
        .unwrap();

    // Event 2: Hour 11 (2026-10-09 11:05:00 UTC) -> should trigger hour rollover
    let h11_ts = TsNs::from_nanos(1_791_544_500_000_000_000);
    handle
        .log_trade(
            "BTC-USDT",
            &spec,
            h11_ts,
            h11_ts,
            Price::from_units(6000500).unwrap(),
            Qty::from_units(50000).unwrap(),
            Aggressor::Buy,
            "123456",
        )
        .unwrap();

    // Graceful shutdown flushes both files
    writer.shutdown().unwrap();

    // Verify hour 10 file exists and contains snapshot
    let h10_file = temp_dir
        .path()
        .join("venue=BINANCE/symbol=BTC-USDT/date=2026-10-09/hour=10/part-0000.pbz");
    assert!(h10_file.exists(), "Hour 10 file must exist: {:?}", h10_file);

    let mut reader10 = EventLogReader::open(&h10_file).unwrap();
    assert_eq!(reader10.header().schema_version, SCHEMA_VERSION);
    assert_eq!(reader10.header().venue, "BINANCE");
    assert_eq!(reader10.header().symbol, "BTC-USDT");
    assert_eq!(reader10.header().price_scale, 2);
    assert_eq!(reader10.header().qty_scale, 5);

    let rec1 = reader10.next_record().unwrap().unwrap();
    assert_eq!(rec1.kind, RecordKind::BookSnapshot as i32);
    assert!(reader10.next_record().unwrap().is_none());

    // Verify hour 11 file exists and contains trade
    let h11_file = temp_dir
        .path()
        .join("venue=BINANCE/symbol=BTC-USDT/date=2026-10-09/hour=11/part-0000.pbz");
    assert!(h11_file.exists(), "Hour 11 file must exist: {:?}", h11_file);

    let mut reader11 = EventLogReader::open(&h11_file).unwrap();
    assert_eq!(reader11.header().schema_version, SCHEMA_VERSION);
    let rec2 = reader11.next_record().unwrap().unwrap();
    assert_eq!(rec2.kind, RecordKind::Trade as i32);
    assert!(reader11.next_record().unwrap().is_none());

    // Verify no temporary .part files were left behind
    for entry in walkdir::WalkDir::new(temp_dir.path()) {
        let entry = entry.unwrap();
        let fname = entry.file_name().to_string_lossy();
        assert!(
            !fname.ends_with(".part"),
            "Found unfinalized part file: {:?}",
            entry.path()
        );
    }
}

#[test]
fn test_live_mode_drops_when_buffer_full() {
    let temp_dir = TempDir::new().unwrap();
    // Capacity 2, drop_on_full = true
    let config = WriterConfig {
        base_dir: temp_dir.path().to_path_buf(),
        venue: "BINANCE".to_string(),
        channel_capacity: 2,
        drop_on_full: true,
        zstd_level: 1,
        writer_version: "0.1.0".to_string(),
    };

    let spec = btc_spec();
    let writer = EventLogWriter::start(config);
    let handle = writer.handle();

    let ts = TsNs::from_nanos(1_791_540_000_000_000_000);
    let bid = Level::new(
        Price::from_units(6000000).unwrap(),
        Qty::from_units(100000).unwrap(),
    );

    // Flood the channel quickly with many records
    let mut dropped = 0;
    for _ in 0..10_000 {
        if handle
            .log_snapshot("BTC-USDT", &spec, ts, ts, &[bid], &[])
            .is_err()
        {
            dropped += 1;
        }
    }

    assert!(
        dropped > 0,
        "Some records must have been dropped when buffer saturated"
    );
    assert_eq!(
        handle.stats().dropped_count.load(Ordering::Relaxed),
        dropped
    );

    writer.shutdown().unwrap();
}
