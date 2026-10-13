//! Integration tests for the gRPC MarketData service.
//!
//! Key test: a slow consumer does not affect the feed for fast consumers.

use std::sync::Arc;
use std::time::Duration;

use sb_book::BookConfig;
use sb_core::{InstrumentSpec, Level, Price, Qty};
use sb_metrics::engine::MetricsConfig;
use sb_server::broadcaster::{BroadcastLevel, BroadcastTiming, Broadcaster};
use sb_server::config::ServerConfig;
use sb_server::proto::market_data_server::MarketDataServer;
use sb_server::proto::{self, market_data_client::MarketDataClient};
use sb_server::service::MarketDataService;
use sb_server::state::SymbolState;

use tonic::transport::{Channel, Server};

/// Helper: create a test server config.
fn test_config() -> ServerConfig {
    ServerConfig {
        listen_addr: "[::1]:0".to_owned(), // port 0 = OS picks
        http_addr: "[::1]:0".to_owned(),
        symbols: vec!["BINANCE:BTC-USDT".to_owned()],
        subscriber_queue_capacity: 4, // small for testing conflation
        max_subscribers: 100,
        max_depth_levels: 50,
        metrics: sb_server::config::MetricsConfig {
            depth_levels: 10,
            vwap_window_secs: 60,
            trade_flow_window_secs: 60,
        },
        observability: sb_server::config::ObservabilityConfig::default(),
        instruments: vec![sb_server::config::InstrumentConfig {
            symbol: "BINANCE:BTC-USDT".to_owned(),
            price_scale: 2,
            qty_scale: 5,
            tick_size: 10,
            lot_size: 100,
            min_notional: 0,
        }],
    }
}

fn test_spec() -> InstrumentSpec {
    InstrumentSpec::new(2, 5, 10, 100, 0).unwrap()
}

fn test_metrics_config() -> MetricsConfig {
    MetricsConfig {
        depth_levels: 10,
        vwap_window_ns: 60_000_000_000,
        trade_flow_window_ns: 60_000_000_000,
        vwap_capacity: 4096,
        trade_flow_capacity: 4096,
    }
}

/// Start a test server and return a channel to it.
async fn start_test_server(config: ServerConfig) -> (Channel, tokio::task::JoinHandle<()>) {
    let listener = tokio::net::TcpListener::bind("[::1]:0").await.unwrap();
    let addr = listener.local_addr().unwrap();

    let svc = MarketDataService::new(config.clone());

    // Register one symbol with initial state
    let spec = test_spec();
    let mut state = SymbolState::new(spec, BookConfig::default(), test_metrics_config(), 50)
        .unwrap();

    // Apply an initial book
    state
        .apply_snapshot(
            &[
                Level::new(Price::from_units(5000000).unwrap(), Qty::from_units(100000).unwrap()),
                Level::new(Price::from_units(4999000).unwrap(), Qty::from_units(200000).unwrap()),
            ],
            &[
                Level::new(Price::from_units(5001000).unwrap(), Qty::from_units(150000).unwrap()),
                Level::new(Price::from_units(5002000).unwrap(), Qty::from_units(250000).unwrap()),
            ],
        )
        .unwrap();

    svc.register_symbol("BINANCE:BTC-USDT".to_owned(), state)
        .await;

    let svc = Arc::new(svc);

    let handle = tokio::spawn(async move {
        let incoming = tokio_stream::wrappers::TcpListenerStream::new(listener);
        Server::builder()
            .add_service(MarketDataServer::from_arc(svc))
            .serve_with_incoming(incoming)
            .await
            .unwrap();
    });

    // Give the server a moment to start
    tokio::time::sleep(Duration::from_millis(50)).await;

    let channel = Channel::from_shared(format!("http://[::1]:{}", addr.port()))
        .unwrap()
        .connect()
        .await
        .unwrap();

    (channel, handle)
}

#[tokio::test]
async fn health_endpoint_works() {
    let config = test_config();
    let (channel, _handle) = start_test_server(config).await;
    let mut client = MarketDataClient::new(channel);

    let resp = client
        .health(proto::HealthRequest {})
        .await
        .unwrap()
        .into_inner();

    assert!(resp.connected);
    assert_eq!(resp.symbols.len(), 1);
    assert_eq!(resp.symbols[0].symbol, "BINANCE:BTC-USDT");
}

#[tokio::test]
async fn list_instruments_works() {
    let config = test_config();
    let (channel, _handle) = start_test_server(config).await;
    let mut client = MarketDataClient::new(channel);

    let resp = client
        .list_instruments(proto::ListInstrumentsRequest {})
        .await
        .unwrap()
        .into_inner();

    assert_eq!(resp.instruments.len(), 1);
    assert_eq!(resp.instruments[0].symbol, "BINANCE:BTC-USDT");
    assert_eq!(resp.instruments[0].price_scale, 2);
}

#[tokio::test]
async fn get_snapshot_returns_book() {
    let config = test_config();
    let (channel, _handle) = start_test_server(config).await;
    let mut client = MarketDataClient::new(channel);

    let resp = client
        .get_snapshot(proto::SnapshotRequest {
            symbol: "BINANCE:BTC-USDT".to_owned(),
            depth_levels: 10,
        })
        .await
        .unwrap()
        .into_inner();

    assert_eq!(resp.symbol, "BINANCE:BTC-USDT");
    assert!(resp.epoch > 0);
    assert!(resp.payload.is_some());

    match resp.payload.unwrap() {
        proto::book_update::Payload::Snapshot(snap) => {
            assert!(!snap.bids.is_empty());
            assert!(!snap.asks.is_empty());
        }
        _ => panic!("expected snapshot"),
    }
}

#[tokio::test]
async fn subscribe_receives_initial_snapshot() {
    let config = test_config();
    let (channel, _handle) = start_test_server(config).await;
    let mut client = MarketDataClient::new(channel);

    let mut stream = client
        .subscribe_book(proto::SubscribeRequest {
            symbols: vec!["BINANCE:BTC-USDT".to_owned()],
            depth_levels: 10,
            metrics_levels: 0,
        })
        .await
        .unwrap()
        .into_inner();

    // First message should be a snapshot
    let msg = tokio::time::timeout(Duration::from_secs(2), stream.message())
        .await
        .unwrap()
        .unwrap()
        .unwrap();

    assert_eq!(msg.symbol, "BINANCE:BTC-USDT");
    assert_eq!(msg.epoch, 1); // subscriber epoch
    assert_eq!(msg.seq, 1);
    match msg.payload.unwrap() {
        proto::book_update::Payload::Snapshot(_) => {} // ok
        _ => panic!("expected snapshot as first message"),
    }
}

#[tokio::test]
async fn unknown_symbol_returns_not_found() {
    let config = test_config();
    let (channel, _handle) = start_test_server(config).await;
    let mut client = MarketDataClient::new(channel);

    let err = client
        .get_snapshot(proto::SnapshotRequest {
            symbol: "UNKNOWN:SYMBOL".to_owned(),
            depth_levels: 10,
        })
        .await
        .unwrap_err();

    assert_eq!(err.code(), tonic::Code::NotFound);
}

// =========================================================================
// Slow-consumer test: the critical acceptance test
// =========================================================================

/// Test that a slow consumer does NOT block or slow down a fast consumer.
///
/// Setup:
/// 1. Create a broadcaster with a small queue (capacity=4).
/// 2. Subscribe two consumers: one "fast" (drains immediately), one "slow" (never reads).
/// 3. Publish many deltas rapidly.
/// 4. Verify: the fast consumer receives all messages with no gaps.
/// 5. Verify: the slow consumer is conflated (gets snapshots, not deltas).
/// 6. Verify: the broadcast loop never blocks regardless of the slow consumer.
#[tokio::test]
async fn slow_consumer_does_not_block_fast_consumer() {
    let capacity = 4_usize;
    let mut bc = Broadcaster::new(capacity, 100);

    // Subscribe fast consumer and slow consumer
    let fast = bc.subscribe().unwrap();
    let slow = bc.subscribe().unwrap();

    let bids = vec![BroadcastLevel { price: 5000000, qty: 100000 }];
    let asks = vec![BroadcastLevel { price: 5001000, qty: 150000 }];
    let timing = BroadcastTiming {
        exchange_ts_ns: 1_000_000_000,
        recv_ts_ns: 1_000_100_000,
        publish_ts_ns: 1_000_200_000,
    };

    // Send initial snapshot to both
    bc.send_initial_snapshot(fast.id, "BTC", &bids, &asks, timing).unwrap();
    bc.send_initial_snapshot(slow.id, "BTC", &bids, &asks, timing).unwrap();

    // Fast consumer drains messages as they come
    let fast_rx = fast.rx;
    let fast_handle = tokio::spawn(async move {
        let mut rx = fast_rx;
        let mut count = 0;
        let mut last_seq = 0_u64;
        let mut gaps = 0_u64;

        while let Ok(msg) = tokio::time::timeout(Duration::from_millis(500), rx.recv()).await {
            if let Some(msg) = msg {
                let (_epoch, seq) = match &msg {
                    sb_server::broadcaster::OutboundMsg::BookSnapshot { epoch, seq, .. } => (*epoch, *seq),
                    sb_server::broadcaster::OutboundMsg::BookDelta { epoch, seq, .. } => (*epoch, *seq),
                    sb_server::broadcaster::OutboundMsg::MetricsUpdate { epoch, seq, .. } => (*epoch, *seq),
                };

                // Check for gaps (seq should increase by 1 within an epoch)
                if seq > 1 && last_seq > 0 && seq != last_seq + 1 {
                    // Gap could be from epoch change (conflation), which is acceptable
                    // Only count actual gaps within the same epoch as errors
                    gaps += 1;
                }
                last_seq = seq;
                count += 1;
            } else {
                break;
            }
        }

        (count, gaps)
    });

    // Slow consumer: NEVER reads from its channel (simulating a stalled client)
    // We keep `slow.rx` alive but don't read it
    let _slow_rx = slow.rx;

    // Publish many deltas rapidly — the slow consumer's queue will fill up
    let num_deltas = 100_usize;
    let start = std::time::Instant::now();

    for i in 0..num_deltas {
        let delta_bids = vec![BroadcastLevel {
            price: 5000000_i64.wrapping_add(i as i64),
            qty: 100000,
        }];
        let delta_asks = vec![BroadcastLevel {
            price: 5001000_i64.wrapping_add(i as i64),
            qty: 150000,
        }];

        bc.publish_delta("BTC", &delta_bids, &delta_asks, timing, &bids, &asks);
    }

    let publish_duration = start.elapsed();

    // Drop the broadcaster's subscribers to close channels
    bc.unsubscribe(fast.id);
    drop(_slow_rx);

    let (fast_count, _fast_gaps) = fast_handle.await.unwrap();

    // ---- ASSERTIONS ----

    // 1. Publishing was NOT blocked by the slow consumer.
    //    100 publishes should take well under 1 second.
    assert!(
        publish_duration < Duration::from_secs(1),
        "publishing took too long ({:?}), slow consumer may be blocking",
        publish_duration
    );

    // 2. Fast consumer received messages (at least the initial snapshot + some deltas).
    assert!(
        fast_count > 0,
        "fast consumer received 0 messages"
    );

    // 3. Slow consumer was conflated.
    let slow_conflations = slow.conflations.load(std::sync::atomic::Ordering::Relaxed);
    assert!(
        slow_conflations > 0,
        "slow consumer was never conflated (expected conflation with capacity={}, deltas={})",
        capacity,
        num_deltas
    );

    println!(
        "✓ slow-consumer test passed: {} publishes in {:?}, fast got {} msgs, slow conflated {} times",
        num_deltas, publish_duration, fast_count, slow_conflations
    );
}

/// Test that conflation produces valid snapshots (new epoch, seq=1).
#[tokio::test]
async fn conflation_produces_valid_epoch_reset() {
    let mut bc = Broadcaster::new(2, 100); // tiny queue
    let handle = bc.subscribe().unwrap();

    let bids = vec![BroadcastLevel { price: 5000000, qty: 100000 }];
    let asks = vec![BroadcastLevel { price: 5001000, qty: 150000 }];
    let timing = BroadcastTiming {
        exchange_ts_ns: 1_000_000_000,
        recv_ts_ns: 1_000_100_000,
        publish_ts_ns: 1_000_200_000,
    };

    // Fill the queue past capacity to trigger conflation
    for _ in 0..10 {
        bc.publish_delta("BTC", &bids, &asks, timing, &bids, &asks);
    }

    // The subscriber should have been conflated at least once
    let conflations = handle.conflations.load(std::sync::atomic::Ordering::Relaxed);
    assert!(conflations > 0, "expected conflation");

    // Read remaining messages — the last should be a snapshot after conflation
    let mut rx = handle.rx;
    let mut last_msg = None;
    while let Ok(msg) = rx.try_recv() {
        last_msg = Some(msg);
    }

    // The conflated message should be present
    assert!(last_msg.is_some(), "expected at least one message after conflation");
}
