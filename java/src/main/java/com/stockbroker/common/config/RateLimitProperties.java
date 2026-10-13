package com.stockbroker.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

/**
 * Configuration properties for rate limiting.
 */
@Configuration
@ConfigurationProperties(prefix = "sb.rate-limit")
@Validated
public class RateLimitProperties {

    /**
     * Default requests per minute for authenticated endpoints.
     */
    @Min(1)
    @Max(10000)
    private int defaultRequestsPerMinute = 300;

    /**
     * Default requests per minute for auth endpoints (stricter).
     */
    @Min(1)
    @Max(1000)
    private int authRequestsPerMinute = 20;

    /**
     * Default requests per minute for market data endpoints.
     */
    @Min(1)
    @Max(5000)
    private int marketRequestsPerMinute = 600;

    /**
     * Default requests per minute for trading endpoints.
     */
    @Min(1)
    @Max(1000)
    private int tradingRequestsPerMinute = 100;

    /**
     * Enable rate limiting.
     */
    private boolean enabled = true;

    /**
     * Key prefix for Redis.
     */
    @NotNull
    private String keyPrefix = "rl";

    public int getDefaultRequestsPerMinute() {
        return defaultRequestsPerMinute;
    }

    public void setDefaultRequestsPerMinute(int defaultRequestsPerMinute) {
        this.defaultRequestsPerMinute = defaultRequestsPerMinute;
    }

    public int getAuthRequestsPerMinute() {
        return authRequestsPerMinute;
    }

    public void setAuthRequestsPerMinute(int authRequestsPerMinute) {
        this.authRequestsPerMinute = authRequestsPerMinute;
    }

    public int getMarketRequestsPerMinute() {
        return marketRequestsPerMinute;
    }

    public void setMarketRequestsPerMinute(int marketRequestsPerMinute) {
        this.marketRequestsPerMinute = marketRequestsPerMinute;
    }

    public int getTradingRequestsPerMinute() {
        return tradingRequestsPerMinute;
    }

    public void setTradingRequestsPerMinute(int tradingRequestsPerMinute) {
        this.tradingRequestsPerMinute = tradingRequestsPerMinute;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}