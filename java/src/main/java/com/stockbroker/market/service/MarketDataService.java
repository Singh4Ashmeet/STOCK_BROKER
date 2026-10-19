package com.stockbroker.market.service;

import com.stockbroker.common.config.MarketGatewayProperties;
import com.stockbroker.market.api.MarketDtos;
import com.stockbroker.market.api.MarketDtosExtended;
import com.stockbroker.market.grpc.MarketGrpcClient;
import com.stockbroker.market.state.MarketStateManager;
import com.stockbroker.market.subscription.SubscriptionManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Market data service that manages the gRPC connection to the Rust feed service
 * and provides market data to REST endpoints and WebSocket fan-out.
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final MarketGatewayProperties properties;
    private final MarketGrpcClient grpcClient;
    private final MarketStateManager stateManager;
    private final SubscriptionManager subscriptionManager;
    private final MeterRegistry meterRegistry;
    private final ScheduledExecutorService scheduler;

    private Timer connectionTimer;
    private volatile boolean initialConnection = true;

    public MarketDataService(MarketGatewayProperties properties,
                             MarketGrpcClient grpcClient,
                             MarketStateManager stateManager,
                             SubscriptionManager subscriptionManager,
                             MeterRegistry meterRegistry) {
        this.properties = properties;
        this.grpcClient = grpcClient;
        this.stateManager = stateManager;
        this.subscriptionManager = subscriptionManager;
        this.meterRegistry = meterRegistry;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "market-data-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void init() {
        connectionTimer = Timer.builder("gateway.connection.duration")
                .description("Time to establish gRPC connection to feed service")
                .register(meterRegistry);

        // Start connection to feed service
        connectWithBackoff();

        // Schedule periodic health checks
        scheduler.scheduleAtFixedRate(
                this::healthCheck,
                30, 30, TimeUnit.SECONDS
        );

        // Schedule Redis mirror if enabled
        if (properties.getRedisMirror().isEnabled()) {
            scheduler.scheduleAtFixedRate(
                    this::mirrorToRedis,
                    properties.getRedisMirror().getThrottleMs(),
                    properties.getRedisMirror().getThrottleMs(),
                    TimeUnit.MILLISECONDS
            );
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        grpcClient.shutdown();
    }

    private void connectWithBackoff() {
        CompletableFuture.runAsync(() -> {
            int backoffMs = properties.getSubscriptionManager().getResubscribeBackoffMs();
            int maxBackoffMs = properties.getSubscriptionManager().getMaxResubscribeBackoffMs();

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Timer.Sample sample = Timer.start(meterRegistry);
                    grpcClient.connect();
                    sample.stop(connectionTimer);

                    log.info("Connected to market data feed at {}:{}",
                            properties.getHost(), properties.getPort());

                    // Recover state from Redis on first connection
                    if (initialConnection) {
                        stateManager.recoverFromRedis();
                        initialConnection = false;
                    }

                    // Resubscribe to all active symbols
                    subscriptionManager.resubscribeAll();

                    break; // Success, exit retry loop

                } catch (Exception e) {
                    log.warn("Failed to connect to feed service, retrying in {}ms: {}",
                            backoffMs, e.getMessage());

                    meterRegistry.counter("gateway.connection.failures").increment();

                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    // Exponential backoff with cap
                    backoffMs = Math.min(backoffMs * 2, maxBackoffMs);
                }
            }
        });
    }

    private void healthCheck() {
        try {
            var health = grpcClient.healthCheck();
            meterRegistry.gauge("gateway.health.connected", health.getConnected() ? 1 : 0);
            meterRegistry.gauge("gateway.health.symbols", health.getSymbolsCount());
        } catch (Exception e) {
            log.debug("Health check failed: {}", e.getMessage());
            meterRegistry.gauge("gateway.health.connected", 0);
        }
    }

    private void mirrorToRedis() {
        stateManager.mirrorToRedis();
    }

    // REST API methods

    public List<MarketDtos.InstrumentSpec> getAllInstruments() {
        return stateManager.getAllInstruments();
    }

    public MarketDtos.BookUpdate getSnapshot(String symbol, int depth) {
        return stateManager.getSnapshot(symbol, depth);
    }

    public MarketDtos.Metrics getMetrics(String symbol) {
        return stateManager.getMetrics(symbol);
    }

    public List<MarketDtos.Trade> getRecentTrades(String symbol, int limit) {
        return stateManager.getRecentTrades(symbol, limit);
    }

    public MarketDtosExtended.LatencyResponse getLatencyMetrics() {
        return stateManager.getLatencyMetrics();
    }

    // For WebSocket fan-out
    public MarketStateManager getStateManager() {
        return stateManager;
    }

    public SubscriptionManager getSubscriptionManager() {
        return subscriptionManager;
    }
}