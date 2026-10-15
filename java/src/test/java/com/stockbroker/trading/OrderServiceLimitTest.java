package com.stockbroker.trading;

import com.stockbroker.common.error.BusinessProblem;
import com.stockbroker.common.security.CurrentUser;
import com.stockbroker.market.api.MarketDtos;
import com.stockbroker.market.service.MarketDataService;
import com.stockbroker.trading.api.TradingDtos;
import com.stockbroker.trading.domain.Account;
import com.stockbroker.trading.domain.Order;
import com.stockbroker.trading.repo.OrderRepository;
import com.stockbroker.trading.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceLimitTest {

    @Mock
    private com.stockbroker.trading.service.OrderExecutor orderExecutor;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private com.stockbroker.trading.repo.FillRepository fillRepository;

    @Mock
    private MarketDataService marketDataService;

    @Mock
    private com.stockbroker.trading.service.AccountService accountService;

    @Mock
    private com.stockbroker.trading.service.GoldenVectorService goldenVectorService;

    private OrderService orderService;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        
        // Set up security context
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(userId.toString());
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);
        SecurityContextHolder.getContext().setAuthentication(auth);

        orderService = new OrderService(
                orderExecutor, orderRepository, fillRepository,
                marketDataService, accountService, goldenVectorService
        );
    }

    @Test
    void shouldRejectOrderWhenOpenOrdersLimitExceeded() {
        // Given
        when(orderRepository.countByUserIdAndStatusIn(any(), any()))
                .thenReturn(100L); // MAX_OPEN_ORDERS_PER_USER = 100

        TradingDtos.CreateOrderRequest request = new TradingDtos.CreateOrderRequest(
                "AAPL", TradingDtos.OrderSide.BUY, TradingDtos.OrderType.LIMIT,
                "100", "150.00", TradingDtos.TimeInForce.GTC, "idem-key"
        );

        // When/Then
        assertThatThrownBy(() -> orderService.createOrder(userId, request, "idem-key"))
                .isInstanceOf(BusinessProblem.OrderLimitExceeded.class)
                .hasMessageContaining("Maximum number of open orders (100) reached");
    }

    @Test
    void shouldAllowOrderWhenUnderLimit() {
        // Given
        when(orderRepository.countByUserIdAndStatusIn(any(), any()))
                .thenReturn(50L); // Under limit

        when(orderRepository.findByUserIdAndIdempotencyKey(any(), any()))
                .thenReturn(Optional.empty());

        MarketDtos.BookUpdate snapshot = new MarketDtos.BookUpdate(
                "AAPL", 1, 1, null, null, null, true
        );
        when(marketDataService.getSnapshot("AAPL", 1)).thenReturn(snapshot);

        Account account = new Account();
        account.setId(UUID.randomUUID());
        when(accountService.getOrCreateDefaultAccount(userId)).thenReturn(account);

        com.stockbroker.trading.service.OrderExecutor.OrderPreviewResult preview = 
                new com.stockbroker.trading.service.OrderExecutor.OrderPreviewResult(
                        "150.00", "1.00", 0, true, 1
                );
        when(orderExecutor.previewOrder(any())).thenReturn(preview);

        Order executedOrder = new Order();
        executedOrder.setId(UUID.randomUUID());
        executedOrder.setUserId(userId);
        executedOrder.setSymbol("AAPL");
        executedOrder.setSide("BUY");
        executedOrder.setType("LIMIT");
        executedOrder.setStatus("NEW");
        executedOrder.setQty(new BigDecimal("100"));
        executedOrder.setLimitPrice(new BigDecimal("150.00"));
        executedOrder.setTif("GTC");
        executedOrder.setFilledQty(BigDecimal.ZERO);
        
        when(orderExecutor.executeOrder(any())).thenReturn(executedOrder);

        TradingDtos.CreateOrderRequest request = new TradingDtos.CreateOrderRequest(
                "AAPL", TradingDtos.OrderSide.BUY, TradingDtos.OrderType.LIMIT,
                "100", "150.00", TradingDtos.TimeInForce.GTC, "idem-key"
        );

        // When
        TradingDtos.OrderResponse response = orderService.createOrder(userId, request, "idem-key");

        // Then
        assertThat(response).isNotNull();
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void shouldReturnExistingOrderForIdempotentRequest() {
        // Given
        when(orderRepository.countByUserIdAndStatusIn(any(), any()))
                .thenReturn(50L);

        Order existingOrder = new Order();
        existingOrder.setId(UUID.randomUUID());
        existingOrder.setUserId(userId);
        existingOrder.setSymbol("AAPL");
        existingOrder.setSide("BUY");
        existingOrder.setType("LIMIT");
        existingOrder.setStatus("NEW");
        existingOrder.setQty(new BigDecimal("100"));
        existingOrder.setLimitPrice(new BigDecimal("150.00"));
        existingOrder.setTif("GTC");
        existingOrder.setFilledQty(BigDecimal.ZERO);
        existingOrder.setIdempotencyKey("idem-key");
        
        when(orderRepository.findByUserIdAndIdempotencyKey(userId, "idem-key"))
                .thenReturn(Optional.of(existingOrder));

        TradingDtos.CreateOrderRequest request = new TradingDtos.CreateOrderRequest(
                "AAPL", TradingDtos.OrderSide.BUY, TradingDtos.OrderType.LIMIT,
                "100", "150.00", TradingDtos.TimeInForce.GTC, "idem-key"
        );

        // When
        TradingDtos.OrderResponse response = orderService.createOrder(userId, request, "idem-key");

        // Then
        assertThat(response).isNotNull();
        assertThat(response.orderId()).isEqualTo(existingOrder.getId().toString());
        verify(orderExecutor, never()).executeOrder(any());
    }
}
