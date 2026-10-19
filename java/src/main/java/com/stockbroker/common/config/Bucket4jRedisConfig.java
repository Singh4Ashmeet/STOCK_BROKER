package com.stockbroker.common.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Bucket4j configuration for rate limiting.
 * Provides in-memory buckets for tests and Redis-backed buckets for production.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(prefix = "sb.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Bucket4jRedisConfig {

    private final RateLimitProperties rateLimitProperties;

    @Autowired
    public Bucket4jRedisConfig(RateLimitProperties rateLimitProperties) {
        this.rateLimitProperties = rateLimitProperties;
    }

    /**
     * In-memory bucket supplier for per-user rate limiting.
     * Used when Redis is not available (e.g., tests).
     */
    @Bean
    @Qualifier("userRateLimiter")
    @ConditionalOnMissingBean(name = "redisProxyManager")
    @Profile({"test", "default", "dev"})
    public Supplier<Bucket> userRateLimiter() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(rateLimitProperties.getDefaultRequestsPerMinute())
                .refillGreedy(rateLimitProperties.getDefaultRequestsPerMinute(), Duration.ofMinutes(1))
                .build();
        return () -> Bucket.builder().addLimit(limit).build();
    }

    /**
     * In-memory bucket supplier for per-IP rate limiting.
     * Used when Redis is not available (e.g., tests).
     */
    @Bean
    @Qualifier("ipRateLimiter")
    @ConditionalOnMissingBean(name = "redisProxyManager")
    @Profile({"test", "default", "dev"})
    public Supplier<Bucket> ipRateLimiter() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(rateLimitProperties.getDefaultRequestsPerMinute())
                .refillGreedy(rateLimitProperties.getDefaultRequestsPerMinute(), Duration.ofMinutes(1))
                .build();
        return () -> Bucket.builder().addLimit(limit).build();
    }

    /**
     * In-memory bucket supplier for auth endpoints.
     * Used when Redis is not available (e.g., tests).
     */
    @Bean
    @Qualifier("authRateLimiter")
    @ConditionalOnMissingBean(name = "redisProxyManager")
    @Profile({"test", "default", "dev"})
    public Supplier<Bucket> authRateLimiter() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(rateLimitProperties.getAuthRequestsPerMinute())
                .refillGreedy(rateLimitProperties.getAuthRequestsPerMinute(), Duration.ofMinutes(1))
                .build();
        return () -> Bucket.builder().addLimit(limit).build();
    }

    /**
     * In-memory bucket supplier for market data endpoints.
     * Used when Redis is not available (e.g., tests).
     */
    @Bean
    @Qualifier("marketRateLimiter")
    @ConditionalOnMissingBean(name = "redisProxyManager")
    @Profile({"test", "default", "dev"})
    public Supplier<Bucket> marketRateLimiter() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(rateLimitProperties.getMarketRequestsPerMinute())
                .refillGreedy(rateLimitProperties.getMarketRequestsPerMinute(), Duration.ofMinutes(1))
                .build();
        return () -> Bucket.builder().addLimit(limit).build();
    }

    /**
     * In-memory bucket supplier for trading endpoints.
     * Used when Redis is not available (e.g., tests).
     */
    @Bean
    @Qualifier("tradingRateLimiter")
    @ConditionalOnMissingBean(name = "redisProxyManager")
    @Profile({"test", "default", "dev"})
    public Supplier<Bucket> tradingRateLimiter() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(rateLimitProperties.getTradingRequestsPerMinute())
                .refillGreedy(rateLimitProperties.getTradingRequestsPerMinute(), Duration.ofMinutes(1))
                .build();
        return () -> Bucket.builder().addLimit(limit).build();
    }

    /**
     * Redis-backed bucket configuration for production.
     * Only activated when Redis is available and not in test profile.
     */
    @Configuration
    @Profile("!test")
    @ConditionalOnProperty(prefix = "spring.data.redis", name = "host")
    static class RedisBucketConfig {
        
        // Redis-backed bucket suppliers would be defined here
        // For now, we rely on the in-memory buckets above
        // In production, you would configure Bucket4j Redis integration here
    }
}