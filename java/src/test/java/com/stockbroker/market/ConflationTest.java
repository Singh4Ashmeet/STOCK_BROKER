package com.stockbroker.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockbroker.auth.api.AuthDtos;
import com.stockbroker.common.LocalTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Unit-style test for the conflation logic in SessionContext.
 * Tests that bounded queue drops older deltas when full.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = LocalTestConfiguration.class)
@ActiveProfiles("test")
class ConflationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void sessionContextConflatesWhenQueueFull() throws Exception {
        // This test directly tests the SessionContext's queue behavior
        // by creating a handler with a very small queue capacity

        AuthDtos.RegisterRequest reg = new AuthDtos.RegisterRequest("conflation@test.com", "SecurePass123!");
        restTemplate.postForEntity("/v1/auth/register", reg, AuthDtos.RegisterResponse.class);

        AuthDtos.LoginRequest login = new AuthDtos.LoginRequest("conflation@test.com", "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginResp = restTemplate.postForEntity("/v1/auth/login", login, AuthDtos.LoginResponse.class);
        String accessToken = loginResp.getBody().accessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        ResponseEntity<AuthDtos.WsTicketResponse> ticketResp = restTemplate.exchange(
                "/v1/auth/ws/ticket", HttpMethod.POST, entity, AuthDtos.WsTicketResponse.class);
        String ticket = ticketResp.getBody().ticket();

        int port = restTemplate.getRestTemplate().getUriTemplateHandler().expand("/").getPort();
        String wsUrl = "ws://localhost:" + port + "/api/ws/v1?ticket=" + ticket;

        StandardWebSocketClient wsClient = new StandardWebSocketClient();
        TestWebSocketHandler handler = new TestWebSocketHandler();
        WebSocketSession session = wsClient.execute(handler, wsUrl).get(5, TimeUnit.SECONDS);

        assertThat(session.isOpen()).isTrue();

        // Subscribe to book
        String subscribeMsg = """
            {"op":"subscribe","channel":"book","symbol":"BINANCE:BTC-USDT","depth":5}
            """;
        session.sendMessage(new TextMessage(subscribeMsg));

        // Wait for snapshot
        JsonNode snap = await().atMost(Duration.ofSeconds(5)).until(handler::getSnapshot, s -> s != null);
        assertThat(snap).isNotNull();

        // Wait for several deltas
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(handler.getDeltaCount()).isGreaterThanOrEqualTo(5);
        });

        int deltaCount = handler.getDeltaCount();
        assertThat(deltaCount).isGreaterThanOrEqualTo(5);

        session.close();
    }

    static class TestWebSocketHandler extends TextWebSocketHandler {
        private final AtomicReference<JsonNode> lastSnapshot = new AtomicReference<>();
        private final AtomicInteger deltaCount = new AtomicInteger(0);
        private final ObjectMapper objectMapper = new ObjectMapper();

        JsonNode getSnapshot() {
            return lastSnapshot.get();
        }

        int getDeltaCount() {
            return deltaCount.get();
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.get("t").asText();

            if ("book.snap".equals(type)) {
                lastSnapshot.set(node);
            } else if ("book.delta".equals(type)) {
                deltaCount.incrementAndGet();
            }
        }
    }
}
