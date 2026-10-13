//! Capture Tool CLI: connects to Binance and records raw frames and REST snapshot to a JSONL file.

use clap::Parser;
use futures_util::StreamExt;
use sb_connectors::{BinanceConfig, BinanceConnector, Exchange, RawRecord};
use sb_core::{Clock, InstrumentSpec, SymbolId, SystemClock};
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::PathBuf;
use std::time::Duration;
use tokio_tungstenite::connect_async;

#[derive(Parser, Debug)]
#[command(
    name = "capture-tool",
    about = "Capture raw Binance market data feed to a JSONL fixture file"
)]
struct Args {
    /// Symbol to capture (e.g. BTCUSDT)
    #[arg(short, long, default_value = "BTCUSDT")]
    symbol: String,

    /// Output destination path
    #[arg(
        short,
        long,
        default_value = "testdata/feeds/binance_btcusdt_fixture.jsonl"
    )]
    output: PathBuf,

    /// Maximum frames to capture (0 = unbounded)
    #[arg(short, long, default_value_t = 200)]
    limit: usize,

    /// Capture duration in seconds (0 = unbounded)
    #[arg(short, long, default_value_t = 10)]
    duration_secs: u64,

    /// Custom WebSocket base URL
    #[arg(long, default_value = "wss://stream.binance.com:9443")]
    ws_url: String,

    /// Custom REST base URL
    #[arg(long, default_value = "https://api.binance.com")]
    rest_url: String,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();
    println!(
        "Capturing feed for symbol {} into {:?}",
        args.symbol, args.output
    );

    if let Some(parent) = args.output.parent() {
        std::fs::create_dir_all(parent)?;
    }

    let file = File::create(&args.output)?;
    let mut writer = BufWriter::new(file);

    let config = BinanceConfig {
        ws_base_url: args.ws_url,
        rest_base_url: args.rest_url,
        snapshot_depth_limit: 1000,
    };

    let mut connector = BinanceConnector::with_config(config)?;
    let spec = InstrumentSpec::new(2, 5, 1, 1, 0)?;
    let symbol_id = SymbolId::new(1);
    connector.register_symbol(symbol_id, &args.symbol, spec);

    let clock = SystemClock::new();

    // 1. Fetch REST snapshot and record it
    println!("Fetching and recording REST snapshot...");
    let url = format!(
        "{}/api/v3/depth?symbol={}&limit=1000",
        connector.venue(),
        args.symbol
    );
    let snapshot_res = reqwest::get(&url).await?;
    let snapshot_text = snapshot_res.text().await?;
    let snap_record = RawRecord {
        recv_ts_ns: clock.wall_ns().as_nanos(),
        venue: "BINANCE".to_string(),
        stream: format!("{}:REST:depth", args.symbol),
        payload: snapshot_text,
    };
    serde_json::to_writer(&mut writer, &snap_record)?;
    writeln!(&mut writer)?;
    writer.flush()?;
    println!("REST snapshot recorded!");

    // 2. Connect to WebSocket
    let specs = connector.subscribe_specs(&[symbol_id]);
    let stream_url = &specs[0].url;
    println!("Connecting to WebSocket: {}", stream_url);

    let (ws_stream, _) = connect_async(stream_url).await?;
    let (_, mut read) = ws_stream.split();

    let start = std::time::Instant::now();
    let mut count = 0;

    while let Some(msg_res) = read.next().await {
        if args.duration_secs > 0 && start.elapsed() >= Duration::from_secs(args.duration_secs) {
            println!("Duration elapsed.");
            break;
        }

        let msg = msg_res?;
        if msg.is_text() || msg.is_binary() {
            let recv_ts_ns = clock.wall_ns().as_nanos();
            let text = msg.to_text()?.to_string();

            let record = RawRecord {
                recv_ts_ns,
                venue: "BINANCE".to_string(),
                stream: format!("{}@depth@100ms", args.symbol.to_ascii_lowercase()),
                payload: text,
            };

            serde_json::to_writer(&mut writer, &record)?;
            writeln!(&mut writer)?;
            count += 1;

            if count % 20 == 0 {
                writer.flush()?;
                println!("Captured {} frames...", count);
            }

            if args.limit > 0 && count >= args.limit {
                println!("Limit reached.");
                break;
            }
        }
    }

    writer.flush()?;
    println!(
        "Capture complete! Recorded {} frames to {:?}",
        count, args.output
    );
    Ok(())
}
