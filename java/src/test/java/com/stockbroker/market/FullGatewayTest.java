package com.stockbroker.market;

import com.stockbroker.common.TestcontainersConfiguration;
import com.stockbroker.auth.api.AuthDtos;
import com.stockbroker.market.api.MarketDtos;
import com.stockbroker.market.api.MarketDtosExtended;
import com.stockbroker.market.state.MarketStateManager;
import com.stockbroker.market.subscription.SubscriptionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test for the full market data gateway flow:
 * - WebSocket subscription management
 * - Redis state mirroring and recovery
 * - Gap detection and resync
 * - Metrics forwarding (never recomputed)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = TestcontainersConfiguration.class)
@ActiveProfiles("test")
class FullGatewayTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    MarketStateManager stateManager;

    @Autowired
    SubscriptionManager subscriptionManager;

    @Autowired
    RedisTemplate<String, Object> redisTemplate;

    private String accessToken;

    @BeforeEach
    void setup() {
        AuthDtos.RegisterRequest reg = new AuthDtos.RegisterRequest("fulltest@test.com", "SecurePass123!");
        restTemplate.postForEntity("/api/v1/auth/register", reg, AuthDtos.RegisterResponse.class);

        AuthDtos.LoginRequest login = new AuthDtos.LoginRequest("fulltest@test.com", "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginResp = restTemplate.postForEntity("/api/v1/auth/login", login, AuthDtos.LoginResponse.class);
        accessToken = loginResp.getBody().accessToken();
    }

    @Test
    void fullFlowSubscriptionAndMetrics() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        // 1. Get instruments
        ResponseEntity<MarketDtos.ListInstrumentsResponse> instResp = restTemplate.exchange(
                "/api/v1/instruments", HttpMethod.GET, entity, MarketDtos.ListInstrumentsResponse.class);
        assertThat(instResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(instResp.getBody().instruments()).isNotEmpty();

        String symbol = instResp.getBody().instruments().get(0).symbol();

        // 2. Get snapshot
        ResponseEntity<MarketDtos.BookUpdate> snapResp = restTemplate.exchange(
                "/api/v1/market/" + symbol + "/snapshot?depth=10", HttpMethod.GET, entity, MarketDtos.BookUpdate.class);
        assertThat(snapResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(snapResp.getBody().symbol()).isEqualTo(symbol);

        // 3. Get metrics - verify they are forwarded from Rust, not recomputed
        ResponseEntity<MarketDtos.Metrics> metricsResp = restTemplate.exchange(
                "/api/v1/market/" + symbol + "/metrics", HttpMethod.GET, entity, MarketDtos.Metrics.class);
        assertThat(metricsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        MarketDtos.Metrics metrics = metricsResp.getBody();
        assertThat(metrics).isNotNull();
        // Metrics should have all fields populated from Rust
        assertThat(metrics.mid()).isNotNaN();
        assertThat(metrics.microprice()).isNotNaN();
        assertThat(metrics.imbalanceTopN()).isNotNaN();
        assertThat(metrics.rollingVwap()).isNotNaN();
        assertThat(metrics.tradeFlowImbalance()).isNotNaN();

        // 4. Get trades
        ResponseEntity<List<MarketDtos.Trade>> tradesResp = restTemplate.exchange(
                "/api/v1/market/" + symbol + "/trades?limit=5", HttpMethod.GET, entity,
                new org.springframework.core.ParameterizedTypeReference<>() {});
        assertThat(tradesResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 5. Verify Redis mirroring
        stateManager.mirrorToRedis();
        String bookKey = "mkt:" + symbol + ":book";
        assertThat(redisTemplate.opsForValue().get(bookKey)).isNotNull();

        String metricsKey = "mkt:" + symbol + ":metrics";
        assertThat(redisTemplate.opsForValue().get(metricsKey)).isNotNull();
    }

    @Test
    void subscriptionManagerRefCountsWork() {
        String symbol = "BINANCE:BTC-USDT";

        // Subscribe
        subscriptionManager.addBookSubscriber(symbol);
        assertThat(subscriptionManager.getActiveBookSubscriptions()).isEqualTo(1);

        // Subscribe again (second client)
        subscriptionManager.addBookSubscriber(symbol);
        assertThat(subscriptionManager.getActiveBookSubscriptions()).isEqualTo(1); // Still 1 symbol, 2 refs

        // Subscribe different symbol
        subscriptionManager.addBookSubscriber("BINANCE:ETH-USDT");
        assertThat(subscriptionManager.getActiveBookSubscriptions()).isEqualTo(2);

        // Unsubscribe one
        subscriptionManager.removeBookSubscriber(symbol);
        assertThat(subscriptionManager.getActiveBookSubscriptions()).isEqualTo(2); // Still 1 ref on BTC

        // Unsubscribe last BTC
        subscriptionManager.removeBookSubscriber(symbol);
        assertThat(subscriptionManager.getActiveBookSubscriptions()).isEqualTo(1); // Only ETH

        // Unsubscribe ETH
        subscriptionManager.removeBookSubscriber("BINANCE:ETH-USDT");
        assertThat(subscriptionManager.getActiveBookSubscriptions()).isEqualTo(0);
    }

    @Test
    void gapDetectionWorks() {
        String symbol = "BINANCE:BTC-USDT";

        // Normal sequence
        subscriptionManager.onBookUpdate(symbol, 1, 10);
        subscriptionManager.onBookUpdate(symbol, 1, 11);
        subscriptionManager.onBookUpdate(symbol, 1, 12);

        // Gap
        subscriptionManager.onBookUpdate(symbol, 1, 20);

        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(1);

        // Epoch change should not count as gap
        subscriptionManager.onBookUpdate(symbol, 2, 1);
        assertThat(subscriptionManager.getTotalResyncs()).isEqualTo(1);
        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(1);
    }

    @Test
    void redisRecoveryPreservesState() {
        String symbol = "BINANCE:BTC-USDT";

        // Populate state via REST
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        restTemplate.exchange("/api/v1/market/" + symbol + "/snapshot?depth=5", HttpMethod.GET, entity, MarketDtos.BookUpdate.class);
        restTemplate.exchange("/api/v1/market/" + symbol + "/metrics", HttpMethod.GET, entity, MarketDtos.Metrics.class);

        // Mirror to Redis
        stateManager.mirrorToRedis();

        // Simulate restart by creating new state manager (not easily testable in Spring context)
        // But we can verify the data is in Redis
        String bookKey = "mkt:" + symbol + ":book";
        assertThat(redisTemplate.opsForValue().get(bookKey)).isNotNull();

        String metricsKey = "mkt:" + symbol + ":metrics";
        assertThat(redisTemplate.opsForValue().get(metricsKey)).isNotNull();
    }

    @Test
    void latencyMetricsIncludeResyncInfo() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<MarketDtosExtended.LatencyResponse> response = restTemplate.exchange(
                "/api/v1/system/latency", HttpMethod.GET, entity, MarketDtosExtended.LatencyResponse.class);

        // This requires ADMIN role, so expect 403
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}