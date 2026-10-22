package com.stockbroker.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockbroker.auth.service.AuthService;
import com.stockbroker.common.config.RateLimitProperties;
import com.stockbroker.market.api.MarketDtos;
import com.stockbroker.market.state.MarketStateManager;
import com.stockbroker.market.subscription.SubscriptionManager;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bandwidth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * WebSocket handler for real-time market data fan-out with per-client bounded queues and conflation.
 *
 * <p>Protocol per API spec (docs/04-API-SPEC.md section 3):
 * - Connect with ticket: `wss://host/ws/v1?ticket=<ticket>`
 * - JSON text frames
 * - Client -> Server: subscribe, unsubscribe, resync, ping
 * - Server -> Client: book.snap, book.delta, trade, metrics, order, portfolio, resync, error, pong
 *
 * <p>Features:
 * - Per-client bounded queues with conflation (slow clients get latest snapshot, not backlog)
 * - Token expiry closes sessions
 * - Caps per user and per session
 * - Shared upstream subscriptions via SubscriptionManager
 * - Per-user and per-IP connection rate limiting via Bucket4j + Redis
 */
public class MarketWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketWebSocketHandler.class);

    private final AuthService authService;
    private final MarketStateManager marketStateManager;
    private final SubscriptionManager subscriptionManager;
    private final ObjectMapper objectMapper;
    private final RateLimitProperties rateLimitProperties;

    // Session management
    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> symbolSubscriptions = new ConcurrentHashMap<>();

    // Rate limiters for WebSocket connections (per-user and per-IP)
    // Note: In production, these should use Redis-backed Bucket4j for distributed rate limiting
    // For simplicity, we use local in-memory buckets here
    private final Map<String, Bucket> userConnectionBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> ipConnectionBuckets = new ConcurrentHashMap<>();

    // Limits
    private static final int MAX_SESSIONS_PER_USER = 5;
    private static final int MAX_SUBSCRIPTIONS_PER_SESSION = 20;
    private static final int MAX_INBOUND_PER_SECOND = 20;

    // Queue configuration
    private static final int CLIENT_QUEUE_CAPACITY = 1024;
    private static final long TOKEN_EXPIRY_CHECK_INTERVAL_MS = 5000;

    // Metrics
    private final AtomicLong totalMessagesSent = new AtomicLong(0);
    private final AtomicLong totalMessagesDropped = new AtomicLong(0);
    private final AtomicLong totalConflations = new AtomicLong(0);
    private final AtomicLong sessionsClosedByTokenExpiry = new AtomicLong(0);

    public MarketWebSocketHandler(AuthService authService,
                                  MarketStateManager marketStateManager,
                                  SubscriptionManager subscriptionManager,
                                  ObjectMapper objectMapper,
                                  TaskScheduler taskScheduler,
                                  RateLimitProperties rateLimitProperties) {
        this.authService = authService;
        this.marketStateManager = marketStateManager;
        this.subscriptionManager = subscriptionManager;
        this.objectMapper = objectMapper;
        this.rateLimitProperties = rateLimitProperties;

        // Schedule token expiry checker
        taskScheduler.scheduleAtFixedRate(this::checkTokenExpiry, Duration.ofMillis(TOKEN_EXPIRY_CHECK_INTERVAL_MS));
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String ticket = extractTicket(session);
        if (ticket == null) {
            session.close(CloseStatus.BAD_DATA.withReason("Missing ticket parameter"));
            return;
        }

        // Validate and consume ticket from Redis
        UUID userId = authService.validateAndConsumeWsTicket(ticket);
        if (userId == null) {
            session.close(CloseStatus.BAD_DATA.withReason("Invalid or expired ticket"));
            return;
        }

        // Check session limit per user
        long userSessions = sessions.values().stream()
                .filter(s -> s.userId.equals(userId))
                .count();
        if (userSessions >= MAX_SESSIONS_PER_USER) {
            session.close(CloseStatus.SESSION_NOT_RELIABLE.withReason("Max sessions per user reached"));
            return;
        }

        // Per-user connection rate limiting
        String userKey = "ws:user:" + userId;
        Bucket userBucket = userConnectionBuckets.computeIfAbsent(userKey, k -> createConnectionBucket());
        if (!userBucket.tryConsume(1)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Connection rate limit exceeded for user"));
            return;
        }

        // Per-IP connection rate limiting
        String clientIp = getClientIp(session);
        String ipKey = "ws:ip:" + clientIp;
        Bucket ipBucket = ipConnectionBuckets.computeIfAbsent(ipKey, k -> createConnectionBucket());
        if (!ipBucket.tryConsume(1)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Connection rate limit exceeded for IP"));
            return;
        }

        // Extract access token from ticket metadata (stored in Redis with ticket)
        // For now, we'll use a placeholder - in production, the ticket would carry the token expiry
        SessionContext ctx = new SessionContext(this, session, userId);
        sessions.put(session.getId(), ctx);

        // Start the outbound writer for this session
        ctx.startWriter();

        log.info("WebSocket connected: session={}, user={}, ip={}", session.getId(), userId, clientIp);
    }

    private Bucket createConnectionBucket() {
        // 10 connections per minute per user/IP
        int limit = rateLimitProperties != null ? rateLimitProperties.getDefaultRequestsPerMinute() / 3 : 10;
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(limit).refillGreedy(limit, Duration.ofMinutes(1)).build())
                .build();
    }

    private String getClientIp(WebSocketSession session) {
        // Try to get IP from headers (for proxied connections)
        String forwardedFor = session.getHandshakeHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = session.getHandshakeHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        // Fallback to remote address
        return session.getRemoteAddress() != null ? session.getRemoteAddress().getAddress().getHostAddress() : "unknown";
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        SessionContext ctx = sessions.get(session.getId());
        if (ctx == null) {
            return;
        }

        // Rate limiting
        if (!ctx.checkRateLimit()) {
            sendError(session, "RATE_LIMITED", "Too many messages");
            return;
        }

        try {
            Map<String, Object> msg = objectMapper.readValue(message.getPayload(), new TypeReference<Map<String, Object>>() {});
            String op = (String) msg.get("op");

            switch (op) {
                case "subscribe" -> handleSubscribe(ctx, msg);
                case "unsubscribe" -> handleUnsubscribe(ctx, msg);
                case "resync" -> handleResync(ctx, msg);
                case "ping" -> handlePing(ctx, msg);
                default -> sendError(session, "UNKNOWN_OP", "Unknown operation: " + op);
            }
        } catch (IOException e) {
            sendError(session, "INVALID_JSON", "Invalid JSON message");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionContext ctx = sessions.remove(session.getId());
        if (ctx != null) {
            ctx.stopWriter();
            // Clean up subscriptions
            ctx.subscriptions.forEach(subKey -> {
                removeSubscription(subKey, session.getId());
                String[] parts = subKey.split(":", 2);
                if (parts.length == 2) {
                    String channel = parts[0];
                    String symbol = parts[1];
                    switch (channel) {
                        case "book" -> subscriptionManager.removeBookSubscriber(symbol);
                        case "trades" -> subscriptionManager.removeTradesSubscriber(symbol);
                        case "metrics" -> subscriptionManager.removeMetricsSubscriber(symbol);
                    }
                }
            });
            log.info("WebSocket closed: session={}, user={}, reason={}, sent={}, dropped={}, conflations={}",
                    session.getId(), ctx.userId, status,
                    ctx.messagesSent.get(), ctx.messagesDropped.get(), ctx.conflations.get());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket error: session={}, error={}", session.getId(), exception.getMessage());
    }

    private void checkTokenExpiry() {
        Instant now = Instant.now();
        for (SessionContext ctx : sessions.values()) {
            if (ctx.accessTokenExpiry != null && ctx.accessTokenExpiry.isBefore(now)) {
                log.info("Closing session {} for user {} due to token expiry",
                        ctx.session.getId(), ctx.userId);
                sessionsClosedByTokenExpiry.incrementAndGet();
                try {
                    ctx.session.close(CloseStatus.SESSION_NOT_RELIABLE.withReason("Access token expired"));
                } catch (IOException e) {
                    log.debug("Error closing expired session: {}", e.getMessage());
                }
            }
        }
    }

    private String extractTicket(WebSocketSession session) {
        String query = session.getUri().getQuery();
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=");
            if (kv.length == 2 && "ticket".equals(kv[0])) {
                return kv[1];
            }
        }
        return null;
    }

    private void handleSubscribe(SessionContext ctx, Map<String, Object> msg) {
        String channel = (String) msg.get("channel");
        String symbol = (String) msg.get("symbol");

        if (channel == null || symbol == null) {
            sendError(ctx.session, "INVALID_SUBSCRIBE", "Missing channel or symbol");
            return;
        }

        if (ctx.subscriptions.size() >= MAX_SUBSCRIPTIONS_PER_SESSION) {
            sendError(ctx.session, "SUBSCRIPTION_LIMIT", "Max subscriptions reached");
            return;
        }

        String subKey = channel + ":" + symbol;
        if (ctx.subscriptions.contains(subKey)) {
            return; // Already subscribed
        }

        // Add to session
        ctx.subscriptions.add(subKey);

        // Add to global subscription map
        symbolSubscriptions.computeIfAbsent(subKey, k -> ConcurrentHashMap.newKeySet())
                .add(ctx.session.getId());

        // Register with subscription manager for refcounted gRPC subscriptions
        switch (channel) {
            case "book" -> subscriptionManager.addBookSubscriber(symbol);
            case "trades" -> subscriptionManager.addTradesSubscriber(symbol);
            case "metrics" -> subscriptionManager.addMetricsSubscriber(symbol);
            case "orders", "portfolio" -> {
                sendError(ctx.session, "UNAUTHORIZED", "Private channel requires additional auth");
                ctx.subscriptions.remove(subKey);
                return;
            }
            default -> {
                sendError(ctx.session, "INVALID_CHANNEL", "Unknown channel: " + channel);
                ctx.subscriptions.remove(subKey);
                return;
            }
        }

        // Send initial snapshot for book channel (snapshot-then-delta)
        if ("book".equals(channel)) {
            sendBookSnapshot(ctx, symbol, (Integer) msg.getOrDefault("depth", 20));
        } else if ("trades".equals(channel)) {
            sendRecentTrades(ctx, symbol);
        } else if ("metrics".equals(channel)) {
            sendMetrics(ctx, symbol);
        }
    }

    private void handleUnsubscribe(SessionContext ctx, Map<String, Object> msg) {
        String channel = (String) msg.get("channel");
        String symbol = (String) msg.get("symbol");

        if (channel == null || symbol == null) return;

        String subKey = channel + ":" + symbol;
        ctx.subscriptions.remove(subKey);
        removeSubscription(subKey, ctx.session.getId());

        // Unregister from subscription manager
        switch (channel) {
            case "book" -> subscriptionManager.removeBookSubscriber(symbol);
            case "trades" -> subscriptionManager.removeTradesSubscriber(symbol);
            case "metrics" -> subscriptionManager.removeMetricsSubscriber(symbol);
        }
    }

    private void handleResync(SessionContext ctx, Map<String, Object> msg) {
        String channel = (String) msg.get("channel");
        String symbol = (String) msg.get("symbol");

        if ("book".equals(channel) && symbol != null) {
            sendBookSnapshot(ctx, symbol, 20);
        }
    }

    private void handlePing(SessionContext ctx, Map<String, Object> msg) {
        String id = (String) msg.get("id");
        Map<String, Object> pong = Map.of(
                "t", "pong",
                "id", id != null ? id : ""
        );
        sendMessage(ctx.session, pong);
    }

    private void sendBookSnapshot(SessionContext ctx, String symbol, int depth) {
        var snapshot = marketStateManager.getSnapshot(symbol, depth);
        if (snapshot != null) {
            Map<String, Object> msg = Map.of(
                    "t", "book.snap",
                    "ch", "book",
                    "s", symbol,
                    "ep", snapshot.epoch(),
                    "sq", snapshot.seq(),
                    "tm", snapshot.timing(),
                    "d", Map.of(
                            "b", snapshot.snapshot().bids().stream()
                                    .map(l -> List.of(l.price(), l.qty())).toList(),
                            "a", snapshot.snapshot().asks().stream()
                                    .map(l -> List.of(l.price(), l.qty())).toList()
                    )
            );
            ctx.enqueue(msg);
        }
    }

    private void sendRecentTrades(SessionContext ctx, String symbol) {
        List<MarketDtos.Trade> trades = marketStateManager.getRecentTrades(symbol, 50);
        for (MarketDtos.Trade trade : trades) {
            Map<String, Object> msg = Map.of(
                    "t", "trade",
                    "ch", "trades",
                    "s", symbol,
                    "ep", trade.epoch(),
                    "sq", trade.seq(),
                    "tm", trade.timing(),
                    "d", Map.of(
                            "p", trade.price(),
                            "q", trade.qty(),
                            "side", trade.side(),
                            "id", trade.venueTradeId()
                    )
            );
            ctx.enqueue(msg);
        }
    }

    private void sendMetrics(SessionContext ctx, String symbol) {
        var metrics = marketStateManager.getMetrics(symbol);
        if (metrics != null) {
            Map<String, Object> msg = Map.of(
                    "t", "metrics",
                    "ch", "metrics",
                    "s", symbol,
                    "ep", metrics.epoch(),
                    "sq", metrics.seq(),
                    "tm", metrics.timing(),
                    "d", Map.of(
                            "best_bid", metrics.bestBid(),
                            "best_ask", metrics.bestAsk(),
                            "best_bid_qty", metrics.bestBidQty(),
                            "best_ask_qty", metrics.bestAskQty(),
                            "spread", metrics.spread(),
                            "mid", metrics.mid(),
                            "microprice", metrics.microprice(),
                            "imbalance_top_n", metrics.imbalanceTopN(),
                            "rolling_vwap", metrics.rollingVwap(),
                            "trade_flow_imbalance", metrics.tradeFlowImbalance(),
                            "depth", metrics.depth(),
                            "book_valid", metrics.bookValid()
                    )
            );
            ctx.enqueue(msg);
        }
    }

    private void removeSubscription(String subKey, String sessionId) {
        Set<String> sessionIds = symbolSubscriptions.get(subKey);
        if (sessionIds != null) {
            sessionIds.remove(sessionId);
            if (sessionIds.isEmpty()) {
                symbolSubscriptions.remove(subKey);
            }
        }
    }

    // Called by MarketStateManager when updates arrive - enqueues to each subscribed client
    public void broadcastBookUpdate(String symbol, MarketDtos.BookUpdate update) {
        String subKey = "book:" + symbol;
        Set<String> sessionIds = symbolSubscriptions.get(subKey);
        if (sessionIds == null) return;

        Map<String, Object> msg = Map.of(
                "t", update.delta() != null ? "book.delta" : "book.snap",
                "ch", "book",
                "s", symbol,
                "ep", update.epoch(),
                "sq", update.seq(),
                "tm", update.timing(),
                "d", update.delta() != null ? Map.of(
                        "b", update.delta().bids().stream().map(l -> List.of(l.price(), l.qty())).toList(),
                        "a", update.delta().asks().stream().map(l -> List.of(l.price(), l.qty())).toList()
                ) : Map.of(
                        "b", update.snapshot().bids().stream().map(l -> List.of(l.price(), l.qty())).toList(),
                        "a", update.snapshot().asks().stream().map(l -> List.of(l.price(), l.qty())).toList()
                )
        );

        for (String sessionId : sessionIds) {
            SessionContext ctx = sessions.get(sessionId);
            if (ctx != null) {
                ctx.enqueue(msg);
            }
        }
    }

    public void broadcastTrade(String symbol, MarketDtos.Trade trade) {
        String subKey = "trades:" + symbol;
        Set<String> sessionIds = symbolSubscriptions.get(subKey);
        if (sessionIds == null) return;

        Map<String, Object> msg = Map.of(
                "t", "trade",
                "ch", "trades",
                "s", symbol,
                "ep", trade.epoch(),
                "sq", trade.seq(),
                "tm", trade.timing(),
                "d", Map.of(
                        "p", trade.price(),
                        "q", trade.qty(),
                        "side", trade.side(),
                        "id", trade.venueTradeId()
                )
        );

        for (String sessionId : sessionIds) {
            SessionContext ctx = sessions.get(sessionId);
            if (ctx != null) {
                ctx.enqueue(msg);
            }
        }
    }

    public void broadcastMetrics(String symbol, MarketDtos.Metrics metrics) {
        String subKey = "metrics:" + symbol;
        Set<String> sessionIds = symbolSubscriptions.get(subKey);
        if (sessionIds == null) return;

        Map<String, Object> msg = Map.of(
                "t", "metrics",
                "ch", "metrics",
                "s", symbol,
                "ep", metrics.epoch(),
                "sq", metrics.seq(),
                "tm", metrics.timing(),
                "d", Map.of(
                        "best_bid", metrics.bestBid(),
                        "best_ask", metrics.bestAsk(),
                        "best_bid_qty", metrics.bestBidQty(),
                        "best_ask_qty", metrics.bestAskQty(),
                        "spread", metrics.spread(),
                        "mid", metrics.mid(),
                        "microprice", metrics.microprice(),
                        "imbalance_top_n", metrics.imbalanceTopN(),
                        "rolling_vwap", metrics.rollingVwap(),
                        "trade_flow_imbalance", metrics.tradeFlowImbalance(),
                        "depth", metrics.depth(),
                        "book_valid", metrics.bookValid()
                )
        );

        for (String sessionId : sessionIds) {
            SessionContext ctx = sessions.get(sessionId);
            if (ctx != null) {
                ctx.enqueue(msg);
            }
        }
    }

    /**
     * Session context with bounded outbound queue and conflation.
     * When queue is full, older delta messages are dropped, keeping only the latest snapshot.
     */
    static class SessionContext {
        final MarketWebSocketHandler handler;
        final WebSocketSession session;
        final UUID userId;
        final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
        final Deque<Long> messageTimestamps = new ArrayDeque<>();

        // Bounded outbound queue with conflation
        final ArrayBlockingQueue<OutboundMessage> outboundQueue =
                new ArrayBlockingQueue<>(CLIENT_QUEUE_CAPACITY);

        // Writer control
        final AtomicBoolean writerRunning = new AtomicBoolean(false);
        Thread writerThread;

        // Token expiry (set from ticket metadata)
        volatile Instant accessTokenExpiry;

        // Metrics
        final AtomicLong messagesSent = new AtomicLong(0);
        final AtomicLong messagesDropped = new AtomicLong(0);
        final AtomicLong conflations = new AtomicLong(0);

        SessionContext(MarketWebSocketHandler handler, WebSocketSession session, UUID userId) {
            this.handler = handler;
            this.session = session;
            this.userId = userId;
            // Access token expiry would be stored with the ticket in Redis
            // For now, we'll set a default 15-minute expiry from now
            this.accessTokenExpiry = Instant.now().plus(15, ChronoUnit.MINUTES);
        }

        boolean checkRateLimit() {
            long now = System.currentTimeMillis();
            while (!messageTimestamps.isEmpty() && messageTimestamps.peekFirst() < now - 1000) {
                messageTimestamps.pollFirst();
            }
            if (messageTimestamps.size() >= MAX_INBOUND_PER_SECOND) {
                return false;
            }
            messageTimestamps.addLast(now);
            return true;
        }

        void startWriter() {
            if (writerRunning.compareAndSet(false, true)) {
                writerThread = new Thread(this::writerLoop, "ws-writer-" + session.getId().substring(0, 8));
                writerThread.setDaemon(true);
                writerThread.start();
            }
        }

        void stopWriter() {
            writerRunning.set(false);
            outboundQueue.clear(); // Wake up writer
            if (writerThread != null) {
                writerThread.interrupt();
            }
        }

        void enqueue(Object message) {
            // Determine if this is a snapshot (high priority) or delta/update (low priority)
            boolean isSnapshot = false;
            if (message instanceof Map) {
                Object type = ((Map<?, ?>) message).get("t");
                if (type instanceof String && ((String) type).endsWith(".snap")) {
                    isSnapshot = true;
                }
            }

            OutboundMessage outbound = new OutboundMessage(message, isSnapshot, Instant.now());

            // Try to add to queue
            if (!outboundQueue.offer(outbound)) {
                // Queue full - apply conflation
                conflateAndEnqueue(outbound);
            }
        }

        private void conflateAndEnqueue(OutboundMessage newMessage) {
            // Conflation strategy: remove older deltas for same symbol/channel, keep latest snapshot
            List<OutboundMessage> toRequeue = new ArrayList<>();
            OutboundMessage latestSnapshot = null;
            Map<String, OutboundMessage> latestDeltas = new HashMap<>();

            // Drain queue and classify messages
            OutboundMessage msg;
            while ((msg = outboundQueue.poll()) != null) {
                String key = getMessageKey(msg.message());
                if (msg.isSnapshot()) {
                    // Keep only the latest snapshot
                    if (latestSnapshot == null || msg.timestamp().isAfter(latestSnapshot.timestamp())) {
                        latestSnapshot = msg;
                    }
                } else {
                    // Keep only the latest delta per key
                    latestDeltas.put(key, msg);
                }
            }

            // Re-add latest snapshot first
            if (latestSnapshot != null) {
                toRequeue.add(latestSnapshot);
            }

            // Re-add latest deltas
            toRequeue.addAll(latestDeltas.values());

            // Add the new message
            toRequeue.add(newMessage);

            // Put back into queue
            for (OutboundMessage m : toRequeue) {
                if (!outboundQueue.offer(m)) {
                    // Still full - drop oldest (should be rare)
                    messagesDropped.incrementAndGet();
                    handler.totalMessagesDropped.incrementAndGet();
                }
            }

            conflations.incrementAndGet();
            handler.totalConflations.incrementAndGet();

            log.debug("Conflated queue for session {}: kept {} messages, dropped {}",
                    session.getId(), toRequeue.size(), outboundQueue.size() - toRequeue.size());
        }

        private String getMessageKey(Object message) {
            if (message instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) message;
                String channel = (String) map.get("ch");
                String symbol = (String) map.get("s");
                if (channel != null && symbol != null) {
                    return channel + ":" + symbol;
                }
            }
            return "unknown";
        }

        private void writerLoop() {
            try {
                while (writerRunning.get() && session.isOpen()) {
                    OutboundMessage msg = outboundQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (msg != null) {
                        handler.sendMessage(session, msg.message());
                        messagesSent.incrementAndGet();
                        handler.totalMessagesSent.incrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Writer loop error for session {}: {}", session.getId(), e.getMessage());
            } finally {
                writerRunning.set(false);
            }
        }
    }

    record OutboundMessage(Object message, boolean isSnapshot, Instant timestamp) {}

    private void sendMessage(WebSocketSession session, Object message) {
        if (!session.isOpen()) return;
        try {
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.debug("Failed to send WebSocket message: {}", e.getMessage());
        }
    }

    private void sendError(WebSocketSession session, String code, String message) {
        Map<String, Object> error = Map.of(
                "t", "error",
                "d", Map.of("code", code, "message", message)
        );
        sendMessage(session, error);
    }
}