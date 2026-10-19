package com.stockbroker.market.grpc;

import com.stockbroker.common.config.MarketGatewayProperties;
import com.stockbroker.market.state.MarketStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Instant;

/**
 * Mock gRPC client for testing - generates fake market data.
 * Only active when 'test' profile is active.
 */
@Component
@Profile("test")
@Primary
public class MockMarketGrpcClient extends MarketGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(MockMarketGrpcClient.class);

    private final MarketStateManager stateManager;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong(0);
    private final AtomicLong epoch = new AtomicLong(1);
    private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();

    public MockMarketGrpcClient(MarketGatewayProperties properties, MarketStateManager stateManager) {
        super(properties, stateManager);
        this.stateManager = stateManager;
    }

    @PostConstruct
    public void init() {
        // Don't call parent init - we don't connect to real feed
        log.info("MockMarketGrpcClient initialized for testing");
    }

    @Override
    public void connect() {
        // Simulate successful connection
        log.info("Mock feed connected");
    }

    @Override
    public List<String> listInstruments() {
        return List.of("BINANCE:BTC-USDT", "BINANCE:ETH-USDT", "BINANCE:SOL-USDT");
    }

    @Override
    public void subscribeBooks(List<String> symbols, int maxMessagesPerSymbol) {
        subscribedSymbols.addAll(symbols);
        startMockDataGeneration();
    }

    @Override
    public void subscribeTrades(List<String> symbols) {
        subscribedSymbols.addAll(symbols);
        startMockDataGeneration();
    }

    @Override
    public void subscribeMetrics(List<String> symbols) {
        subscribedSymbols.addAll(symbols);
        startMockDataGeneration();
    }

    private void startMockDataGeneration() {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(this::generateMockBookUpdate, 0, 100, TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(this::generateMockTrade, 0, 500, TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(this::generateMockMetrics, 0, 200, TimeUnit.MILLISECONDS);
            log.info("Started mock data generation for {} symbols", subscribedSymbols.size());
        }
    }

    private void generateMockBookUpdate() {
        if (!running.get() || subscribedSymbols.isEmpty()) return;

        for (String symbol : subscribedSymbols) {
            long seq = sequence.incrementAndGet();
            long currentEpoch = epoch.get();

            // Create mock book update
            var update = createMockBookUpdate(symbol, currentEpoch, seq);
            stateManager.onBookUpdate(update);
        }
    }

    private void generateMockTrade() {
        if (!running.get() || subscribedSymbols.isEmpty()) return;

        for (String symbol : subscribedSymbols) {
            if (Math.random() < 0.3) { // 30% chance per symbol
                var trade = createMockTrade(symbol, epoch.get(), sequence.incrementAndGet());
                stateManager.onTrade(trade);
            }
        }
    }

    private void generateMockMetrics() {
        if (!running.get() || subscribedSymbols.isEmpty()) return;

        for (String symbol : subscribedSymbols) {
            var metrics = createMockMetrics(symbol, epoch.get(), sequence.incrementAndGet());
            stateManager.onMetrics(metrics);
        }
    }

    private com.stockbroker.market.v1.BookUpdate createMockBookUpdate(String symbol, long epoch, long seq) {
        // Create a simple mock book with some bids and asks
        List<com.stockbroker.market.v1.Level> bids = new ArrayList<>();
        List<com.stockbroker.market.v1.Level> asks = new ArrayList<>();

        long basePrice = 50000000000L; // 50000.00000000 in price units (8 decimals)
        long baseQty = 100000000L; // 1.0 in qty units (8 decimals)

        for (int i = 0; i < 10; i++) {
            long priceOffset = (long) (Math.random() * 1000000);
            long qty = baseQty + (long) (Math.random() * 100000000);

            bids.add(com.stockbroker.market.v1.Level.newBuilder()
                    .setPrice(basePrice - priceOffset - i * 100000)
                    .setQty(qty)
                    .build());

            asks.add(com.stockbroker.market.v1.Level.newBuilder()
                    .setPrice(basePrice + priceOffset + i * 100000)
                    .setQty(qty)
                    .build());
        }

        var snapshot = com.stockbroker.market.v1.BookSnapshot.newBuilder()
                .addAllBids(bids)
                .addAllAsks(asks)
                .build();

        return com.stockbroker.market.v1.BookUpdate.newBuilder()
                .setSymbol(symbol)
                .setEpoch(epoch)
                .setSeq(seq)
                .setSnapshot(snapshot)
                .setTiming(com.stockbroker.market.v1.Timing.newBuilder()
                        .setExchangeTsNs(Instant.now().toEpochMilli() * 1_000_000)
                        .setRecvTsNs(Instant.now().toEpochMilli() * 1_000_000)
                        .setPublishTsNs(Instant.now().toEpochMilli() * 1_000_000)
                        .build())
                .build();
    }

    private com.stockbroker.market.v1.Trade createMockTrade(String symbol, long epoch, long seq) {
        boolean isBuy = Math.random() < 0.5;
        long price = 50000000000L + (long) (Math.random() * 1000000);
        long qty = 100000000L + (long) (Math.random() * 100000000);

        return com.stockbroker.market.v1.Trade.newBuilder()
                .setSymbol(symbol)
                .setEpoch(epoch)
                .setSeq(seq)
                .setPrice(price)
                .setQty(qty)
                .setAggressor(isBuy ? com.stockbroker.market.v1.Side.SIDE_BUY : com.stockbroker.market.v1.Side.SIDE_SELL)
                .setVenueTradeId("mock-" + UUID.randomUUID().toString().substring(0, 8))
                .setTiming(com.stockbroker.market.v1.Timing.newBuilder()
                        .setExchangeTsNs(Instant.now().toEpochMilli() * 1_000_000)
                        .setRecvTsNs(Instant.now().toEpochMilli() * 1_000_000)
                        .setPublishTsNs(Instant.now().toEpochMilli() * 1_000_000)
                        .build())
                .build();
    }

    private com.stockbroker.market.v1.Metrics createMockMetrics(String symbol, long epoch, long seq) {
        return com.stockbroker.market.v1.Metrics.newBuilder()
                .setSymbol(symbol)
                .setEpoch(epoch)
                .setSeq(seq)
                .setBestBid(49999000000L)
                .setBestAsk(50001000000L)
                .setBestBidQty(100000000L)
                .setBestAskQty(100000000L)
                .setSpread(2000000L)
                .setMid(50000.0)
                .setMicroprice(50000.0)
                .setImbalanceTopN(0.1)
                .setRollingVwap(50000.0)
                .setTradeFlowImbalance(0.05)
                .addDepth(com.stockbroker.market.v1.DepthPoint.newBuilder()
                        .setLevels(10)
                        .setBidQty(1000000000L)
                        .setAskQty(1000000000L)
                        .build())
                .setBookValid(true)
                .setTiming(com.stockbroker.market.v1.Timing.newBuilder()
                        .setExchangeTsNs(Instant.now().toEpochMilli() * 1_000_000)
                        .setRecvTsNs(Instant.now().toEpochMilli() * 1_000_000)
                        .setPublishTsNs(Instant.now().toEpochMilli() * 1_000_000)
                        .build())
                .build();
    }

    @Override
    public com.stockbroker.market.v1.BookUpdate getSnapshot(String symbol, int depthLevels) {
        return createMockBookUpdate(symbol, epoch.get(), sequence.incrementAndGet());
    }

    @Override
    public com.stockbroker.market.v1.HealthResponse healthCheck() {
        return com.stockbroker.market.v1.HealthResponse.newBuilder()
                .setConnected(true)
                .setBuildVersion("test-mock-1.0")
                .addSymbols(com.stockbroker.market.v1.SymbolHealth.newBuilder()
                        .setSymbol("BINANCE:BTC-USDT")
                        .setBookValid(true)
                        .setEpoch(epoch.get())
                        .setResyncCount(0)
                        .setLastEventAgeMs(100)
                        .build())
                .build();
    }

    @Override
    public void shutdown() {
        running.set(false);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Mock feed shut down");
    }

    @Override
    public boolean isConnected() {
        return running.get();
    }
}
