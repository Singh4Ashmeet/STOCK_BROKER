package com.stockbroker.common.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitFilterTest {

    @Mock
    private RateLimitProperties rateLimitProperties;

    @Mock
    private Supplier<io.github.bucket4j.Bucket> authRateLimiter;

    @Mock
    private Supplier<io.github.bucket4j.Bucket> marketRateLimiter;

    @Mock
    private Supplier<io.github.bucket4j.Bucket> tradingRateLimiter;

    @Mock
    private Supplier<io.github.bucket4j.Bucket> userRateLimiter;

    @Mock
    private Supplier<io.github.bucket4j.Bucket> ipRateLimiter;

    @Mock
    private io.github.bucket4j.Bucket bucket;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        
        when(rateLimitProperties.isEnabled()).thenReturn(true);
        when(rateLimitProperties.getDefaultRequestsPerMinute()).thenReturn(300);
        
        when(authRateLimiter.get()).thenReturn(bucket);
        when(marketRateLimiter.get()).thenReturn(bucket);
        when(tradingRateLimiter.get()).thenReturn(bucket);
        when(userRateLimiter.get()).thenReturn(bucket);
        when(ipRateLimiter.get()).thenReturn(bucket);
        
        when(bucket.tryConsume(1)).thenReturn(true);

        filter = new RateLimitFilter(
                rateLimitProperties,
                authRateLimiter, marketRateLimiter, tradingRateLimiter,
                userRateLimiter, ipRateLimiter
        );
    }

    @Test
    void shouldAllowRequestWhenUnderLimit() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/market/symbols");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        assertThat(response.getStatus()).isEqualTo(200);
        verify(bucket).tryConsume(1);
    }

    @Test
    void shouldRejectRequestWhenRateLimited() throws Exception {
        // Given
        when(bucket.tryConsume(1)).thenReturn(false);
        
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/market/symbols");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
    }

    @Test
    void shouldUseAuthRateLimiterForAuthEndpoints() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(authRateLimiter).get();
        verify(bucket).tryConsume(1);
    }

    @Test
    void shouldUseMarketRateLimiterForMarketEndpoints() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/market/AAPL/book");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(marketRateLimiter).get();
        verify(bucket).tryConsume(1);
    }

    @Test
    void shouldUseTradingRateLimiterForTradingEndpoints() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/orders/");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(tradingRateLimiter).get();
        verify(bucket).tryConsume(1);
    }

    @Test
    void shouldUseIpRateLimiterForAnonymousRequests() throws Exception {
        // Given - no authentication, request to non-market/trading/auth endpoint
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/unknown");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(ipRateLimiter).get();
        verify(bucket).tryConsume(1);
    }

    @Test
    void shouldSkipRateLimitingWhenDisabled() throws Exception {
        // Given
        when(rateLimitProperties.isEnabled()).thenReturn(false);
        
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/market/symbols");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        assertThat(response.getStatus()).isEqualTo(200);
        verifyNoInteractions(bucket);
    }
}
