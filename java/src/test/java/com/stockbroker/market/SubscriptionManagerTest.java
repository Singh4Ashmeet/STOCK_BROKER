package com.stockbroker.market;

import com.stockbroker.common.LocalTestConfiguration;
import com.stockbroker.auth.api.AuthDtos;
import com.stockbroker.market.subscription.SubscriptionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Tests for SubscriptionManager - refcounted subscriptions, gap detection, and resync.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = LocalTestConfiguration.class)
@ActiveProfiles("test")
class SubscriptionManagerTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    SubscriptionManager subscriptionManager;

    private String accessToken;
    private String ticket;

    @BeforeEach
    void setup() {
        // Register and login
        AuthDtos.RegisterRequest reg = new AuthDtos.RegisterRequest("subtest@test.com", "SecurePass123!");
        restTemplate.postForEntity("/api/v1/auth/register", reg, AuthDtos.RegisterResponse.class);

        AuthDtos.LoginRequest login = new AuthDtos.LoginRequest("subtest@test.com", "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginResp = restTemplate.postForEntity("/api/v1/auth/login", login, AuthDtos.LoginResponse.class);
        accessToken = loginResp.getBody().accessToken();

        // Get WebSocket ticket
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        ResponseEntity<AuthDtos.WsTicketResponse> ticketResp = restTemplate.exchange(
                "/api/v1/auth/ws/ticket", HttpMethod.POST, entity, AuthDtos.WsTicketResponse.class);
        ticket = ticketResp.getBody().ticket();
    }

    @Test
    void subscriptionManagerTracksRefCounts() {
        // Initially no subscriptions
        assertThat(subscriptionManager.getActiveBookSubscriptions()).isEqualTo(0);
        assertThat(subscriptionManager.getActiveTradesSubscriptions()).isEqualTo(0);
        assertThat(subscriptionManager.getActiveMetricsSubscriptions()).isEqualTo(0);

        // Add subscribers
        subscriptionManager.addBookSubscriber("BINANCE:BTC-USDT");
        subscriptionManager.addBookSubscriber("BINANCE:BTC-USDT"); // Second subscriber same symbol
        subscriptionManager.addBookSubscriber("BINANCE:ETH-USDT");

        assertThat(subscriptionManager.getActiveBookSubscriptions()).isEqualTo(2);

        // Remove one subscriber
        subscriptionManager.removeBookSubscriber("BINANCE:BTC-USDT");
        assertThat(subscriptionManager.getActiveBookSubscriptions()).isEqualTo(2); // Still 1 ref on BTC, 1 on ETH

        // Remove last BTC subscriber
        subscriptionManager.removeBookSubscriber("BINANCE:BTC-USDT");
        assertThat(subscriptionManager.getActiveBookSubscriptions()).isEqualTo(1); // Only ETH remains

        // Remove ETH
        subscriptionManager.removeBookSubscriber("BINANCE:ETH-USDT");
        assertThat(subscriptionManager.getActiveBookSubscriptions()).isEqualTo(0);
    }

    @Test
    void gapDetectionIncrementsCounters() {
        // Simulate sequence gap detection
        subscriptionManager.onBookUpdate("BINANCE:BTC-USDT", 1, 10);
        subscriptionManager.onBookUpdate("BINANCE:BTC-USDT", 1, 15); // Gap of 4

        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(1);

        // Another gap
        subscriptionManager.onBookUpdate("BINANCE:BTC-USDT", 1, 20);
        subscriptionManager.onBookUpdate("BINANCE:BTC-USDT", 1, 25); // Gap of 4

        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(2);
    }

    @Test
    void epochChangeCountsAsResyncNotGap() {
        subscriptionManager.onBookUpdate("BINANCE:BTC-USDT", 1, 10);
        subscriptionManager.onBookUpdate("BINANCE:BTC-USDT", 2, 1); // New epoch, seq resets

        // Should count as resync, not gap
        assertThat(subscriptionManager.getTotalResyncs()).isEqualTo(1);
        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(0);
    }

    @Test
    void sequenceTrackingWorks() {
        subscriptionManager.onTrade("BINANCE:BTC-USDT", 1, 100);
        subscriptionManager.onTrade("BINANCE:BTC-USDT", 1, 101);
        subscriptionManager.onTrade("BINANCE:BTC-USDT", 1, 103); // Gap

        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(1);
    }
}
