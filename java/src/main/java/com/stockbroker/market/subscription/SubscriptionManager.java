package com.stockbroker.market.subscription;

import com.stockbroker.common.config.MarketGatewayProperties;
import com.stockbroker.market.grpc.MarketGrpcClient;
import com.stockbroker.market.state.MarketStateManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages refcounted symbol subscriptions to the Rust feed service.
 *
 * <p>Tracks which symbols have active WebSocket subscribers and only maintains
 * gRPC subscriptions for those symbols. Handles gap detection and automatic
 * resync via GetSnapshot when sequence gaps are detected.
 */
@Component
public class SubscriptionManager {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionManager.class);

    private final MarketGrpcClient grpcClient;
    private final MarketStateManager stateManager;
    private final MeterRegistry meterRegistry;
    private final MarketGatewayProperties.SubscriptionManager config;

    // Refcounted subscriptions per symbol per stream type
    private final ConcurrentHashMap<String, SymbolSubscription> bookSubscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SymbolSubscription> tradesSubscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SymbolSubscription> metricsSubscriptions = new ConcurrentHashMap<>();

    // Pending resync requests
    private final ConcurrentHashMap<String, CompletableFuture<Void>> pendingResyncs = new ConcurrentHashMap<>();

    // Sequence tracking for gap detection
    private final ConcurrentHashMap<String, Long> lastBookSeq = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastTradesSeq = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastMetricsSeq = new ConcurrentHashMap<>();

    // Metrics
    private final Timer resyncTimer;
    private final AtomicLong totalResyncs = new AtomicLong(0);
    private final AtomicLong totalGapsDetected = new AtomicLong(0);

    public SubscriptionManager(@Lazy MarketGrpcClient grpcClient,
                               @Lazy MarketStateManager stateManager,
                               MeterRegistry meterRegistry,
                               MarketGatewayProperties properties) {
        this.grpcClient = grpcClient;
        this.stateManager = stateManager;
        this.meterRegistry = meterRegistry;
        this.config = properties.getSubscriptionManager();

        this.resyncTimer = Timer.builder("gateway.resync.duration")
                .description("Resync via GetSnapshot latency")
                .register(meterRegistry);

        meterRegistry.gauge("gateway.subscriptions.book", bookSubscriptions, map -> map.size());
        meterRegistry.gauge("gateway.subscriptions.trades", tradesSubscriptions, map -> map.size());
        meterRegistry.gauge("gateway.subscriptions.metrics", metricsSubscriptions, map -> map.size());
        meterRegistry.gauge("gateway.resync.pending", pendingResyncs, map -> map.size());
    }

    @PostConstruct
    public void init() {
        // Recovery from Redis will be triggered by MarketDataService after connection
    }

    @PreDestroy
    public void shutdown() {
        // Unsubscribe from all symbols
        bookSubscriptions.keySet().forEach(this::unsubscribeBook);
        tradesSubscriptions.keySet().forEach(this::unsubscribeTrades);
        metricsSubscriptions.keySet().forEach(this::unsubscribeMetrics);
    }

    /**
     * Called when a WebSocket client subscribes to a symbol's book.
     * Increments refcount and starts gRPC subscription if first subscriber.
     */
    public void addBookSubscriber(String symbol) {
        SymbolSubscription sub = bookSubscriptions.compute(symbol, (k, existing) -> {
            if (existing == null) {
                existing = new SymbolSubscription(symbol);
            }
            existing.increment();
            return existing;
        });

        if (sub.getRefCount() == 1) {
            // First subscriber - start gRPC subscription
            subscribeBook(symbol);
        }
    }

    /**
     * Called when a WebSocket client unsubscribes from a symbol's book.
     * Decrements refcount and stops gRPC subscription if last subscriber.
     */
    public void removeBookSubscriber(String symbol) {
        SymbolSubscription sub = bookSubscriptions.get(symbol);
        if (sub != null) {
            int newCount = sub.decrement();
            if (newCount == 0) {
                bookSubscriptions.remove(symbol);
                unsubscribeBook(symbol);
            }
        }
    }

    /**
     * Called when a WebSocket client subscribes to a symbol's trades.
     */
    public void addTradesSubscriber(String symbol) {
        SymbolSubscription sub = tradesSubscriptions.compute(symbol, (k, existing) -> {
            if (existing == null) {
                existing = new SymbolSubscription(symbol);
            }
            existing.increment();
            return existing;
        });

        if (sub.getRefCount() == 1) {
            subscribeTrades(symbol);
        }
    }

    /**
     * Called when a WebSocket client unsubscribes from a symbol's trades.
     */
    public void removeTradesSubscriber(String symbol) {
        SymbolSubscription sub = tradesSubscriptions.get(symbol);
        if (sub != null) {
            int newCount = sub.decrement();
            if (newCount == 0) {
                tradesSubscriptions.remove(symbol);
                unsubscribeTrades(symbol);
            }
        }
    }

    /**
     * Called when a WebSocket client subscribes to a symbol's metrics.
     */
    public void addMetricsSubscriber(String symbol) {
        SymbolSubscription sub = metricsSubscriptions.compute(symbol, (k, existing) -> {
            if (existing == null) {
                existing = new SymbolSubscription(symbol);
            }
            existing.increment();
            return existing;
        });

        if (sub.getRefCount() == 1) {
            subscribeMetrics(symbol);
        }
    }

    /**
     * Called when a WebSocket client unsubscribes from a symbol's metrics.
     */
    public void removeMetricsSubscriber(String symbol) {
        SymbolSubscription sub = metricsSubscriptions.get(symbol);
        if (sub != null) {
            int newCount = sub.decrement();
            if (newCount == 0) {
                metricsSubscriptions.remove(symbol);
                unsubscribeMetrics(symbol);
            }
        }
    }

    /**
     * Handles incoming book update - checks for gaps and triggers resync if needed.
     */
    public void onBookUpdate(String symbol, long epoch, long seq) {
        // Check for epoch change (full resync)
        Long lastEpoch = lastBookSeq.get(symbol);
        if (lastEpoch != null && epoch != lastEpoch) {
            // Epoch changed - this is a resync, not a gap
            log.debug("Book epoch change for {}: {} -> {}", symbol, lastEpoch, epoch);
            totalResyncs.incrementAndGet();
            meterRegistry.counter("gateway.resync.epoch_change").increment();
            lastBookSeq.put(symbol, epoch);
            return;
        }

        // Check for sequence gap
        Long lastSeq = lastBookSeq.get(symbol);
        if (lastSeq != null && seq > lastSeq + 1) {
            long gap = seq - lastSeq - 1;
            log.warn("Book sequence gap detected for {}: expected {}, got {} (gap={})",
                    symbol, lastSeq + 1, seq, gap);
            totalGapsDetected.incrementAndGet();
            meterRegistry.counter("gateway.gaps.detected", "type", "book").increment();
            meterRegistry.counter("gateway.gaps.size", "type", "book").increment(gap);

            // Trigger async resync
            triggerResync(symbol, "book");
        }

        lastBookSeq.put(symbol, seq);
    }

    /**
     * Handles incoming trade - checks for gaps.
     */
    public void onTrade(String symbol, long epoch, long seq) {
        Long lastSeq = lastTradesSeq.get(symbol);
        if (lastSeq != null && seq > lastSeq + 1) {
            long gap = seq - lastSeq - 1;
            log.warn("Trades sequence gap detected for {}: expected {}, got {} (gap={})",
                    symbol, lastSeq + 1, seq, gap);
            totalGapsDetected.incrementAndGet();
            meterRegistry.counter("gateway.gaps.detected", "type", "trades").increment();
            triggerResync(symbol, "trades");
        }
        lastTradesSeq.put(symbol, seq);
    }

    /**
     * Handles incoming metrics - checks for gaps.
     */
    public void onMetrics(String symbol, long epoch, long seq) {
        Long lastSeq = lastMetricsSeq.get(symbol);
        if (lastSeq != null && seq > lastSeq + 1) {
            long gap = seq - lastSeq - 1;
            log.warn("Metrics sequence gap detected for {}: expected {}, got {} (gap={})",
                    symbol, lastSeq + 1, seq, gap);
            totalGapsDetected.incrementAndGet();
            meterRegistry.counter("gateway.gaps.detected", "type", "metrics").increment();
            triggerResync(symbol, "metrics");
        }
        lastMetricsSeq.put(symbol, seq);
    }

    /**
     * Triggers a resync for the given symbol and stream type.
     * Calls GetSnapshot to get a fresh snapshot.
     */
    private void triggerResync(String symbol, String streamType) {
        CompletableFuture<Void> existing = pendingResyncs.get(symbol);
        if (existing != null && !existing.isDone()) {
            return; // Resync already in progress
        }

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                log.info("Requesting resync for {} ({})", symbol, streamType);
                var snapshot = grpcClient.getSnapshot(symbol, config.getMaxMessagesPerSymbol());
                if (snapshot != null) {
                    // Feed the snapshot back into state manager
                    stateManager.onBookUpdate(snapshot);
                    log.info("Resync completed for {} ({})", symbol, streamType);
                } else {
                    log.warn("Resync returned null for {} ({})", symbol, streamType);
                }
            } catch (Exception e) {
                log.error("Resync failed for {} ({}): {}", symbol, streamType, e.getMessage());
                meterRegistry.counter("gateway.resync.failed", "type", streamType).increment();
            } finally {
                sample.stop(resyncTimer);
                pendingResyncs.remove(symbol);
            }
        });

        pendingResyncs.put(symbol, future);
    }

    private void subscribeBook(String symbol) {
        try {
            List<String> symbols = List.of(symbol);
            grpcClient.subscribeBooks(symbols, config.getMaxMessagesPerSymbol());
            log.debug("Subscribed to book for {}", symbol);
        } catch (Exception e) {
            log.error("Failed to subscribe to book for {}: {}", symbol, e.getMessage());
        }
    }

    private void unsubscribeBook(String symbol) {
        // gRPC subscriptions are per-stream, not per-symbol
        // We can't easily unsubscribe a single symbol, so we'd need to resubscribe
        // with the remaining symbols. For now, log and handle on reconnection.
        log.debug("Unsubscribed from book for {} (will resubscribe on reconnect)", symbol);
    }

    private void subscribeTrades(String symbol) {
        try {
            grpcClient.subscribeTrades(List.of(symbol));
            log.debug("Subscribed to trades for {}", symbol);
        } catch (Exception e) {
            log.error("Failed to subscribe to trades for {}: {}", symbol, e.getMessage());
        }
    }

    private void unsubscribeTrades(String symbol) {
        log.debug("Unsubscribed from trades for {} (will resubscribe on reconnect)", symbol);
    }

    private void subscribeMetrics(String symbol) {
        try {
            grpcClient.subscribeMetrics(List.of(symbol));
            log.debug("Subscribed to metrics for {}", symbol);
        } catch (Exception e) {
            log.error("Failed to subscribe to metrics for {}: {}", symbol, e.getMessage());
        }
    }

    private void unsubscribeMetrics(String symbol) {
        log.debug("Unsubscribed from metrics for {} (will resubscribe on reconnect)", symbol);
    }

    /**
     * Re-subscribe to all currently active symbols after reconnection.
     */
    public void resubscribeAll() {
        List<String> bookSymbols = new ArrayList<>(bookSubscriptions.keySet());
        List<String> tradesSymbols = new ArrayList<>(tradesSubscriptions.keySet());
        List<String> metricsSymbols = new ArrayList<>(metricsSubscriptions.keySet());

        if (!bookSymbols.isEmpty()) {
            grpcClient.subscribeBooks(bookSymbols, config.getMaxMessagesPerSymbol());
        }
        if (!tradesSymbols.isEmpty()) {
            grpcClient.subscribeTrades(tradesSymbols);
        }
        if (!metricsSymbols.isEmpty()) {
            grpcClient.subscribeMetrics(metricsSymbols);
        }
        log.info("Resubscribed to {} book, {} trades, {} metrics symbols",
                bookSymbols.size(), tradesSymbols.size(), metricsSymbols.size());
    }

    /**
     * Restore sequence state from Redis after restart.
     */
    public void restoreFromRedis() {
        try {
            // Restore book sequences
            for (String symbol : bookSubscriptions.keySet()) {
                String key = "mkt:" + symbol + ":book:seq";
                String value = (String) stateManager.getRedisTemplate().opsForValue().get(key);
                if (value != null) {
                    lastBookSeq.put(symbol, Long.parseLong(value));
                }
            }
            // Restore trades sequences
            for (String symbol : tradesSubscriptions.keySet()) {
                String key = "mkt:" + symbol + ":trades:seq";
                String value = (String) stateManager.getRedisTemplate().opsForValue().get(key);
                if (value != null) {
                    lastTradesSeq.put(symbol, Long.parseLong(value));
                }
            }
            // Restore metrics sequences
            for (String symbol : metricsSubscriptions.keySet()) {
                String key = "mkt:" + symbol + ":metrics:seq";
                String value = (String) stateManager.getRedisTemplate().opsForValue().get(key);
                if (value != null) {
                    lastMetricsSeq.put(symbol, Long.parseLong(value));
                }
            }
            log.info("Restored sequence state from Redis for {} symbols", lastBookSeq.size());
        } catch (Exception e) {
            log.warn("Failed to restore sequence state from Redis: {}", e.getMessage());
        }
    }

    /**
     * Save sequence state to Redis for recovery.
     */
    public void saveSequencesToRedis() {
        try {
            for (var entry : lastBookSeq.entrySet()) {
                String key = "mkt:" + entry.getKey() + ":book:seq";
                stateManager.getRedisTemplate().opsForValue().set(key, String.valueOf(entry.getValue()));
            }
            for (var entry : lastTradesSeq.entrySet()) {
                String key = "mkt:" + entry.getKey() + ":trades:seq";
                stateManager.getRedisTemplate().opsForValue().set(key, String.valueOf(entry.getValue()));
            }
            for (var entry : lastMetricsSeq.entrySet()) {
                String key = "mkt:" + entry.getKey() + ":metrics:seq";
                stateManager.getRedisTemplate().opsForValue().set(key, String.valueOf(entry.getValue()));
            }
        } catch (Exception e) {
            log.warn("Failed to save sequences to Redis: {}", e.getMessage());
        }
    }

    public long getTotalResyncs() {
        return totalResyncs.get();
    }

    public long getTotalGapsDetected() {
        return totalGapsDetected.get();
    }

    public int getActiveBookSubscriptions() {
        return bookSubscriptions.size();
    }

    public int getActiveTradesSubscriptions() {
        return tradesSubscriptions.size();
    }

    public int getActiveMetricsSubscriptions() {
        return metricsSubscriptions.size();
    }

    static class SymbolSubscription {
        final String symbol;
        private int refCount = 0;

        SymbolSubscription(String symbol) {
            this.symbol = symbol;
        }

        int increment() {
            return ++refCount;
        }

        int decrement() {
            return --refCount;
        }

        int getRefCount() {
            return refCount;
        }
    }
}