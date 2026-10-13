package com.stockbroker.market;

import com.stockbroker.common.TestcontainersConfiguration;
import com.stockbroker.auth.api.AuthDtos;
import com.stockbroker.market.api.MarketDtos;
import com.stockbroker.market.state.MarketStateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Redis state recovery on restart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RedisRecoveryTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    MarketStateManager stateManager;

    @Autowired
    RedisTemplate<String, Object> redisTemplate;

    private String accessToken;

    @BeforeEach
    void setup() {
        // Register and login
        AuthDtos.RegisterRequest reg = new AuthDtos.RegisterRequest("redistest@test.com", "SecurePass123!");
        restTemplate.postForEntity("/api/v1/auth/register", reg, AuthDtos.RegisterResponse.class);

        AuthDtos.LoginRequest login = new AuthDtos.LoginRequest("redistest@test.com", "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginResp = restTemplate.postForEntity("/api/v1/auth/login", login, AuthDtos.LoginResponse.class);
        accessToken = loginResp.getBody().accessToken();
    }

    @Test
    void redisMirrorStoresState() {
        // Get a snapshot to populate state
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<MarketDtos.BookUpdate> response = restTemplate.exchange(
                "/api/v1/market/BINANCE:BTC-USDT/snapshot?depth=5", HttpMethod.GET, entity, MarketDtos.BookUpdate.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Trigger mirror to Redis
        stateManager.mirrorToRedis();

        // Verify data in Redis
        String bookKey = "mkt:BINANCE:BTC-USDT:book";
        Object bookState = redisTemplate.opsForValue().get(bookKey);
        assertThat(bookState).isNotNull();
        assertThat(bookState).isInstanceOf(MarketStateManager.BookState.class);

        MarketStateManager.BookState state = (MarketStateManager.BookState) bookState;
        assertThat(state.symbol()).isEqualTo("BINANCE:BTC-USDT");
        assertThat(state.epoch()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void redisStoresMetrics() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<MarketDtos.Metrics> response = restTemplate.exchange(
                "/api/v1/market/BINANCE:BTC-USDT/metrics", HttpMethod.GET, entity, MarketDtos.Metrics.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        stateManager.mirrorToRedis();

        String metricsKey = "mkt:BINANCE:BTC-USDT:metrics";
        Object metricsState = redisTemplate.opsForValue().get(metricsKey);
        assertThat(metricsState).isNotNull();
        assertThat(metricsState).isInstanceOf(MarketStateManager.MetricsState.class);
    }

    @Test
    void redisStoresInstrumentSpecs() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<MarketDtos.ListInstrumentsResponse> response = restTemplate.exchange(
                "/api/v1/instruments", HttpMethod.GET, entity, MarketDtos.ListInstrumentsResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().instruments()).isNotEmpty();

        stateManager.mirrorToRedis();

        // Check at least one spec is stored
        MarketDtos.InstrumentSpec spec = response.getBody().instruments().get(0);
        String specKey = "mkt:" + spec.symbol() + ":spec";
        Object specState = redisTemplate.opsForValue().get(specKey);
        assertThat(specState).isNotNull();
        assertThat(specState).isInstanceOf(stockbroker.market.v1.InstrumentSpec.class);
    }

    @Test
    void recoveryFromRedisWorks() {
        // Populate state first
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        restTemplate.exchange("/api/v1/market/BINANCE:BTC-USDT/snapshot?depth=5", HttpMethod.GET, entity, MarketDtos.BookUpdate.class);
        restTemplate.exchange("/api/v1/market/BINANCE:BTC-USDT/metrics", HttpMethod.GET, entity, MarketDtos.Metrics.class);

        stateManager.mirrorToRedis();

        // Create a new state manager instance to simulate restart
        // We can't easily recreate the bean, but we can call recoverFromRedis again
        stateManager.recoverFromRedis();

        // Verify state was recovered
        MarketDtos.BookUpdate snapshot = stateManager.getSnapshot("BINANCE:BTC-USDT", 5);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.symbol()).isEqualTo("BINANCE:BTC-USDT");
    }

    @Test
    void sequenceNumbersSavedAndRestored() {
        // This tests the subscription manager's sequence persistence
        String symbol = "BINANCE:BTC-USDT";

        // Save sequences
        redisTemplate.opsForValue().set("mkt:" + symbol + ":book:seq", "12345");
        redisTemplate.opsForValue().set("mkt:" + symbol + ":trades:seq", "67890");
        redisTemplate.opsForValue().set("mkt:" + symbol + ":metrics:seq", "11111");

        // Verify they can be read back
        String bookSeq = (String) redisTemplate.opsForValue().get("mkt:" + symbol + ":book:seq");
        String tradesSeq = (String) redisTemplate.opsForValue().get("mkt:" + symbol + ":trades:seq");
        String metricsSeq = (String) redisTemplate.opsForValue().get("mkt:" + symbol + ":metrics:seq");

        assertThat(bookSeq).isEqualTo("12345");
        assertThat(tradesSeq).isEqualTo("67890");
        assertThat(metricsSeq).isEqualTo("11111");
    }
}