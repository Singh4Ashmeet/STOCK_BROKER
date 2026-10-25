package com.stockbroker.common.web;

import com.stockbroker.auth.service.AuthService;
import com.stockbroker.common.config.RateLimitProperties;
import com.stockbroker.market.state.MarketStateManager;
import com.stockbroker.market.subscription.SubscriptionManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket configuration for real-time market data fan-out.
 * Uses SockJS in production for fallback transports, raw WebSocket in tests.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AuthService authService;
    private final MarketStateManager marketStateManager;
    private final SubscriptionManager subscriptionManager;
    private final RateLimitProperties rateLimitProperties;

    public WebSocketConfig(AuthService authService,
                           MarketStateManager marketStateManager,
                           SubscriptionManager subscriptionManager,
                           RateLimitProperties rateLimitProperties) {
        this.authService = authService;
        this.marketStateManager = marketStateManager;
        this.subscriptionManager = subscriptionManager;
        this.rateLimitProperties = rateLimitProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Raw WebSocket endpoint (for tests and native WebSocket clients)
        registry.addHandler(marketWebSocketHandler(), "/ws/v1")
                .setAllowedOrigins("*");

        // SockJS endpoint (for production browser clients with fallback)
        registry.addHandler(marketWebSocketHandler(), "/ws/v1/sockjs")
                .setAllowedOrigins("http://localhost:3000", "http://127.0.0.1:3000")
                .withSockJS();
    }

    @org.springframework.context.annotation.Bean
    public MarketWebSocketHandler marketWebSocketHandler() {
        return new MarketWebSocketHandler(
                authService,
                marketStateManager,
                subscriptionManager,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                taskScheduler(),
                rateLimitProperties
        );
    }

    @org.springframework.context.annotation.Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("ws-scheduler-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }
}
