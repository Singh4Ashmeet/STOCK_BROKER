package com.stockbroker.trading.service;

import com.stockbroker.common.error.BusinessProblem;
import com.stockbroker.common.security.CurrentUser;
import com.stockbroker.market.service.MarketDataService;
import com.stockbroker.trading.api.TradingDtos;
import com.stockbroker.trading.api.TradingDtosExtended;
import com.stockbroker.trading.domain.Account;
import com.stockbroker.trading.domain.Order;
import com.stockbroker.trading.domain.Fill;
import com.stockbroker.trading.repo.OrderRepository;
import com.stockbroker.trading.repo.FillRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Order management service - HTTP layer delegating to OrderExecutor.
 */
@Service
@Transactional
public class OrderService {

    // Cap on open orders per user (NEW, ACCEPTED, PARTIALLY_FILLED)
    private static final int MAX_OPEN_ORDERS_PER_USER = 100;

    private final OrderExecutor orderExecutor;
    private final OrderRepository orderRepository;
    private final FillRepository fillRepository;
    private final MarketDataService marketDataService;
    private final AccountService accountService;
    private final GoldenVectorService goldenVectorService;

    public OrderService(OrderExecutor orderExecutor,
                        OrderRepository orderRepository,
                        FillRepository fillRepository,
                        MarketDataService marketDataService,
                        AccountService accountService,
                        GoldenVectorService goldenVectorService) {
        this.orderExecutor = orderExecutor;
        this.orderRepository = orderRepository;
        this.fillRepository = fillRepository;
        this.marketDataService = marketDataService;
        this.accountService = accountService;
        this.goldenVectorService = goldenVectorService;
    }

    public TradingDtos.OrderResponse createOrder(UUID userId,
                                                  TradingDtos.CreateOrderRequest request,
                                                  String idempotencyKey) {
        // Check idempotency key
        Optional<Order> existing = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            Order existingOrder = existing.get();
            byte[] requestHash = computeRequestHash(request);
            if (!MessageDigest.isEqual(existingOrder.getRequestHash(), requestHash)) {
                throw new BusinessProblem.OrderDuplicateKeyMismatch();
            }
            // Return existing order
            return toOrderResponse(existingOrder);
        }

        // Check open orders cap per user
        long openOrders = orderRepository.countByUserIdAndStatusIn(userId, List.of("NEW", "ACCEPTED", "PARTIALLY_FILLED"));
        if (openOrders >= MAX_OPEN_ORDERS_PER_USER) {
            throw new BusinessProblem.OrderLimitExceeded(MAX_OPEN_ORDERS_PER_USER);
        }

        // Validate symbol exists
        var snapshot = marketDataService.getSnapshot(request.symbol(), 1);
        if (snapshot == null) {
            throw new BusinessProblem.InstrumentNotFound(request.symbol());
        }
        if (!snapshot.bookValid()) {
            throw new BusinessProblem.OrderBookInvalid(request.symbol());
        }

        // Get or create default account
        Account account = accountService.getOrCreateDefaultAccount(userId);

        // Create order
        Order order = new Order();
        order.setUserId(userId);
        order.setAccountId(account.getId());
        order.setSymbol(request.symbol());
        order.setSide(request.side().name());
        order.setType(request.type().name());
        order.setTif(request.tif().name());
        order.setQty(new BigDecimal(request.qty()));
        order.setLimitPrice(request.limitPrice() != null ? new BigDecimal(request.limitPrice()) : null);
        order.setStatus("NEW");
        order.setFilledQty(BigDecimal.ZERO);
        order.setIdempotencyKey(idempotencyKey);
        order.setRequestHash(computeRequestHash(request));

        orderRepository.save(order);

        // Execute order through executor
        Order executedOrder = orderExecutor.executeOrder(order);

        return toOrderResponse(executedOrder);
    }

    public TradingDtosExtended.OrderListResponse listOrders(UUID userId,
                                                             String status,
                                                             String symbol,
                                                             int limit,
                                                             String cursor) {
        // TODO: Implement cursor pagination
        List<Order> orders = orderRepository.findByUserId(userId, PageRequest.of(0, limit));
        
        // Filter by status if provided
        if (status != null) {
            orders = orders.stream()
                    .filter(o -> o.getStatus().equalsIgnoreCase(status))
                    .collect(Collectors.toList());
        }
        
        // Filter by symbol if provided
        if (symbol != null) {
            orders = orders.stream()
                    .filter(o -> o.getSymbol().equals(symbol))
                    .collect(Collectors.toList());
        }

        List<TradingDtos.OrderResponse> responses = orders.stream()
                .map(this::toOrderResponse)
                .collect(Collectors.toList());
        return new TradingDtosExtended.OrderListResponse(responses, null);
    }

    public TradingDtos.OrderResponse getOrder(UUID userId, UUID orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessProblem.OrderNotFound(orderId.toString()));
        return toOrderResponse(order);
    }

    public TradingDtos.OrderResponse cancelOrder(UUID userId, UUID orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessProblem.OrderNotFound(orderId.toString()));

        if (!order.getStatus().matches("NEW|ACCEPTED|PARTIALLY_FILLED")) {
            throw new BusinessProblem.OrderCannotCancel(orderId.toString(), order.getStatus());
        }

        order.setStatus("CANCELED");
        orderRepository.save(order);

        return toOrderResponse(order);
    }

    public TradingDtosExtended.FillsResponse listFills(UUID userId, int limit, String cursor) {
        // TODO: Implement cursor pagination
        // For now, use a simple approach
        return new TradingDtosExtended.FillsResponse(List.of(), null);
    }

    public TradingDtos.OrderPreviewResponse previewOrder(UUID userId,
                                                          TradingDtos.OrderPreviewRequest request) {
        OrderExecutor.OrderPreviewResult result = orderExecutor.previewOrder(request);
        
        // Save preview vector for golden vector testing
        try {
            var snapshot = marketDataService.getSnapshot(request.symbol(), 100);
            if (snapshot != null) {
                goldenVectorService.savePreviewVector(result, request, snapshot);
            }
        } catch (Exception e) {
            // Ignore preview vector errors
        }
        
        return new TradingDtos.OrderPreviewResponse(
                result.estFillPrice(),
                result.estFees(),
                result.estSlippageBps(),
                result.bookValid(),
                result.levelsConsumed()
        );
    }

    private byte[] computeRequestHash(TradingDtos.CreateOrderRequest request) {
        try {
            String canonical = String.format("%s|%s|%s|%s|%s|%s",
                    request.symbol(),
                    request.side(),
                    request.type(),
                    request.qty(),
                    request.limitPrice() != null ? request.limitPrice() : "",
                    request.tif());
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute request hash", e);
        }
    }

    private TradingDtos.OrderResponse toOrderResponse(Order order) {
        List<Fill> fills = fillRepository.findByOrderId(order.getId());
        List<TradingDtos.FillResponse> fillResponses = fills.stream()
                .map(this::toFillResponse)
                .collect(Collectors.toList());

        return new TradingDtos.OrderResponse(
                order.getId().toString(),
                order.getSymbol(),
                TradingDtos.OrderSide.valueOf(order.getSide()),
                TradingDtos.OrderType.valueOf(order.getType()),
                TradingDtos.OrderStatus.valueOf(order.getStatus()),
                order.getQty().toPlainString(),
                order.getFilledQty().toPlainString(),
                order.getAvgFillPrice() != null ? order.getAvgFillPrice().toPlainString() : null,
                order.getLimitPrice() != null ? order.getLimitPrice().toPlainString() : null,
                TradingDtos.TimeInForce.valueOf(order.getTif()),
                order.getRejectReason(),
                order.getCreatedAt().toString(),
                order.getUpdatedAt().toString(),
                fillResponses
        );
    }

    private TradingDtos.FillResponse toFillResponse(Fill fill) {
        return new TradingDtos.FillResponse(
                fill.getId().toString(),
                fill.getPrice().toPlainString(),
                fill.getQty().toPlainString(),
                fill.getFeeAsset(),
                fill.getFeeAmount().toPlainString(),
                fill.getLiquidity(),
                fill.getBookEpoch(),
                fill.getBookSeq(),
                fill.getFilledAt().toString()
        );
    }
}