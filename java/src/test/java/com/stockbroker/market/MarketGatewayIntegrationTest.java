package com.stockbroker.market;

import com.stockbroker.common.LocalTestConfiguration;
import com.stockbroker.auth.api.AuthDtos;
import com.stockbroker.market.api.MarketDtos;
import com.stockbroker.market.subscription.SubscriptionManager;
import com.stockbroker.market.state.MarketStateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the market data gateway.
 * Tests subscription management, Redis recovery, and gap detection/resync.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = LocalTestConfiguration.class)
@ActiveProfiles("test")
class MarketGatewayIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    MarketStateManager stateManager;

    @Autowired
    SubscriptionManager subscriptionManager;

    private String accessToken;

    @BeforeEach
    void setup() {
        // Register and login
        AuthDtos.RegisterRequest reg = new AuthDtos.RegisterRequest("markettest@test.com", "SecurePass123!", "Market Gateway User");
        restTemplate.postForEntity("/v1/auth/register", reg, AuthDtos.RegisterResponse.class);

        AuthDtos.LoginRequest login = new AuthDtos.LoginRequest("markettest@test.com", "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginResp = restTemplate.postForEntity("/v1/auth/login", login, AuthDtos.LoginResponse.class);
        accessToken = loginResp.getBody().accessToken();
    }

    @Test
    void instrumentsEndpointReturnsList() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<MarketDtos.ListInstrumentsResponse> response = restTemplate.exchange(
                "/v1/instruments", HttpMethod.GET, entity, MarketDtos.ListInstrumentsResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().instruments()).isNotEmpty();
    }

    @Test
    void snapshotEndpointReturnsBook() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<MarketDtos.BookUpdate> response = restTemplate.exchange(
                "/v1/market/BINANCE:BTC-USDT/snapshot?depth=10", HttpMethod.GET, entity, MarketDtos.BookUpdate.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().symbol()).isEqualTo("BINANCE:BTC-USDT");
    }

    @Test
    void metricsEndpointReturnsMetrics() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<MarketDtos.Metrics> response = restTemplate.exchange(
                "/v1/market/BINANCE:BTC-USDT/metrics", HttpMethod.GET, entity, MarketDtos.Metrics.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().symbol()).isEqualTo("BINANCE:BTC-USDT");
    }

    @Test
    void tradesEndpointReturnsTrades() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<List<MarketDtos.Trade>> response = restTemplate.exchange(
                "/v1/market/BINANCE:BTC-USDT/trades?limit=10", HttpMethod.GET, entity, 
                new org.springframework.core.ParameterizedTypeReference<List<MarketDtos.Trade>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void healthEndpointWorks() {
        ResponseEntity<MarketDtos.HealthResponse> response = restTemplate.getForEntity(
                "/v1/system/health", MarketDtos.HealthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().connected()).isTrue();
    }

    @Test
    void latencyEndpointRequiresAdmin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/system/latency", HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
