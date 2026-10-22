//! CLI tool to generate one full recorded day of market data for parity tests.

use std::path::PathBuf;

use sb_core::{Aggressor, InstrumentSpec, Level, Price, Qty, TsNs};
use sb_replay::{EventLogWriter, WriterConfig};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let base_dir = if args.len() > 1 {
        PathBuf::from(&args[1])
    } else {
        PathBuf::from("testdata/recorded_day")
    };

    let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
    let writer_config = WriterConfig {
        base_dir,
        venue: "BINANCE".to_string(),
        channel_capacity: 65_536,
        drop_on_full: false,
        zstd_level: 1,
        writer_version: "0.1.0".to_string(),
    };

    let writer = EventLogWriter::start(writer_config);
    let handle = writer.handle();

    let base_sec = 1_791_504_000i64; // 2026-10-09 00:00:00 UTC
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
                    "hourly_resync",
                )
                .unwrap();
        }
    }

    writer.shutdown().unwrap();
    println!("Successfully generated recorded day.");
}
