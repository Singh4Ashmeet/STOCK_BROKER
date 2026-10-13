package com.stockbroker.common.config;

import io.micrometer.common.lang.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;

/**
 * Configuration properties for the Market Data Gateway (gRPC client).
 */
@Configuration
@ConfigurationProperties(prefix = "sb.market.gateway")
@Validated
public class MarketGatewayProperties {

    /**
     * Host of the Rust feed service.
     */
    @NotBlank
    private String host = "localhost";

    /**
     * Port of the Rust feed service gRPC endpoint.
     */
    @Positive
    private int port = 9000;

    /**
     * Whether to use plaintext (insecure) connection.
     */
    private boolean plaintext = true;

    /**
     * Connection timeout.
     */
    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Keepalive settings.
     */
    @NotNull
    private Keepalive keepalive = new Keepalive();

    /**
     * Subscription manager settings.
     */
    @NotNull
    private SubscriptionManager subscriptionManager = new SubscriptionManager();

    /**
     * Redis mirror settings.
     */
    @NotNull
    private RedisMirror redisMirror = new RedisMirror();

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isPlaintext() {
        return plaintext;
    }

    public void setPlaintext(boolean plaintext) {
        this.plaintext = plaintext;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Keepalive getKeepalive() {
        return keepalive;
    }

    public void setKeepalive(Keepalive keepalive) {
        this.keepalive = keepalive;
    }

    public SubscriptionManager getSubscriptionManager() {
        return subscriptionManager;
    }

    public void setSubscriptionManager(SubscriptionManager subscriptionManager) {
        this.subscriptionManager = subscriptionManager;
    }

    public RedisMirror getRedisMirror() {
        return redisMirror;
    }

    public void setRedisMirror(RedisMirror redisMirror) {
        this.redisMirror = redisMirror;
    }

    public static class Keepalive {
        @Positive
        private long intervalSeconds = 20;

        @Positive
        private long timeoutSeconds = 10;

        @Positive
        private long permitWithoutCalls = 1;

        public long getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(long intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }

        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public long getPermitWithoutCalls() {
            return permitWithoutCalls;
        }

        public void setPermitWithoutCalls(long permitWithoutCalls) {
            this.permitWithoutCalls = permitWithoutCalls;
        }
    }

    public static class SubscriptionManager {
        @Positive
        private int maxSymbolsPerConnection = 100;

        @Positive
        private int maxMessagesPerSymbol = 1024;

        @Positive
        private int resubscribeBackoffMs = 1000;

        @Positive
        private int maxResubscribeBackoffMs = 30000;

        public int getMaxSymbolsPerConnection() {
            return maxSymbolsPerConnection;
        }

        public void setMaxSymbolsPerConnection(int maxSymbolsPerConnection) {
            this.maxSymbolsPerConnection = maxSymbolsPerConnection;
        }

        public int getMaxMessagesPerSymbol() {
            return maxMessagesPerSymbol;
        }

        public void setMaxMessagesPerSymbol(int maxMessagesPerSymbol) {
            this.maxMessagesPerSymbol = maxMessagesPerSymbol;
        }

        public int getResubscribeBackoffMs() {
            return resubscribeBackoffMs;
        }

        public void setResubscribeBackoffMs(int resubscribeBackoffMs) {
            this.resubscribeBackoffMs = resubscribeBackoffMs;
        }

        public int getMaxResubscribeBackoffMs() {
            return maxResubscribeBackoffMs;
        }

        public void setMaxResubscribeBackoffMs(int maxResubscribeBackoffMs) {
            this.maxResubscribeBackoffMs = maxResubscribeBackoffMs;
        }
    }

    public static class RedisMirror {
        private boolean enabled = true;

        @Positive
        private int throttleMs = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getThrottleMs() {
            return throttleMs;
        }

        public void setThrottleMs(int throttleMs) {
            this.throttleMs = throttleMs;
        }
    }
}