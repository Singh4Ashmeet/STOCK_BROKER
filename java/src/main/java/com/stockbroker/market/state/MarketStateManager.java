package com.stockbroker.market.state;

import com.stockbroker.common.config.MarketGatewayProperties;
import com.stockbroker.common.web.MarketWebSocketHandler;
import com.stockbroker.market.api.MarketDtos;
import com.stockbroker.market.api.MarketDtosExtended;
import com.stockbroker.market.subscription.SubscriptionManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages in-memory market data state with atomic snapshots.
 *
 * <p>Provides:
 * <ul>
 *   <li>Thread-safe access to latest book, trades, metrics per symbol</li>
 *   <li>Atomic snapshot swapping via AtomicReference</li>
 *   <li>Redis mirroring for horizontal scaling and restart recovery</li>
 *   <li>Latency metrics for each processing stage</li>
 *   <li>WebSocket broadcasting for real-time fan-out</li>
 * </ul>
 */
@Component
public class MarketStateManager {

    private static final Logger log = LoggerFactory.getLogger(MarketStateManager.class);

    private final MarketGatewayProperties.RedisMirror redisMirrorConfig;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;
    private final MarketWebSocketHandler webSocketHandler;
    private final SubscriptionManager subscriptionManager;

    // In-memory state
    private final ConcurrentHashMap<String, AtomicReference<BookState>> books = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicReference<TradesState>> trades = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicReference<MetricsState>> metrics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, com.stockbroker.market.v1.InstrumentSpec> instrumentSpecs = new ConcurrentHashMap<>();

    // Latency metrics
    private final Timer bookUpdateTimer;
    private final Timer tradeUpdateTimer;
    private final Timer metricsUpdateTimer;
    private final Timer redisMirrorTimer;

    // Resync tracking
    private final AtomicLong resyncCount = new AtomicLong(0);
    private final AtomicLong droppedMessages = new AtomicLong(0);

    // Recovery state
    private volatile boolean recoveredFromRedis = false;

    public MarketStateManager(MarketGatewayProperties properties,
                              RedisTemplate<String, Object> redisTemplate,
                              MeterRegistry meterRegistry,
                              @Lazy MarketWebSocketHandler webSocketHandler,
                              SubscriptionManager subscriptionManager) {
        this.redisMirrorConfig = properties.getRedisMirror();
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.webSocketHandler = webSocketHandler;
        this.subscriptionManager = subscriptionManager;

        this.bookUpdateTimer = Timer.builder("gateway.stage.book_update")
                .description("Book update processing latency")
                .register(meterRegistry);
        this.tradeUpdateTimer = Timer.builder("gateway.stage.trade_update")
                .description("Trade update processing latency")
                .register(meterRegistry);
        this.metricsUpdateTimer = Timer.builder("gateway.stage.metrics_update")
                .description("Metrics update processing latency")
                .register(meterRegistry);
        this.redisMirrorTimer = Timer.builder("gateway.stage.redis_mirror")
                .description("Redis mirror latency")
                .register(meterRegistry);
    }

    @PostConstruct
    public void init() {
        meterRegistry.gauge("gateway.subscriptions.active", this, mgr -> mgr.countActiveSubscriptions());
    }

    /**
     * Called after connection to feed is established.
     * Restores state from Redis mirror if available.
     */
    public void recoverFromRedis() {
        if (recoveredFromRedis) {
            return;
        }

        try {
            log.info("Recovering market state from Redis...");

            // Restore instrument specs (stored as DTOs for JSON Redis serialization)
            Set<String> keys = redisTemplate.keys("mkt:*:spec");
            if (keys != null) {
                for (String key : keys) {
                    Object raw = redisTemplate.opsForValue().get(key);
                    com.stockbroker.market.v1.InstrumentSpec spec = coerceInstrumentSpec(raw);
                    if (spec != null) {
                        instrumentSpecs.put(spec.getSymbol(), spec);
                    }
                }
            }

            // Restore books
            keys = redisTemplate.keys("mkt:*:book");
            if (keys != null) {
                for (String key : keys) {
                    Optional<String> symbol = symbolFromStateKey(key, ":book");
                    if (symbol.isEmpty()) {
                        log.warn("Skipping malformed Redis book-state key: {}", key);
                        continue;
                    }
                    BookState state = (BookState) redisTemplate.opsForValue().get(key);
                    if (state != null) {
                        books.computeIfAbsent(symbol.get(), k -> new AtomicReference<>()).set(state);
                        log.debug("Restored book for {} from Redis (epoch={}, seq={})", symbol.get(), state.epoch(), state.seq());
                    }
                }
            }

            // Restore trades
            keys = redisTemplate.keys("mkt:*:trades");
            if (keys != null) {
                for (String key : keys) {
                    Optional<String> symbol = symbolFromStateKey(key, ":trades");
                    if (symbol.isEmpty()) {
                        log.warn("Skipping malformed Redis trades-state key: {}", key);
                        continue;
                    }
                    TradesState state = (TradesState) redisTemplate.opsForValue().get(key);
                    if (state != null) {
                        trades.computeIfAbsent(symbol.get(), k -> new AtomicReference<>()).set(state);
                        log.debug("Restored trades for {} from Redis ({} trades)", symbol.get(), state.recentTrades().size());
                    }
                }
            }

            // Restore metrics
            keys = redisTemplate.keys("mkt:*:metrics");
            if (keys != null) {
                for (String key : keys) {
                    Optional<String> symbol = symbolFromStateKey(key, ":metrics");
                    if (symbol.isEmpty()) {
                        log.warn("Skipping malformed Redis metrics-state key: {}", key);
                        continue;
                    }
                    MetricsState state = (MetricsState) redisTemplate.opsForValue().get(key);
                    if (state != null) {
                        metrics.computeIfAbsent(symbol.get(), k -> new AtomicReference<>()).set(state);
                        log.debug("Restored metrics for {} from Redis", symbol.get());
                    }
                }
            }

            // Restore sequence state in subscription manager
            subscriptionManager.restoreFromRedis();

            recoveredFromRedis = true;
            log.info("Market state recovery from Redis completed");
        } catch (Exception e) {
            log.warn("Failed to recover market state from Redis: {}", e.getMessage());
        }
    }

    public void updateInstrumentSpec(com.stockbroker.market.v1.InstrumentSpec spec) {
        instrumentSpecs.put(spec.getSymbol(), spec);
        // Mirror a Jackson-friendly DTO (protobuf messages are not JSON-serializable).
        if (redisMirrorConfig.isEnabled()) {
            String key = "mkt:" + spec.getSymbol() + ":spec";
            redisTemplate.opsForValue().set(key, toInstrumentDto(spec));
        }
    }

    static Optional<String> symbolFromStateKey(String key, String suffix) {
        String prefix = "mkt:";
        if (key == null || !key.startsWith(prefix) || !key.endsWith(suffix)) {
            return Optional.empty();
        }
        String symbol = key.substring(prefix.length(), key.length() - suffix.length());
        return symbol.isBlank() ? Optional.empty() : Optional.of(symbol);
    }

    public void onBookUpdate(com.stockbroker.market.v1.BookUpdate update) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String symbol = update.getSymbol();
            BookState newState = convertBookUpdate(update);
            books.computeIfAbsent(symbol, k -> new AtomicReference<>()).set(newState);

            // Update metrics
            meterRegistry.gauge("gateway.book.epoch", newState, s -> s.epoch());
            meterRegistry.gauge("gateway.book.seq", newState, s -> s.seq());
            meterRegistry.gauge("gateway.book.valid", newState, s -> s.bookValid() ? 1 : 0);

            // Notify subscription manager for gap detection
            subscriptionManager.onBookUpdate(symbol, update.getEpoch(), update.getSeq());

            // Check for epoch change (resync)
            if (update.getEpoch() > 0 && newState.epoch() != update.getEpoch()) {
                resyncCount.incrementAndGet();
                meterRegistry.counter("gateway.resync.total").increment();
            }

            // Broadcast to WebSocket subscribers
            webSocketHandler.broadcastBookUpdate(symbol, toBookUpdateDto(newState));

        } finally {
            sample.stop(bookUpdateTimer);
        }
    }

    public void onTrade(com.stockbroker.market.v1.Trade trade) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String symbol = trade.getSymbol();
            TradesState current = trades.computeIfAbsent(symbol, k -> new AtomicReference<>(new TradesState())).get();
            TradesState updated = current.addTrade(trade);
            trades.get(symbol).set(updated);

            // Notify subscription manager for gap detection
            subscriptionManager.onTrade(symbol, trade.getEpoch(), trade.getSeq());

            // Broadcast to WebSocket subscribers
            webSocketHandler.broadcastTrade(symbol, toTradeDto(trade));

        } finally {
            sample.stop(tradeUpdateTimer);
        }
    }

    public void onMetrics(com.stockbroker.market.v1.Metrics metricsProto) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String symbol = metricsProto.getSymbol();
            MetricsState newState = convertMetrics(metricsProto);
            metrics.computeIfAbsent(symbol, k -> new AtomicReference<>()).set(newState);

            // Notify subscription manager for gap detection
            subscriptionManager.onMetrics(symbol, metricsProto.getEpoch(), metricsProto.getSeq());

            // Broadcast to WebSocket subscribers
            webSocketHandler.broadcastMetrics(symbol, toMetricsDto(newState));

        } finally {
            sample.stop(metricsUpdateTimer);
        }
    }

    private BookState convertBookUpdate(com.stockbroker.market.v1.BookUpdate update) {
        // Protobuf message getters never return null; use the oneof payload case.
        boolean isSnapshot = update.getPayloadCase() == com.stockbroker.market.v1.BookUpdate.PayloadCase.SNAPSHOT;
        List<MarketDtos.Level> bids;
        List<MarketDtos.Level> asks;
        if (isSnapshot) {
            bids = update.getSnapshot().getBidsList().stream()
                    .map(l -> new MarketDtos.Level(String.valueOf(l.getPrice()), String.valueOf(l.getQty())))
                    .toList();
            asks = update.getSnapshot().getAsksList().stream()
                    .map(l -> new MarketDtos.Level(String.valueOf(l.getPrice()), String.valueOf(l.getQty())))
                    .toList();
        } else {
            bids = update.getDelta().getBidsList().stream()
                    .map(l -> new MarketDtos.Level(String.valueOf(l.getPrice()), String.valueOf(l.getQty())))
                    .toList();
            asks = update.getDelta().getAsksList().stream()
                    .map(l -> new MarketDtos.Level(String.valueOf(l.getPrice()), String.valueOf(l.getQty())))
                    .toList();
        }

        boolean bookValid = isSnapshot || update.getEpoch() > 0;

        // Deltas replace only the levels they carry; merge onto the prior snapshot when present
        // so REST/trading still see a full top-of-book after incremental updates.
        MarketDtos.BookSnapshot snapshot;
        MarketDtos.BookDelta delta;
        if (isSnapshot) {
            snapshot = new MarketDtos.BookSnapshot(bids, asks);
            delta = null;
        } else {
            delta = new MarketDtos.BookDelta(bids, asks);
            AtomicReference<BookState> existing = books.get(update.getSymbol());
            BookState prior = existing != null ? existing.get() : null;
            if (prior != null && prior.snapshot() != null) {
                snapshot = mergeDeltaIntoSnapshot(prior.snapshot(), bids, asks);
            } else {
                snapshot = new MarketDtos.BookSnapshot(bids, asks);
            }
        }

        return new BookState(
                update.getSymbol(),
                update.getEpoch(),
                update.getSeq(),
                convertTiming(update.getTiming()),
                snapshot,
                delta,
                bookValid
        );
    }

    /**
     * Applies price-level updates onto an existing snapshot. Qty {@code 0} removes a level;
     * otherwise the level is inserted or replaced and the book is kept price-ordered.
     */
    private MarketDtos.BookSnapshot mergeDeltaIntoSnapshot(
            MarketDtos.BookSnapshot prior,
            List<MarketDtos.Level> bidUpdates,
            List<MarketDtos.Level> askUpdates) {
        java.util.TreeMap<java.math.BigDecimal, String> bids = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        java.util.TreeMap<java.math.BigDecimal, String> asks = new java.util.TreeMap<>();
        for (MarketDtos.Level level : prior.bids()) {
            bids.put(new java.math.BigDecimal(level.price()), level.qty());
        }
        for (MarketDtos.Level level : prior.asks()) {
            asks.put(new java.math.BigDecimal(level.price()), level.qty());
        }
        applyLevelUpdates(bids, bidUpdates);
        applyLevelUpdates(asks, askUpdates);
        List<MarketDtos.Level> mergedBids = bids.entrySet().stream()
                .map(e -> new MarketDtos.Level(e.getKey().toPlainString(), e.getValue()))
                .toList();
        List<MarketDtos.Level> mergedAsks = asks.entrySet().stream()
                .map(e -> new MarketDtos.Level(e.getKey().toPlainString(), e.getValue()))
                .toList();
        return new MarketDtos.BookSnapshot(mergedBids, mergedAsks);
    }

    private void applyLevelUpdates(
            java.util.NavigableMap<java.math.BigDecimal, String> bookSide,
            List<MarketDtos.Level> updates) {
        for (MarketDtos.Level level : updates) {
            java.math.BigDecimal price = new java.math.BigDecimal(level.price());
            java.math.BigDecimal qty = new java.math.BigDecimal(level.qty());
            if (qty.compareTo(java.math.BigDecimal.ZERO) == 0) {
                bookSide.remove(price);
            } else {
                bookSide.put(price, level.qty());
            }
        }
    }

    private MetricsState convertMetrics(com.stockbroker.market.v1.Metrics m) {
        List<MarketDtos.DepthPoint> depth = m.getDepthList().stream()
                .map(d -> new MarketDtos.DepthPoint(d.getLevels(), String.valueOf(d.getBidQty()), String.valueOf(d.getAskQty())))
                .toList();

        return new MetricsState(
                m.getSymbol(),
                m.getEpoch(),
                m.getSeq(),
                convertTiming(m.getTiming()),
                String.valueOf(m.getBestBid()),
                String.valueOf(m.getBestAsk()),
                String.valueOf(m.getBestBidQty()),
                String.valueOf(m.getBestAskQty()),
                String.valueOf(m.getSpread()),
                m.getMid(),
                m.getMicroprice(),
                m.getImbalanceTopN(),
                m.getRollingVwap(),
                m.getTradeFlowImbalance(),
                depth,
                m.getBookValid()
        );
    }

    private MarketDtos.Timing convertTiming(com.stockbroker.market.v1.Timing t) {
        return new MarketDtos.Timing(
                String.valueOf(t.getExchangeTsNs()),
                String.valueOf(t.getRecvTsNs()),
                String.valueOf(t.getPublishTsNs()),
                String.valueOf(Instant.now().getNano())
        );
    }

    private MarketDtos.BookUpdate toBookUpdateDto(BookState state) {
        return new MarketDtos.BookUpdate(
                state.symbol(),
                state.epoch(),
                state.seq(),
                state.timing(),
                state.snapshot(),
                state.delta(),
                state.bookValid()
        );
    }

    private MarketDtos.Trade toTradeDto(com.stockbroker.market.v1.Trade trade) {
        return new MarketDtos.Trade(
                trade.getSymbol(), trade.getEpoch(), trade.getSeq(), convertTiming(trade.getTiming()),
                String.valueOf(trade.getPrice()), String.valueOf(trade.getQty()),
                trade.getAggressor().name(), trade.getVenueTradeId()
        );
    }

    private MarketDtos.Metrics toMetricsDto(MetricsState state) {
        return new MarketDtos.Metrics(
                state.symbol(), state.epoch(), state.seq(), state.timing(),
                state.bestBid(), state.bestAsk(), state.bestBidQty(), state.bestAskQty(),
                state.spread(), state.mid(), state.microprice(), state.imbalanceTopN(),
                state.rollingVwap(), state.tradeFlowImbalance(), state.depth(), state.bookValid()
        );
    }

    public MarketDtos.BookUpdate getSnapshot(String symbol, int depth) {
        AtomicReference<BookState> ref = books.get(symbol);
        if (ref == null) {
            return null;
        }
        BookState state = ref.get();
        if (state == null) {
            return null;
        }
        List<MarketDtos.Level> bids = state.snapshot().bids().stream().limit(depth).toList();
        List<MarketDtos.Level> asks = state.snapshot().asks().stream().limit(depth).toList();
        return new MarketDtos.BookUpdate(
                state.symbol(),
                state.epoch(),
                state.seq(),
                state.timing(),
                new MarketDtos.BookSnapshot(bids, asks),
                state.delta(),
                state.bookValid()
        );
    }

    public MarketDtos.Metrics getMetrics(String symbol) {
        AtomicReference<MetricsState> ref = metrics.get(symbol);
        if (ref == null) return null;
        MetricsState state = ref.get();
        if (state == null) return null;
        return new MarketDtos.Metrics(
                state.symbol(), state.epoch(), state.seq(), state.timing(),
                state.bestBid(), state.bestAsk(), state.bestBidQty(), state.bestAskQty(),
                state.spread(), state.mid(), state.microprice(), state.imbalanceTopN(),
                state.rollingVwap(), state.tradeFlowImbalance(), state.depth(), state.bookValid()
        );
    }

    public List<MarketDtos.Trade> getRecentTrades(String symbol, int limit) {
        AtomicReference<TradesState> ref = trades.get(symbol);
        if (ref == null) return List.of();
        return ref.get().getRecentTrades(limit);
    }

    public List<MarketDtos.InstrumentSpec> getAllInstruments() {
        return instrumentSpecs.values().stream()
                .map(this::toInstrumentDto)
                .toList();
    }

    private MarketDtos.InstrumentSpec toInstrumentDto(com.stockbroker.market.v1.InstrumentSpec spec) {
        return new MarketDtos.InstrumentSpec(
                spec.getSymbol(), spec.getVenue(), spec.getBaseAsset(), spec.getQuoteAsset(),
                spec.getPriceScale(), spec.getQtyScale(),
                String.valueOf(spec.getTickSize()), String.valueOf(spec.getLotSize()),
                spec.getMinNotional() > 0 ? String.valueOf(spec.getMinNotional()) : null,
                spec.getPriceScale() + spec.getQtyScale() <= 18
        );
    }

    private com.stockbroker.market.v1.InstrumentSpec coerceInstrumentSpec(Object raw) {
        if (raw instanceof com.stockbroker.market.v1.InstrumentSpec spec) {
            return spec;
        }
        if (raw instanceof MarketDtos.InstrumentSpec dto) {
            return com.stockbroker.market.v1.InstrumentSpec.newBuilder()
                    .setSymbol(dto.symbol())
                    .setVenue(dto.venue() != null ? dto.venue() : "")
                    .setBaseAsset(dto.baseAsset() != null ? dto.baseAsset() : "")
                    .setQuoteAsset(dto.quoteAsset() != null ? dto.quoteAsset() : "")
                    .setPriceScale(dto.priceScale())
                    .setQtyScale(dto.qtyScale())
                    .setTickSize(parseLongOrZero(dto.tickSize()))
                    .setLotSize(parseLongOrZero(dto.lotSize()))
                    .setMinNotional(parseLongOrZero(dto.minNotional()))
                    .build();
        }
        return null;
    }

    private long parseLongOrZero(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return new java.math.BigDecimal(value).longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Periodically mirror latest state to Redis for restart recovery.
     * Also saves sequence numbers for gap detection recovery.
     */
    public void mirrorToRedis() {
        if (!redisMirrorConfig.isEnabled()) return;

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Mirror books
            for (var entry : books.entrySet()) {
                String key = "mkt:" + entry.getKey() + ":book";
                redisTemplate.opsForValue().set(key, entry.getValue().get());
            }
            // Mirror trades
            for (var entry : trades.entrySet()) {
                String key = "mkt:" + entry.getKey() + ":trades";
                redisTemplate.opsForValue().set(key, entry.getValue().get());
            }
            // Mirror metrics
            for (var entry : metrics.entrySet()) {
                String key = "mkt:" + entry.getKey() + ":metrics";
                redisTemplate.opsForValue().set(key, entry.getValue().get());
            }
            // Mirror instrument specs as DTOs (protobuf is not Jackson-friendly)
            for (var entry : instrumentSpecs.entrySet()) {
                String key = "mkt:" + entry.getKey() + ":spec";
                redisTemplate.opsForValue().set(key, toInstrumentDto(entry.getValue()));
            }

            // Save sequence state for gap detection recovery
            subscriptionManager.saveSequencesToRedis();

        } catch (Exception e) {
            log.warn("Failed to mirror to Redis: {}", e.getMessage());
        } finally {
            sample.stop(redisMirrorTimer);
        }
    }

    public MarketDtosExtended.LatencyResponse getLatencyMetrics() {
        List<MarketDtosExtended.LatencyStage> stages = List.of(
                new MarketDtosExtended.LatencyStage("book_update",
                        bookUpdateTimer.mean(TimeUnit.MILLISECONDS),
                        bookUpdateTimer.max(TimeUnit.MILLISECONDS),
                        0, 0, bookUpdateTimer.count()),
                new MarketDtosExtended.LatencyStage("trade_update",
                        tradeUpdateTimer.mean(TimeUnit.MILLISECONDS),
                        tradeUpdateTimer.max(TimeUnit.MILLISECONDS),
                        0, 0, tradeUpdateTimer.count()),
                new MarketDtosExtended.LatencyStage("metrics_update",
                        metricsUpdateTimer.mean(TimeUnit.MILLISECONDS),
                        metricsUpdateTimer.max(TimeUnit.MILLISECONDS),
                        0, 0, metricsUpdateTimer.count()),
                new MarketDtosExtended.LatencyStage("redis_mirror",
                        redisMirrorTimer.mean(TimeUnit.MILLISECONDS),
                        redisMirrorTimer.max(TimeUnit.MILLISECONDS),
                        0, 0, redisMirrorTimer.count())
        );

        return new MarketDtosExtended.LatencyResponse(
                stages,
                countActiveSubscriptions(),
                resyncCount.get(),
                droppedMessages.get()
        );
    }

    private int countActiveSubscriptions() {
        return books.size() + trades.size() + metrics.size();
    }

    public RedisTemplate<String, Object> getRedisTemplate() {
        return redisTemplate;
    }

    public boolean isRecoveredFromRedis() {
        return recoveredFromRedis;
    }

    // State records
    public record BookState(
            String symbol,
            long epoch,
            long seq,
            MarketDtos.Timing timing,
            MarketDtos.BookSnapshot snapshot,
            MarketDtos.BookDelta delta,
            boolean bookValid
    ) {}

    public record TradesState(
            Deque<MarketDtos.Trade> recentTrades
    ) {
        TradesState() {
            this(new ArrayDeque<>(1000));
        }

        TradesState addTrade(com.stockbroker.market.v1.Trade trade) {
            TradesState newState = new TradesState(new ArrayDeque<>(this.recentTrades));
            newState.recentTrades.addFirst(convertTrade(trade));
            while (newState.recentTrades.size() > 1000) {
                newState.recentTrades.removeLast();
            }
            return newState;
        }

        List<MarketDtos.Trade> getRecentTrades(int limit) {
            return recentTrades.stream().limit(limit).toList();
        }

        private MarketDtos.Trade convertTrade(com.stockbroker.market.v1.Trade t) {
            return new MarketDtos.Trade(
                    t.getSymbol(), t.getEpoch(), t.getSeq(), convertTiming(t.getTiming()),
                    String.valueOf(t.getPrice()), String.valueOf(t.getQty()),
                    t.getAggressor().name(), t.getVenueTradeId()
            );
        }

        private MarketDtos.Timing convertTiming(com.stockbroker.market.v1.Timing t) {
            return new MarketDtos.Timing(
                    String.valueOf(t.getExchangeTsNs()),
                    String.valueOf(t.getRecvTsNs()),
                    String.valueOf(t.getPublishTsNs()),
                    String.valueOf(Instant.now().getNano())
            );
        }
    }

    public record MetricsState(
            String symbol,
            long epoch,
            long seq,
            MarketDtos.Timing timing,
            String bestBid,
            String bestAsk,
            String bestBidQty,
            String bestAskQty,
            String spread,
            double mid,
            double microprice,
            double imbalanceTopN,
            double rollingVwap,
            double tradeFlowImbalance,
            List<MarketDtos.DepthPoint> depth,
            boolean bookValid
    ) {}
}
