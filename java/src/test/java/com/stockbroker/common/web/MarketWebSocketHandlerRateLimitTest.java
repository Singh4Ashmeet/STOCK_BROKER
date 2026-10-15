package com.stockbroker.common.web;

import com.stockbroker.auth.service.AuthService;
import com.stockbroker.common.config.RateLimitProperties;
import com.stockbroker.market.state.MarketStateManager;
import com.stockbroker.market.subscription.SubscriptionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketWebSocketHandlerRateLimitTest {

    @Mock
    private AuthService authService;

    @Mock
    private MarketStateManager marketStateManager;

    @Mock
    private SubscriptionManager subscriptionManager;

    @Mock
    private RateLimitProperties rateLimitProperties;

    @Mock
    private WebSocketSession session;

    @Mock
    private org.springframework.scheduling.TaskScheduler taskScheduler;

    private MarketWebSocketHandler handler;
    private final UUID userId = UUID.randomUUID();
    private final String ticket = "valid-ticket";

    @BeforeEach
    void setUp() {
        when(rateLimitProperties.getDefaultRequestsPerMinute()).thenReturn(300);
        when(rateLimitProperties.isEnabled()).thenReturn(true);

        handler = new MarketWebSocketHandler(
                authService, marketStateManager, subscriptionManager,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                taskScheduler, rateLimitProperties
        );

        when(authService.validateAndConsumeWsTicket(ticket)).thenReturn(userId);
        
        URI uri = URI.create("ws://localhost:8080/ws/v1?ticket=" + ticket);
        when(session.getUri()).thenReturn(uri);
        when(session.getHandshakeHeaders()).thenReturn(
                new org.springframework.http.HttpHeaders() {{
                    set("X-Forwarded-For", "192.168.1.1");
                }}
        );
        when(session.getRemoteAddress()).thenReturn(new java.net.InetSocketAddress("192.168.1.1", 8080));
    }

    @Test
    void shouldAllowConnectionWhenUnderLimit() throws Exception {
        // Given - first connection for user and IP
        when(session.getId()).thenReturn("session-1");

        // When
        handler.afterConnectionEstablished(session);

        // Then - session should be established
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void shouldRejectConnectionWhenMaxSessionsPerUserReached() throws Exception {
        // Given - setup 5 existing sessions for this user (MAX_SESSIONS_PER_USER = 5)
        when(session.getId()).thenReturn("new-session");

        // Add 5 existing sessions via reflection
        java.lang.reflect.Field sessionsField = MarketWebSocketHandler.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, MarketWebSocketHandler.SessionContext> sessions = 
                (java.util.Map<String, MarketWebSocketHandler.SessionContext>) sessionsField.get(handler);

        for (int i = 0; i < 5; i++) {
            WebSocketSession existingSession = mock(WebSocketSession.class);
            when(existingSession.getId()).thenReturn("existing-" + i);
            
            MarketWebSocketHandler.SessionContext ctx = new MarketWebSocketHandler.SessionContext(handler, existingSession, userId);
            sessions.put("existing-" + i, ctx);
        }

        // When
        handler.afterConnectionEstablished(session);

        // Then - should reject due to max sessions per user
        verify(session).close(argThat(status -> 
            status.getCode() == CloseStatus.SESSION_NOT_RELIABLE.getCode()
        ));
    }

    @Test
    void shouldExtractClientIpFromXForwardedForHeader() throws Exception {
        // Given
        when(session.getId()).thenReturn("session-1");
        
        // Test the private getClientIp method via reflection
        java.lang.reflect.Method getClientIpMethod = MarketWebSocketHandler.class.getDeclaredMethod("getClientIp", WebSocketSession.class);
        getClientIpMethod.setAccessible(true);
        
        String clientIp = (String) getClientIpMethod.invoke(handler, session);

        // Then
        assertThat(clientIp).isEqualTo("192.168.1.1");
    }

    @Test
    void shouldExtractClientIpFromXRealIpHeader() throws Exception {
        // Given
        when(session.getId()).thenReturn("session-1");
        when(session.getHandshakeHeaders()).thenReturn(
                new org.springframework.http.HttpHeaders() {{
                    set("X-Real-IP", "10.0.0.1");
                }}
        );
        
        java.lang.reflect.Method getClientIpMethod = MarketWebSocketHandler.class.getDeclaredMethod("getClientIp", WebSocketSession.class);
        getClientIpMethod.setAccessible(true);
        
        String clientIp = (String) getClientIpMethod.invoke(handler, session);

        // Then
        assertThat(clientIp).isEqualTo("10.0.0.1");
    }
}