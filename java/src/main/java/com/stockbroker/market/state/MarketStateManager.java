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

            // Restore instrument specs
            Set<String> keys = redisTemplate.keys("mkt:*:spec");
            if (keys != null) {
                for (String key : keys) {
                    com.stockbroker.market.v1.InstrumentSpec spec = (com.stockbroker.market.v1.InstrumentSpec) redisTemplate.opsForValue().get(key);
                    if (spec != null) {
                        instrumentSpecs.put(spec.getSymbol(), spec);
                    }
                }
            }

            // Restore books
            keys = redisTemplate.keys("mkt:*:book");
            if (keys != null) {
                for (String key : keys) {
                    String symbol = key.substring(5, key.length() - 5); // "mkt:" + ":book"
                    BookState state = (BookState) redisTemplate.opsForValue().get(key);
                    if (state != null) {
                        books.computeIfAbsent(symbol, k -> new AtomicReference<>()).set(state);
                        log.debug("Restored book for {} from Redis (epoch={}, seq={})", symbol, state.epoch(), state.seq());
                    }
                }
            }

            // Restore trades
            keys = redisTemplate.keys("mkt:*:trades");
            if (keys != null) {
                for (String key : keys) {
                    String symbol = key.substring(5, key.length() - 7); // "mkt:" + ":trades"
                    TradesState state = (TradesState) redisTemplate.opsForValue().get(key);
                    if (state != null) {
                        trades.computeIfAbsent(symbol, k -> new AtomicReference<>()).set(state);
                        log.debug("Restored trades for {} from Redis ({} trades)", symbol, state.recentTrades().size());
                    }
                }
            }

            // Restore metrics
            keys = redisTemplate.keys("mkt:*:metrics");
            if (keys != null) {
                for (String key : keys) {
                    String symbol = key.substring(5, key.length() - 8); // "mkt:" + ":metrics"
                    MetricsState state = (MetricsState) redisTemplate.opsForValue().get(key);
                    if (state != null) {
                        metrics.computeIfAbsent(symbol, k -> new AtomicReference<>()).set(state);
                        log.debug("Restored metrics for {} from Redis", symbol);
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
        // Mirror to Redis
        if (redisMirrorConfig.isEnabled()) {
            String key = "mkt:" + spec.getSymbol() + ":spec";
            redisTemplate.opsForValue().set(key, spec);
        }
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
        List<MarketDtos.Level> bids = update.getSnapshot() != null ?
                update.getSnapshot().getBidsList().stream()
                        .map(l -> new MarketDtos.Level(String.valueOf(l.getPrice()), String.valueOf(l.getQty())))
                        .toList() :
                update.getDelta().getBidsList().stream()
                        .map(l -> new MarketDtos.Level(String.valueOf(l.getPrice()), String.valueOf(l.getQty())))
                        .toList();

        List<MarketDtos.Level> asks = update.getSnapshot() != null ?
                update.getSnapshot().getAsksList().stream()
                        .map(l -> new MarketDtos.Level(String.valueOf(l.getPrice()), String.valueOf(l.getQty())))
                        .toList() :
                update.getDelta().getAsksList().stream()
                        .map(l -> new MarketDtos.Level(String.valueOf(l.getPrice()), String.valueOf(l.getQty())))
                        .toList();

        boolean isSnapshot = update.getPayloadCase() == com.stockbroker.market.v1.BookUpdate.PayloadCase.SNAPSHOT;
        boolean bookValid = isSnapshot || update.getEpoch() > 0;

        return new BookState(
                update.getSymbol(),
                update.getEpoch(),
                update.getSeq(),
                convertTiming(update.getTiming()),
                new MarketDtos.BookSnapshot(bids, asks),
                isSnapshot ? null : new MarketDtos.BookDelta(bids, asks),
                bookValid
        );
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
                .map(spec -> new MarketDtos.InstrumentSpec(
                        spec.getSymbol(), spec.getVenue(), spec.getBaseAsset(), spec.getQuoteAsset(),
                        spec.getPriceScale(), spec.getQtyScale(),
                        String.valueOf(spec.getTickSize()), String.valueOf(spec.getLotSize()),
                        spec.getMinNotional() > 0 ? String.valueOf(spec.getMinNotional()) : null,
                        spec.getPriceScale() + spec.getQtyScale() <= 18
                ))
                .toList();
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
            // Mirror instrument specs
            for (var entry : instrumentSpecs.entrySet()) {
                String key = "mkt:" + entry.getKey() + ":spec";
                redisTemplate.opsForValue().set(key, entry.getValue());
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
