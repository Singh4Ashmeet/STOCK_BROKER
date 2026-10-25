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
    private final AtomicLong epoch = new AtomicLong(1);
    private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();
    // Per-symbol sequence counters to avoid gaps in SubscriptionManager
    private final ConcurrentHashMap<String, AtomicLong> bookSequences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> tradeSequences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> metricsSequences = new ConcurrentHashMap<>();

    public MockMarketGrpcClient(MarketGatewayProperties properties, MarketStateManager stateManager) {
        super(properties, stateManager);
        this.stateManager = stateManager;
    }

    @PostConstruct
    public void init() {
        log.info("MockMarketGrpcClient initialized for testing");
        List<String> instruments = listInstruments();
        subscribedSymbols.addAll(instruments);
        for (String symbol : instruments) {
            stateManager.updateInstrumentSpec(createInstrumentSpec(symbol));
            long bookSeq = bookSequences.computeIfAbsent(symbol, k -> new AtomicLong(0)).incrementAndGet();
            long metricsSeq = metricsSequences.computeIfAbsent(symbol, k -> new AtomicLong(0)).incrementAndGet();
            stateManager.onBookUpdate(createMockBookUpdate(symbol, epoch.get(), bookSeq));
            stateManager.onMetrics(createMockMetrics(symbol, epoch.get(), metricsSeq));
        }
        startMockDataGeneration();
    }

    private com.stockbroker.market.v1.InstrumentSpec createInstrumentSpec(String symbol) {
        String[] parts = symbol.split(":");
        String venue = parts.length > 0 ? parts[0] : "BINANCE";
        String pair = parts.length > 1 ? parts[1] : "BTC-USDT";
        String[] assets = pair.split("-");
        return com.stockbroker.market.v1.InstrumentSpec.newBuilder()
                .setSymbol(symbol)
                .setVenue(venue)
                .setBaseAsset(assets.length > 0 ? assets[0] : "BTC")
                .setQuoteAsset(assets.length > 1 ? assets[1] : "USDT")
                .setPriceScale(8)
                .setQtyScale(8)
                .setTickSize(1)
                .setLotSize(1)
                .setMinNotional(0)
                .build();
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
        for (String symbol : symbols) {
            // Reset snapshot flag for new subscribers so they get a fresh snapshot
            hasSentSnapshot.put(symbol, new AtomicBoolean(false));
        }
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

    private final ConcurrentHashMap<String, AtomicBoolean> hasSentSnapshot = new ConcurrentHashMap<>();

    private void generateMockBookUpdate() {
        if (!running.get() || subscribedSymbols.isEmpty()) return;

        for (String symbol : subscribedSymbols) {
            long seq = bookSequences.computeIfAbsent(symbol, k -> new AtomicLong(0)).incrementAndGet();
            long currentEpoch = epoch.get();

            boolean sentSnap = hasSentSnapshot.computeIfAbsent(symbol, k -> new AtomicBoolean(false)).getAndSet(true);
            var update = !sentSnap
                    ? createMockBookUpdate(symbol, currentEpoch, seq)
                    : createMockBookDelta(symbol, currentEpoch, seq);

            stateManager.onBookUpdate(update);
        }
    }

    private com.stockbroker.market.v1.BookUpdate createMockBookDelta(String symbol, long epoch, long seq) {
        List<com.stockbroker.market.v1.Level> bids = new ArrayList<>();
        List<com.stockbroker.market.v1.Level> asks = new ArrayList<>();

        long basePrice = 50000000000L;
        long qty = 100000000L + (long) (Math.random() * 50000000);

        bids.add(com.stockbroker.market.v1.Level.newBuilder()
                .setPrice(basePrice - 10000)
                .setQty(qty)
                .build());

        asks.add(com.stockbroker.market.v1.Level.newBuilder()
                .setPrice(basePrice + 10000)
                .setQty(qty)
                .build());

        var delta = com.stockbroker.market.v1.BookDelta.newBuilder()
                .addAllBids(bids)
                .addAllAsks(asks)
                .build();

        return com.stockbroker.market.v1.BookUpdate.newBuilder()
                .setSymbol(symbol)
                .setEpoch(epoch)
                .setSeq(seq)
                .setDelta(delta)
                .setTiming(com.stockbroker.market.v1.Timing.newBuilder()
                        .setExchangeTsNs(Instant.now().toEpochMilli() * 1_000_000)
                        .setRecvTsNs(Instant.now().toEpochMilli() * 1_000_000)
                        .setPublishTsNs(Instant.now().toEpochMilli() * 1_000_000)
                        .build())
                .build();
    }

    private void generateMockTrade() {
        if (!running.get() || subscribedSymbols.isEmpty()) return;

        for (String symbol : subscribedSymbols) {
            if (Math.random() < 0.3) { // 30% chance per symbol
                long seq = tradeSequences.computeIfAbsent(symbol, k -> new AtomicLong(0)).incrementAndGet();
                var trade = createMockTrade(symbol, epoch.get(), seq);
                stateManager.onTrade(trade);
            }
        }
    }

    private void generateMockMetrics() {
        if (!running.get() || subscribedSymbols.isEmpty()) return;

        for (String symbol : subscribedSymbols) {
            long seq = metricsSequences.computeIfAbsent(symbol, k -> new AtomicLong(0)).incrementAndGet();
            var metrics = createMockMetrics(symbol, epoch.get(), seq);
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
        long seq = bookSequences.computeIfAbsent(symbol, k -> new AtomicLong(0)).incrementAndGet();
        return createMockBookUpdate(symbol, epoch.get(), seq);
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

    /**
     * Reset mock feed state for test isolation.
     * Clears per-symbol snapshot flags and sequence counters.
     */
    public void resetForTest() {
        log.debug("Resetting mock feed state for test isolation");
        hasSentSnapshot.clear();
        bookSequences.clear();
        tradeSequences.clear();
        metricsSequences.clear();
        subscribedSymbols.clear();
        epoch.set(1);
        running.set(false);
        // Re-initialize with default instruments
        init();
    }

    @Override
    public boolean isConnected() {
        return running.get();
    }
}
