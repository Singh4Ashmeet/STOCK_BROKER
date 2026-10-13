//! Deterministic Replay Tests: Replaying a recorded day twice gives byte-identical books and metrics.

use tempfile::TempDir;

use sb_book::BookConfig;
use sb_core::{Aggressor, BookSide, InstrumentSpec, Level, Price, Qty, TsNs};
use sb_metrics::engine::MetricsConfig;
use sb_replay::{EventLogWriter, ReplayDriver, WriterConfig};

fn btc_spec() -> InstrumentSpec {
    InstrumentSpec::new(2, 5, 10, 100, 0).unwrap()
}

fn book_config() -> BookConfig {
    BookConfig::new(2048, 100, 50).unwrap()
}

fn metrics_config() -> MetricsConfig {
    MetricsConfig {
        depth_levels: 10,
        vwap_window_ns: 3_600_000_000_000, // 1 hour
        trade_flow_window_ns: 3_600_000_000_000,
        vwap_capacity: 4096,
        trade_flow_capacity: 4096,
    }
}

#[test]
fn test_replaying_recorded_day_twice_gives_byte_identical_book_and_metrics() {
    let temp_dir = TempDir::new().unwrap();
    let spec = btc_spec();

    let writer_config = WriterConfig {
        base_dir: temp_dir.path().to_path_buf(),
        venue: "BINANCE".to_string(),
        channel_capacity: 65_536,
        drop_on_full: false, // offline recording guarantee
        zstd_level: 1,
        writer_version: "0.1.0".to_string(),
    };

    let writer = EventLogWriter::start(writer_config);
    let handle = writer.handle();

    // Generate a full simulated day across all 24 hours of 2026-10-09
    // Base start: 2026-10-09 00:00:00 UTC = 1_791_504_000 s
    let base_sec = 1_791_504_000i64;

    // Initial snapshot in hour 0
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

    // Iterate through all 24 hours of the day (hour 00 to 23)
    for hour in 0..24i64 {
        let hour_start_sec = base_sec + hour * 3600;

        // Apply several deltas in this hour
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
                        Qty::from_units(0).unwrap() // removal
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

            // Execute trades in this hour
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
                    &format!("trd_{}_{}", hour, step),
                )
                .unwrap();
        }

        // Add a resync or gap marker every 6 hours
        if hour % 6 == 0 && hour > 0 {
            let marker_ts = TsNs::from_nanos((hour_start_sec + 3500) * 1_000_000_000);
            handle
                .log_resync(
                    "BTC-USDT",
                    &spec,
                    marker_ts,
                    marker_ts,
                    hour as u64,
                    "scheduled_crosscheck_ok",
                )
                .unwrap();
        }
    }

    // Flush and cleanly finish writing the recorded day
    writer.shutdown().unwrap();

    // ──────────────────────────────────────────────────────────────────────────
    // REPLAY RUN 1
    // ──────────────────────────────────────────────────────────────────────────
    let mut driver1 = ReplayDriver::new(spec, book_config(), metrics_config()).unwrap();
    let summary1 = driver1
        .replay_day(temp_dir.path(), "BINANCE", "BTC-USDT", "2026-10-09")
        .unwrap();

    assert!(summary1.total_records > 0, "Must have replayed records");
    assert_eq!(summary1.snapshots_count, 1);
    assert_eq!(summary1.deltas_count, 24 * 10);
    assert_eq!(summary1.trades_count, 24 * 10);
    assert_eq!(summary1.markers_count, 3);

    let book1_bid = driver1.book().best_bid();
    let book1_ask = driver1.book().best_ask();
    let book1_depth_bid = driver1.book().depth_qty(BookSide::Bid, 10);
    let book1_depth_ask = driver1.book().depth_qty(BookSide::Ask, 10);
    let mut levels1_bid =
        [Level::new(Price::from_units(0).unwrap(), Qty::from_units(0).unwrap()); 20];
    let count1_bid = driver1.book().levels(BookSide::Bid, 20, &mut levels1_bid);
    let mut levels1_ask =
        [Level::new(Price::from_units(0).unwrap(), Qty::from_units(0).unwrap()); 20];
    let count1_ask = driver1.book().levels(BookSide::Ask, 20, &mut levels1_ask);

    // ──────────────────────────────────────────────────────────────────────────
    // REPLAY RUN 2 (Fresh driver over the exact same recorded files)
    // ──────────────────────────────────────────────────────────────────────────
    let mut driver2 = ReplayDriver::new(spec, book_config(), metrics_config()).unwrap();
    let summary2 = driver2
        .replay_day(temp_dir.path(), "BINANCE", "BTC-USDT", "2026-10-09")
        .unwrap();

    let book2_bid = driver2.book().best_bid();
    let book2_ask = driver2.book().best_ask();
    let book2_depth_bid = driver2.book().depth_qty(BookSide::Bid, 10);
    let book2_depth_ask = driver2.book().depth_qty(BookSide::Ask, 10);
    let mut levels2_bid =
        [Level::new(Price::from_units(0).unwrap(), Qty::from_units(0).unwrap()); 20];
    let count2_bid = driver2.book().levels(BookSide::Bid, 20, &mut levels2_bid);
    let mut levels2_ask =
        [Level::new(Price::from_units(0).unwrap(), Qty::from_units(0).unwrap()); 20];
    let count2_ask = driver2.book().levels(BookSide::Ask, 20, &mut levels2_ask);

    // ──────────────────────────────────────────────────────────────────────────
    // PARITY ASSERTIONS (Exact Byte-Identical Determinism)
    // ──────────────────────────────────────────────────────────────────────────
    // 1. Overall summaries and event counts match
    assert_eq!(summary1, summary2, "Replay summaries must be identical");
    assert_eq!(
        summary1.book_checksum, summary2.book_checksum,
        "Book checksum fingerprints must be byte-identical"
    );

    // 2. Best bid/ask levels match
    assert_eq!(book1_bid, book2_bid, "Best bid must match byte-for-byte");
    assert_eq!(book1_ask, book2_ask, "Best ask must match byte-for-byte");

    // 3. Depth quantities match
    assert_eq!(
        book1_depth_bid, book2_depth_bid,
        "Bid depth must match byte-for-byte"
    );
    assert_eq!(
        book1_depth_ask, book2_depth_ask,
        "Ask depth must match byte-for-byte"
    );

    // 4. All extracted levels match
    assert_eq!(count1_bid, count2_bid);
    assert_eq!(
        &levels1_bid[..count1_bid],
        &levels2_bid[..count2_bid],
        "Bid levels must match byte-for-byte"
    );
    assert_eq!(count1_ask, count2_ask);
    assert_eq!(
        &levels1_ask[..count1_ask],
        &levels2_ask[..count2_ask],
        "Ask levels must match byte-for-byte"
    );

    // 5. Final metrics match exactly
    assert_eq!(
        summary1.final_metrics.bbo.spread_units,
        summary2.final_metrics.bbo.spread_units
    );
    assert_eq!(
        summary1.final_metrics.bbo.mid,
        summary2.final_metrics.bbo.mid
    );
    assert_eq!(
        summary1.final_metrics.bbo.microprice,
        summary2.final_metrics.bbo.microprice
    );
    assert_eq!(
        summary1.final_metrics.depth.bid_depth,
        summary2.final_metrics.depth.bid_depth
    );
    assert_eq!(
        summary1.final_metrics.depth.ask_depth,
        summary2.final_metrics.depth.ask_depth
    );
    assert_eq!(
        summary1.final_metrics.depth.imbalance,
        summary2.final_metrics.depth.imbalance
    );
    assert_eq!(
        summary1.final_metrics.vwap.vwap,
        summary2.final_metrics.vwap.vwap
    );
    assert_eq!(
        summary1.final_metrics.trade_flow.imbalance,
        summary2.final_metrics.trade_flow.imbalance
    );
}
