//! Parity Test: Replaying one recorded day through the Rust server and through pybook
//! and asserting identical books and metrics at every event.

use std::path::PathBuf;
use tempfile::TempDir;

use pybook::PyEventReader;
use sb_book::BookConfig;
use sb_core::{Aggressor, BookSide, InstrumentSpec, Level, Price, Qty, TsNs};
use sb_metrics::engine::MetricsConfig;
use sb_replay::reader::EventLogReader;
use sb_replay::schema::{decode_book_payload, decode_trade_payload, RecordKind};
use sb_replay::{EventLogWriter, WriterConfig};
use sb_server::state::SymbolState;

fn btc_spec() -> InstrumentSpec {
    InstrumentSpec::new(2, 5, 10, 100, 0).unwrap()
}

fn book_config() -> BookConfig {
    BookConfig::new(4096, 1024, 50).unwrap()
}

fn metrics_config() -> MetricsConfig {
    MetricsConfig {
        depth_levels: 10,
        vwap_window_ns: 3_600_000_000_000, // 1 hour
        trade_flow_window_ns: 3_600_000_000_000,
        vwap_capacity: 8192,
        trade_flow_capacity: 8192,
    }
}

/// Helper that writes one simulated day (24 hourly files) of event logs.
fn generate_recorded_day(dir: &std::path::Path) -> Vec<PathBuf> {
    let spec = btc_spec();
    let writer_config = WriterConfig {
        base_dir: dir.to_path_buf(),
        venue: "BINANCE".to_string(),
        channel_capacity: 65_536,
        drop_on_full: false,
        zstd_level: 1,
        writer_version: "0.1.0".to_string(),
    };

    let writer = EventLogWriter::start(writer_config);
    let handle = writer.handle();

    // Start 2026-10-09 00:00:00 UTC = 1_791_504_000 s
    let base_sec = 1_791_504_000i64;
    let t0 = TsNs::from_nanos(base_sec * 1_000_000_000);

    let initial_bids = vec![
        Level::new(
            Price::from_units(6000000).unwrap(),
            Qty::from_units(500000).unwrap(),
        ),
        Level::new(
            Price::from_units(5999000).unwrap(),
            Qty::from_units(1000000).unwrap(),
        ),
        Level::new(
            Price::from_units(5998000).unwrap(),
            Qty::from_units(1500000).unwrap(),
        ),
    ];
    let initial_asks = vec![
        Level::new(
            Price::from_units(6001000).unwrap(),
            Qty::from_units(400000).unwrap(),
        ),
        Level::new(
            Price::from_units(6002000).unwrap(),
            Qty::from_units(800000).unwrap(),
        ),
        Level::new(
            Price::from_units(6003000).unwrap(),
            Qty::from_units(1200000).unwrap(),
        ),
    ];

    handle
        .log_snapshot("BTC-USDT", &spec, t0, t0, &initial_bids, &initial_asks)
        .unwrap();

    // 24 hours of trading activity
    for hour in 0..24i64 {
        let hour_start_sec = base_sec + hour * 3600;

        for step in 1..=10i64 {
            let current_sec = hour_start_sec + step * 300;
            let ts = TsNs::from_nanos(current_sec * 1_000_000_000);

            let delta_bids = vec![
                Level::new(
                    Price::from_units(6000000 + (step % 5) * 10).unwrap(),
                    Qty::from_units(500000 + step * 10000).unwrap(),
                ),
                Level::new(
                    Price::from_units(5998000).unwrap(),
                    if step % 3 == 0 {
                        Qty::from_units(0).unwrap()
                    } else {
                        Qty::from_units(800000).unwrap()
                    },
                ),
            ];
            let delta_asks = vec![Level::new(
                Price::from_units(6001000 + (step % 4) * 10).unwrap(),
                Qty::from_units(400000 + step * 5000).unwrap(),
            )];

            handle
                .log_delta("BTC-USDT", &spec, ts, ts, &delta_bids, &delta_asks)
                .unwrap();

            let is_buy = (step + hour) % 2 == 0;
            let aggressor = if is_buy {
                Aggressor::Buy
            } else {
                Aggressor::Sell
            };
            let trade_price = if is_buy {
                Price::from_units(6001000).unwrap()
            } else {
                Price::from_units(6000000).unwrap()
            };
            let trade_qty = Qty::from_units(25000 * step).unwrap();

            handle
                .log_trade(
                    "BTC-USDT",
                    &spec,
                    ts,
                    ts,
                    trade_price,
                    trade_qty,
                    aggressor,
                    &format!("trd_{hour}_{step}"),
                )
                .unwrap();
        }

        if hour % 6 == 0 && hour > 0 {
            let marker_ts = TsNs::from_nanos((hour_start_sec + 3500) * 1_000_000_000);
            handle
                .log_resync(
                    "BTC-USDT",
                    &spec,
                    marker_ts,
                    marker_ts,
                    hour as u64,
                    "hourly_audit_resync",
                )
                .unwrap();
        }
    }

    writer.shutdown().unwrap();

    // Collect all generated .pbz files in sorted order
    let day_dir = dir
        .join("venue=BINANCE")
        .join("symbol=BTC-USDT")
        .join("date=2026-10-09");
    let mut files = Vec::new();
    for entry in std::fs::read_dir(&day_dir).unwrap() {
        let p = entry.unwrap().path();
        if p.is_dir() {
            for fe in std::fs::read_dir(&p).unwrap() {
                let fp = fe.unwrap().path();
                if fp.extension().and_then(|s| s.to_str()) == Some("pbz") {
                    files.push(fp);
                }
            }
        }
    }
    files.sort();
    assert_eq!(files.len(), 24, "Must have exactly 24 hourly files");
    files
}

#[test]
fn test_replay_parity_rust_server_and_pybook_at_every_event() {
    let temp_dir = TempDir::new().unwrap();
    let files = generate_recorded_day(temp_dir.path());
    let spec = btc_spec();

    // ──────────────────────────────────────────────────────────────────────────
    // Step 1: Initialize Rust Server SymbolState
    // ──────────────────────────────────────────────────────────────────────────
    let mut server_state = SymbolState::new(spec, book_config(), metrics_config(), 10).unwrap();

    // Collect all expected states from the Rust Server stream
    struct EventState {
        best_bid_p: i64,
        best_bid_q: i64,
        best_ask_p: i64,
        best_ask_q: i64,
        spread: i64,
        mid: f64,
        microprice: f64,
        imbalance: f64,
        vwap: f64,
        trade_flow_imbalance: f64,
        depth_bids: Vec<(i64, i64)>,
        depth_asks: Vec<(i64, i64)>,
    }

    let mut expected_events: Vec<EventState> = Vec::new();

    for file_path in &files {
        let mut reader = EventLogReader::open(file_path).unwrap();
        while let Some(record) = reader.next_record().unwrap() {
            let kind = match RecordKind::from_i32(record.kind) {
                Some(k) => k,
                None => continue,
            };

            let exchange_ts = TsNs::from_nanos(record.exchange_ts_ns);
            match kind {
                RecordKind::BookSnapshot => {
                    let (bids, asks) = decode_book_payload(&record.payload).unwrap();
                    server_state.apply_snapshot(&bids, &asks).unwrap();
                }
                RecordKind::BookDelta => {
                    let (bids, asks) = decode_book_payload(&record.payload).unwrap();
                    server_state.apply_delta(&bids, &asks).unwrap();
                }
                RecordKind::Trade => {
                    let trade = decode_trade_payload(&record.payload).unwrap();
                    server_state.record_trade(exchange_ts, trade.price, trade.qty, trade.aggressor);
                }
                RecordKind::GapMarker | RecordKind::ResyncMarker | RecordKind::ReconnectMarker => {
                    server_state.set_invalid();
                }
                RecordKind::RawFrame => continue,
            }

            // Capture state from Rust Server SymbolState
            let metrics_snap = server_state.metrics().snapshot();
            let bb = server_state.book().best_bid();
            let ba = server_state.book().best_ask();

            let mut bid_buf = [Level::new(Price::ZERO, Qty::ZERO); 10];
            let nb = server_state.book().levels(BookSide::Bid, 10, &mut bid_buf);
            let depth_bids: Vec<(i64, i64)> = (0..10)
                .map(|i| {
                    if i < nb {
                        (bid_buf[i].price.units(), bid_buf[i].qty.units())
                    } else {
                        (0, 0)
                    }
                })
                .collect();

            let mut ask_buf = [Level::new(Price::ZERO, Qty::ZERO); 10];
            let na = server_state.book().levels(BookSide::Ask, 10, &mut ask_buf);
            let depth_asks: Vec<(i64, i64)> = (0..10)
                .map(|i| {
                    if i < na {
                        (ask_buf[i].price.units(), ask_buf[i].qty.units())
                    } else {
                        (0, 0)
                    }
                })
                .collect();

            expected_events.push(EventState {
                best_bid_p: bb.map(|l| l.price.units()).unwrap_or(0),
                best_bid_q: bb.map(|l| l.qty.units()).unwrap_or(0),
                best_ask_p: ba.map(|l| l.price.units()).unwrap_or(0),
                best_ask_q: ba.map(|l| l.qty.units()).unwrap_or(0),
                spread: metrics_snap.bbo.spread_units,
                mid: metrics_snap.bbo.mid,
                microprice: metrics_snap.bbo.microprice,
                imbalance: metrics_snap.depth.imbalance,
                vwap: metrics_snap.vwap.vwap,
                trade_flow_imbalance: metrics_snap.trade_flow.imbalance,
                depth_bids,
                depth_asks,
            });
        }
    }

    assert!(
        !expected_events.is_empty(),
        "Expected events must not be empty"
    );

    // ──────────────────────────────────────────────────────────────────────────
    // Step 2: Replay through PyEventReader (coarse 10,000 batch API)
    // ──────────────────────────────────────────────────────────────────────────
    let mut py_reader = PyEventReader::from_paths(files, Some(spec), 10, 3600, 3600, true).unwrap();

    let mut py_event_idx = 0;

    while let Some(batch) = py_reader.process_batch(10000).unwrap() {
        for i in 0..batch.length {
            let expected = &expected_events[py_event_idx];

            // 1. Order book BBO parity
            assert_eq!(
                batch.best_bid_price[i], expected.best_bid_p,
                "Best bid price mismatch at event {py_event_idx}"
            );
            assert_eq!(
                batch.best_bid_qty[i], expected.best_bid_q,
                "Best bid qty mismatch at event {py_event_idx}"
            );
            assert_eq!(
                batch.best_ask_price[i], expected.best_ask_p,
                "Best ask price mismatch at event {py_event_idx}"
            );
            assert_eq!(
                batch.best_ask_qty[i], expected.best_ask_q,
                "Best ask qty mismatch at event {py_event_idx}"
            );

            // 2. Metrics parity
            assert_eq!(
                batch.spread[i], expected.spread,
                "Spread mismatch at event {py_event_idx}"
            );
            assert!(
                (batch.mid[i] - expected.mid).abs() < 1e-9,
                "Mid price mismatch at event {py_event_idx}: {} vs {}",
                batch.mid[i],
                expected.mid
            );
            assert!(
                (batch.microprice[i] - expected.microprice).abs() < 1e-9,
                "Microprice mismatch at event {py_event_idx}: {} vs {}",
                batch.microprice[i],
                expected.microprice
            );
            assert!(
                (batch.imbalance[i] - expected.imbalance).abs() < 1e-9,
                "Imbalance mismatch at event {py_event_idx}: {} vs {}",
                batch.imbalance[i],
                expected.imbalance
            );
            assert!(
                (batch.vwap[i] - expected.vwap).abs() < 1e-9,
                "VWAP mismatch at event {py_event_idx}: {} vs {}",
                batch.vwap[i],
                expected.vwap
            );
            assert!(
                (batch.trade_flow_imbalance[i] - expected.trade_flow_imbalance).abs() < 1e-9,
                "Trade flow imbalance mismatch at event {py_event_idx}: {} vs {}",
                batch.trade_flow_imbalance[i],
                expected.trade_flow_imbalance
            );

            // 3. Top-N depth array parity
            let depth_offset = i * 10;
            for d in 0..10 {
                let (exp_bp, exp_bq) = expected.depth_bids[d];
                let (exp_ap, exp_aq) = expected.depth_asks[d];
                assert_eq!(
                    batch.bid_prices[depth_offset + d],
                    exp_bp,
                    "Bid price level {d} mismatch at event {py_event_idx}"
                );
                assert_eq!(
                    batch.bid_qtys[depth_offset + d],
                    exp_bq,
                    "Bid qty level {d} mismatch at event {py_event_idx}"
                );
                assert_eq!(
                    batch.ask_prices[depth_offset + d],
                    exp_ap,
                    "Ask price level {d} mismatch at event {py_event_idx}"
                );
                assert_eq!(
                    batch.ask_qtys[depth_offset + d],
                    exp_aq,
                    "Ask qty level {d} mismatch at event {py_event_idx}"
                );
            }

            py_event_idx += 1;
        }
    }

    assert_eq!(
        py_event_idx,
        expected_events.len(),
        "Total processed events through pybook must equal Rust server events"
    );
}
