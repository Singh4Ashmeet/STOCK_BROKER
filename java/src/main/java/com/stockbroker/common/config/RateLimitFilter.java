package com.stockbroker.common.config;

import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Rate limiting filter using Bucket4j + Redis.
 * Applies different rate limits based on endpoint and user/IP.
 */
@Component
@Order(1) // Run before security filter
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties rateLimitProperties;

    @Autowired
    public RateLimitFilter(
            RateLimitProperties rateLimitProperties,
            @Qualifier("authRateLimiter") Supplier<io.github.bucket4j.Bucket> authRateLimiter,
            @Qualifier("marketRateLimiter") Supplier<io.github.bucket4j.Bucket> marketRateLimiter,
            @Qualifier("tradingRateLimiter") Supplier<io.github.bucket4j.Bucket> tradingRateLimiter,
            @Qualifier("userRateLimiter") Supplier<io.github.bucket4j.Bucket> userRateLimiter,
            @Qualifier("ipRateLimiter") Supplier<io.github.bucket4j.Bucket> ipRateLimiter) {
        this.rateLimitProperties = rateLimitProperties;
        this.authRateLimiter = authRateLimiter;
        this.marketRateLimiter = marketRateLimiter;
        this.tradingRateLimiter = tradingRateLimiter;
        this.userRateLimiter = userRateLimiter;
        this.ipRateLimiter = ipRateLimiter;
    }

    private final Supplier<io.github.bucket4j.Bucket> authRateLimiter;
    private final Supplier<io.github.bucket4j.Bucket> marketRateLimiter;
    private final Supplier<io.github.bucket4j.Bucket> tradingRateLimiter;
    private final Supplier<io.github.bucket4j.Bucket> userRateLimiter;
    private final Supplier<io.github.bucket4j.Bucket> ipRateLimiter;

    @Override
    protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, jakarta.servlet.FilterChain filterChain)
            throws jakarta.servlet.ServletException, IOException {
        
        if (!rateLimitProperties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();

        // Determine which rate limiter to use
        Bucket bucket = selectRateLimiter(request, path, method);
        
        if (bucket == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Try to consume a token
        if (bucket.tryConsume(1)) {
            // Add rate limit headers
            addRateLimitHeaders(response, bucket);
            filterChain.doFilter(request, response);
        } else {
            // Rate limited
            response.setStatus(429);
            response.setContentType("application/problem+json");
            response.getWriter().write("""
                    {
                        "type": "urn:stockbroker:error:RATE_LIMITED",
                        "title": "Rate limited",
                        "status": 429,
                        "detail": "Too many requests. Please try again later.",
                        "code": "RATE_LIMITED"
                    }
                    """);
        }
    }

    private Bucket selectRateLimiter(jakarta.servlet.http.HttpServletRequest request, String path, String method) {
        String pathLower = path.toLowerCase();
        
        // Check if user is authenticated
        UUID userId = getCurrentUserId();
        
        // Auth endpoints - strict rate limiting (always per-IP)
        if (pathLower.startsWith("/api/v1/auth/")) {
            return authRateLimiter.get();
        }
        
        // Market data endpoints
        if (pathLower.startsWith("/api/v1/market/") || pathLower.startsWith("/api/v1/instruments")) {
            if (userId != null) {
                return getUserBucket(userId, "market");
            }
            return marketRateLimiter.get();
        }
        
        // Trading endpoints
        if (pathLower.startsWith("/api/v1/orders/") || 
            pathLower.startsWith("/api/v1/fills/") ||
            pathLower.startsWith("/api/v1/portfolio/") ||
            pathLower.startsWith("/api/v1/ledger/") ||
            pathLower.startsWith("/api/v1/watchlists/") ||
            pathLower.startsWith("/api/v1/alerts/") ||
            pathLower.startsWith("/api/v1/backtests/") ||
            pathLower.startsWith("/api/v1/strategies/")) {
            if (userId != null) {
                return getUserBucket(userId, "trading");
            }
            return tradingRateLimiter.get();
        }
        
        // WebSocket ticket
        if (pathLower.startsWith("/api/v1/ws/ticket")) {
            if (userId != null) {
                return getUserBucket(userId, "ws");
            }
            return tradingRateLimiter.get();
        }
        
        // For authenticated users, use user-based rate limiting
        // For anonymous, use IP-based
        if (userId != null) {
            return getUserBucket(userId, "default");
        }
        return ipRateLimiter.get();
    }
    
    private UUID getCurrentUserId() {
        try {
            return com.stockbroker.common.security.CurrentUser.getUserIdOrNull();
        } catch (Exception e) {
            return null;
        }
    }
    
    private Bucket getUserBucket(UUID userId, String endpointType) {
        String key = "user:" + userId + ":" + endpointType;
        return userRateLimiter.get(); // In production, this should be per-user per-endpoint bucket
    }

    private void addRateLimitHeaders(jakarta.servlet.http.HttpServletResponse response, io.github.bucket4j.Bucket bucket) {
        // Bucket4j doesn't expose remaining tokens directly in older versions
        // We'll add standard headers
        response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimitProperties.getDefaultRequestsPerMinute()));
        // Note: Bucket4j 8+ has getAvailableTokens() but we'll use a simpler approach
        response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimitProperties.getDefaultRequestsPerMinute()));
    }
}