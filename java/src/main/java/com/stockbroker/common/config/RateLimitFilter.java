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

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
            @Qualifier("authRateLimiter") Supplier<Bucket> authRateLimiter,
            @Qualifier("marketRateLimiter") Supplier<Bucket> marketRateLimiter,
            @Qualifier("tradingRateLimiter") Supplier<Bucket> tradingRateLimiter,
            @Qualifier("userRateLimiter") Supplier<Bucket> userRateLimiter,
            @Qualifier("ipRateLimiter") Supplier<Bucket> ipRateLimiter) {
        this.rateLimitProperties = rateLimitProperties;
        this.authRateLimiter = authRateLimiter;
        this.marketRateLimiter = marketRateLimiter;
        this.tradingRateLimiter = tradingRateLimiter;
        this.userRateLimiter = userRateLimiter;
        this.ipRateLimiter = ipRateLimiter;
    }

    private final Supplier<Bucket> authRateLimiter;
    private final Supplier<Bucket> marketRateLimiter;
    private final Supplier<Bucket> tradingRateLimiter;
    @SuppressWarnings("unused")
    private final Supplier<Bucket> userRateLimiter;
    private final Supplier<Bucket> ipRateLimiter;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        
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

    private Bucket selectRateLimiter(HttpServletRequest request, String path, String method) {
        String pathLower = path.toLowerCase();
        if (pathLower.startsWith("/api/")) {
            pathLower = pathLower.substring(4);
        }
        
        // Auth endpoints - strict rate limiting (always per-IP)
        if (pathLower.startsWith("/v1/auth/")) {
            return bucketFor(request, "auth", authRateLimiter);
        }
        
        // Market data endpoints
        if (pathLower.startsWith("/v1/market/") || pathLower.startsWith("/v1/instruments")) {
            return bucketFor(request, "market", marketRateLimiter);
        }
        
        // Trading endpoints
        if (pathLower.startsWith("/v1/orders/") || 
            pathLower.startsWith("/v1/fills/") ||
            pathLower.startsWith("/v1/portfolio/") ||
            pathLower.startsWith("/v1/ledger/") ||
            pathLower.startsWith("/v1/watchlists/") ||
            pathLower.startsWith("/v1/alerts/") ||
            pathLower.startsWith("/v1/backtests/") ||
            pathLower.startsWith("/v1/strategies/")) {
            return bucketFor(request, "trading", tradingRateLimiter);
        }
        
        // WebSocket ticket
        if (pathLower.startsWith("/v1/ws/ticket")) {
            return bucketFor(request, "ws", tradingRateLimiter);
        }
        
        return bucketFor(request, "default", ipRateLimiter);
    }

    private Bucket bucketFor(HttpServletRequest request, String endpointType, Supplier<Bucket> supplier) {
        // This servlet filter runs before the security chain, so using the
        // authenticated principal here would be unreliable. Keying by the
        // container-provided peer address makes the limiter effective without
        // trusting spoofable forwarded headers. A Redis-backed deployment can
        // replace this with a bounded per-user/per-IP keyspace.
        String remoteAddress = request.getRemoteAddr();
        return buckets.computeIfAbsent(endpointType + ':' + remoteAddress, ignored -> supplier.get());
    }

    private void addRateLimitHeaders(HttpServletResponse response, Bucket bucket) {
        // Standard rate limit headers
        response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimitProperties.getDefaultRequestsPerMinute()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(bucket.getAvailableTokens()));
    }
}
