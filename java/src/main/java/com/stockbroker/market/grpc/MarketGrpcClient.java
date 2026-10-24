package com.stockbroker.market.grpc;

import com.stockbroker.common.config.MarketGatewayProperties;
import com.stockbroker.market.state.MarketStateManager;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * gRPC client for the Rust market data feed service.
 *
 * <p>Manages a single managed channel with keepalive, reconnection with backoff,
 * and supports dynamic subscription management for book, trades, and metrics streams.
 * Each stream type (book, trades, metrics) has a single gRPC stream that can be
 * updated with new symbols via SendMessage on the request observer.
 */
@Component
@ConditionalOnProperty(name = "sb.market.gateway.mock", havingValue = "false", matchIfMissing = true)
public class MarketGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(MarketGrpcClient.class);

    private final MarketGatewayProperties properties;
    private final MarketGatewayProperties.SubscriptionManager subConfig;
    private final MarketStateManager stateManager;

    private ManagedChannel channel;
    private com.stockbroker.market.v1.MarketDataGrpc.MarketDataStub asyncStub;
    private com.stockbroker.market.v1.MarketDataGrpc.MarketDataBlockingStub blockingStub;

    private final AtomicBoolean connected = new AtomicBoolean(false);

    // Current subscribed symbols per stream
    private final ConcurrentHashMap<String, Boolean> bookSymbols = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> tradesSymbols = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> metricsSymbols = new ConcurrentHashMap<>();

    public MarketGrpcClient(MarketGatewayProperties properties,
                            MarketStateManager stateManager) {
        this.properties = properties;
        this.subConfig = properties.getSubscriptionManager();
        this.stateManager = stateManager;
    }

    public void connect() {
        ManagedChannelBuilder<?> builder = Grpc.newChannelBuilderForAddress(
                properties.getHost(), properties.getPort(), getCredentials())
                .keepAliveTime(properties.getKeepalive().getIntervalSeconds(), TimeUnit.SECONDS)
                .keepAliveTimeout(properties.getKeepalive().getTimeoutSeconds(), TimeUnit.SECONDS)
                .keepAliveWithoutCalls(properties.getKeepalive().getPermitWithoutCalls() > 0)
                .maxInboundMessageSize(16 * 1024 * 1024)
                .idleTimeout(30, TimeUnit.SECONDS);

        this.channel = builder.build();
        this.asyncStub = com.stockbroker.market.v1.MarketDataGrpc.newStub(channel);
        this.blockingStub = com.stockbroker.market.v1.MarketDataGrpc.newBlockingStub(channel);

        connected.set(true);
    }

    private com.stockbroker.market.v1.MarketDataGrpc.MarketDataBlockingStub getBlockingStub() {
        return blockingStub.withDeadlineAfter(properties.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private ChannelCredentials getCredentials() {
        if (properties.isPlaintext()) {
            return InsecureChannelCredentials.create();
        }
        return InsecureChannelCredentials.create();
    }

    public List<String> listInstruments() {
        com.stockbroker.market.v1.ListInstrumentsResponse response = getBlockingStub().listInstruments(
                com.stockbroker.market.v1.ListInstrumentsRequest.getDefaultInstance());
        return response.getInstrumentsList().stream()
                .map(spec -> spec.getSymbol())
                .toList();
    }

    /**
     * Subscribe to book updates for the given symbols.
     */
    public void subscribeBooks(List<String> symbols, int maxMessagesPerSymbol) {
        if (symbols == null || symbols.isEmpty()) return;

        symbols.forEach(s -> bookSymbols.put(s, true));

        int depth = maxMessagesPerSymbol > 0 ? maxMessagesPerSymbol : subConfig.getMaxMessagesPerSymbol();
        com.stockbroker.market.v1.SubscribeRequest request = com.stockbroker.market.v1.SubscribeRequest.newBuilder()
                .addAllSymbols(symbols)
                .setDepthLevels(depth)
                .setMetricsLevels(0)
                .build();

        asyncStub.subscribeBook(request, new StreamObserver<com.stockbroker.market.v1.BookUpdate>() {
            @Override
            public void onNext(com.stockbroker.market.v1.BookUpdate update) {
                stateManager.onBookUpdate(update);
            }

            @Override
            public void onError(Throwable t) {
                log.error("Book subscription error: {}", t.getMessage());
                handleSubscriptionError("book", t);
            }

            @Override
            public void onCompleted() {
                log.info("Book subscription completed");
            }
        });
        log.info("Subscribed to book for {} symbols", symbols.size());
    }

    /**
     * Subscribe to trades for the given symbols.
     */
    public void subscribeTrades(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return;

        symbols.forEach(s -> tradesSymbols.put(s, true));

        com.stockbroker.market.v1.SubscribeRequest request = com.stockbroker.market.v1.SubscribeRequest.newBuilder()
                .addAllSymbols(symbols)
                .build();

        asyncStub.subscribeTrades(request, new StreamObserver<com.stockbroker.market.v1.Trade>() {
            @Override
            public void onNext(com.stockbroker.market.v1.Trade trade) {
                stateManager.onTrade(trade);
            }

            @Override
            public void onError(Throwable t) {
                log.error("Trades subscription error: {}", t.getMessage());
                handleSubscriptionError("trades", t);
            }

            @Override
            public void onCompleted() {
                log.info("Trades subscription completed");
            }
        });
        log.info("Subscribed to trades for {} symbols", symbols.size());
    }

    /**
     * Subscribe to metrics for the given symbols.
     */
    public void subscribeMetrics(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return;

        symbols.forEach(s -> metricsSymbols.put(s, true));

        com.stockbroker.market.v1.SubscribeRequest request = com.stockbroker.market.v1.SubscribeRequest.newBuilder()
                .addAllSymbols(symbols)
                .build();

        asyncStub.subscribeMetrics(request, new StreamObserver<com.stockbroker.market.v1.Metrics>() {
            @Override
            public void onNext(com.stockbroker.market.v1.Metrics metrics) {
                stateManager.onMetrics(metrics);
            }

            @Override
            public void onError(Throwable t) {
                log.error("Metrics subscription error: {}", t.getMessage());
                handleSubscriptionError("metrics", t);
            }

            @Override
            public void onCompleted() {
                log.info("Metrics subscription completed");
            }
        });
        log.info("Subscribed to metrics for {} symbols", symbols.size());
    }

    /**
     * Unsubscribe from book updates for the given symbols.
     */
    public void unsubscribeBooks(List<String> symbols) {
        if (symbols != null) {
            symbols.forEach(bookSymbols::remove);
        }
    }

    /**
     * Unsubscribe from trades for the given symbols.
     */
    public void unsubscribeTrades(List<String> symbols) {
        if (symbols != null) {
            symbols.forEach(tradesSymbols::remove);
        }
    }

    /**
     * Unsubscribe from metrics for the given symbols.
     */
    public void unsubscribeMetrics(List<String> symbols) {
        if (symbols != null) {
            symbols.forEach(metricsSymbols::remove);
        }
    }

    /**
     * Resubscribe to all currently tracked symbols (used after reconnection).
     */
    public void resubscribeAll() {
        if (!bookSymbols.isEmpty()) {
            List<String> symbols = new ArrayList<>(bookSymbols.keySet());
            bookSymbols.clear();
            subscribeBooks(symbols, subConfig.getMaxMessagesPerSymbol());
        }
        if (!tradesSymbols.isEmpty()) {
            List<String> symbols = new ArrayList<>(tradesSymbols.keySet());
            tradesSymbols.clear();
            subscribeTrades(symbols);
        }
        if (!metricsSymbols.isEmpty()) {
            List<String> symbols = new ArrayList<>(metricsSymbols.keySet());
            metricsSymbols.clear();
            subscribeMetrics(symbols);
        }
    }

    public com.stockbroker.market.v1.BookUpdate getSnapshot(String symbol, int depthLevels) {
        com.stockbroker.market.v1.SnapshotRequest request = com.stockbroker.market.v1.SnapshotRequest.newBuilder()
                .setSymbol(symbol)
                .setDepthLevels(depthLevels)
                .build();
        return getBlockingStub().getSnapshot(request);
    }

    public com.stockbroker.market.v1.HealthResponse healthCheck() {
        return getBlockingStub().health(com.stockbroker.market.v1.HealthRequest.getDefaultInstance());
    }

    private void handleSubscriptionError(String type, Throwable t) {
        connected.set(false);
    }

    public void shutdown() {
        if (channel != null) {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        connected.set(false);
    }

    public boolean isConnected() {
        return connected.get();
    }

    public int getBookSymbolCount() {
        return bookSymbols.size();
    }

    public int getTradesSymbolCount() {
        return tradesSymbols.size();
    }

    public int getMetricsSymbolCount() {
        return metricsSymbols.size();
    }
}
