//! Binance Feed CLI: connects to Binance WebSocket + REST, synchronizes order book, and prints events.

use clap::Parser;
use futures_util::StreamExt;
use sb_book::BookConfig;
use sb_connectors::{
    BinanceConfig, BinanceConnector, ConnectionState, Exchange, MarketEvent, OrderBookSynchronizer,
    ParsedMessage,
};
use sb_core::{BookSide, Clock, InstrumentSpec, SymbolId, SystemClock};
use std::time::Duration;
use tokio_tungstenite::connect_async;

#[derive(Parser, Debug)]
#[command(
    name = "binance-feed",
    about = "Stream Binance market data and display synchronized order book events"
)]
struct Args {
    /// Symbol to track (e.g. BTCUSDT, ETHUSDT)
    #[arg(short, long, default_value = "BTCUSDT")]
    symbol: String,

    /// Run duration in seconds (0 = run forever)
    #[arg(short, long, default_value_t = 0)]
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
    println!("Starting Binance feed for symbol: {}", args.symbol);

    let config = BinanceConfig {
        ws_base_url: args.ws_url,
        rest_base_url: args.rest_url,
        snapshot_depth_limit: 1000,
    };

    let mut connector = BinanceConnector::with_config(config)?;
    let spec = InstrumentSpec::new(2, 5, 1, 1, 0)?;
    let symbol_id = SymbolId::new(1);
    connector.register_symbol(symbol_id, &args.symbol, spec);

    let book_config = BookConfig::new(65536, 1000, 50)?;
    let mut synchronizer = OrderBookSynchronizer::new(symbol_id, spec, book_config)?;

    let specs = connector.subscribe_specs(&[symbol_id]);
    let stream_url = &specs[0].url;
    println!("Connecting to WebSocket: {}", stream_url);

    let (ws_stream, _) = connect_async(stream_url).await?;
    println!("WebSocket connected! Starting order book synchronization...");
    let (_, mut read) = ws_stream.split();

    synchronizer.start_buffering();

    // Fetch snapshot
    println!("Fetching REST snapshot...");
    let snapshot = connector.fetch_snapshot(symbol_id).await?;
    println!(
        "Snapshot fetched! lastUpdateId = {}",
        snapshot.last_update_id
    );

    let snap_event = synchronizer.apply_snapshot(snapshot)?;
    if let MarketEvent::Snapshot {
        epoch,
        seq,
        bids,
        asks,
        timing,
        ..
    } = snap_event
    {
        println!(
            "🟢 [LIVE SNAPSHOT] epoch={} seq={} bids={} asks={} (internal_lat={}ns)",
            epoch.id(),
            seq.get(),
            bids.len(),
            asks.len(),
            timing.internal_latency_ns()
        );
        if let Some(best_bid) = bids.first() {
            println!(
                "   Best Bid: {} @ {}",
                best_bid.qty.units(),
                best_bid.price.units()
            );
        }
        if let Some(best_ask) = asks.first() {
            println!(
                "   Best Ask: {} @ {}",
                best_ask.qty.units(),
                best_ask.price.units()
            );
        }
    }

    let clock = SystemClock::new();
    let start_time = std::time::Instant::now();

    while let Some(msg_res) = read.next().await {
        if args.duration_secs > 0 && start_time.elapsed() >= Duration::from_secs(args.duration_secs)
        {
            println!("Duration elapsed, exiting.");
            break;
        }

        let msg = msg_res?;
        if msg.is_text() || msg.is_binary() {
            let recv_ts = clock.wall_ns();
            let data = msg.into_data();
            match connector.parse(&data, recv_ts) {
                Ok(ParsedMessage::Diff(diff)) => match synchronizer.on_diff(diff) {
                    Ok(Some(MarketEvent::Delta {
                        seq,
                        bids,
                        asks,
                        timing,
                        ..
                    })) => {
                        println!(
                            "⚡ [DELTA] seq={} bids_updated={} asks_updated={} (e2e_lat={}ns)",
                            seq.get(),
                            bids.len(),
                            asks.len(),
                            timing.e2e_latency_ns()
                        );
                        if let Some(bid) = synchronizer.book().best_bid() {
                            if let Some(ask) = synchronizer.book().best_ask() {
                                println!(
                                    "   Spread: {} (bid={} ask={})",
                                    ask.price.units() - bid.price.units(),
                                    bid.price.units(),
                                    ask.price.units()
                                );
                            }
                        }
                    }
                    Ok(None) => {}
                    Err(e) => {
                        eprintln!("⚠️ Sync gap or error: {:?}. Resync required!", e);
                        if synchronizer.state() == ConnectionState::Invalid {
                            println!("Resyncing book...");
                            synchronizer.start_buffering();
                            let snap = connector.fetch_snapshot(symbol_id).await?;
                            let _ = synchronizer.apply_snapshot(snap)?;
                            println!("Resynced!");
                        }
                    }
                },
                Ok(ParsedMessage::Trade(trade)) => {
                    println!(
                        "💰 [TRADE] id={} price={} qty={} side={:?}",
                        trade.venue_trade_id,
                        trade.price.units(),
                        trade.qty.units(),
                        trade.aggressor
                    );
                }
                Ok(ParsedMessage::Pong | ParsedMessage::Ignored) => {}
                Err(e) => {
                    eprintln!("Parse error: {:?}", e);
                }
            }
        }
    }

    Ok(())
}
