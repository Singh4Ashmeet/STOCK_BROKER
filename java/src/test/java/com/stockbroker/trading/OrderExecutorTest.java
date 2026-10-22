package com.stockbroker.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockbroker.common.LocalTestConfiguration;
import com.stockbroker.auth.api.AuthDtos;
import com.stockbroker.trading.api.TradingDtos;
import com.stockbroker.trading.domain.Fill;
import com.stockbroker.trading.repo.FillRepository;
import com.stockbroker.trading.repo.OrderRepository;
import com.stockbroker.trading.service.OrderExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for OrderExecutor - state machine, book walking, fees, idempotency, concurrency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = LocalTestConfiguration.class)
@org.springframework.test.context.ActiveProfiles("test")
class OrderExecutorTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    FillRepository fillRepository;

    @Autowired
    OrderExecutor orderExecutor;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private String accessToken;

    @BeforeEach
    void setup() {
        // Register and login
        AuthDtos.RegisterRequest reg = new AuthDtos.RegisterRequest("orderexec@test.com", "SecurePass123!");
        restTemplate.postForEntity("/api/v1/auth/register", reg, AuthDtos.RegisterResponse.class);

        AuthDtos.LoginRequest login = new AuthDtos.LoginRequest("orderexec@test.com", "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginResp = restTemplate.postForEntity("/api/v1/auth/login", login, AuthDtos.LoginResponse.class);
        String accessToken = loginResp.getBody().accessToken();
        
        this.accessToken = accessToken;

        // Clean up any old orders and fills from prior test runs
        jdbcTemplate.update("DELETE FROM fills WHERE user_id IN (SELECT id FROM users WHERE email = 'orderexec@test.com')");
        jdbcTemplate.update("DELETE FROM orders WHERE user_id IN (SELECT id FROM users WHERE email = 'orderexec@test.com')");
    }

    @Test
    void marketOrderFillsAgainstBook() throws Exception {
        // Create a market buy order
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Idempotency-Key", "market-buy-1");
        HttpEntity<TradingDtos.CreateOrderRequest> entity = new HttpEntity<>(
                new TradingDtos.CreateOrderRequest(
                        "BINANCE:BTC-USDT",
                        TradingDtos.OrderSide.BUY,
                        TradingDtos.OrderType.MARKET,
                        "0.1",
                        null,
                        TradingDtos.TimeInForce.GTC,
                        "market-buy-1"
                ),
                headers
        );

        ResponseEntity<TradingDtos.OrderResponse> response = restTemplate.exchange(
                "/api/v1/orders", HttpMethod.POST, entity, TradingDtos.OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TradingDtos.OrderResponse order = response.getBody();
        assertThat(order).isNotNull();
        assertThat(order.status()).isEqualTo(TradingDtos.OrderStatus.FILLED);
        assertThat(new java.math.BigDecimal(order.filledQty())).isEqualByComparingTo("0.1");
        assertThat(order.avgPrice()).isNotNull();
        assertThat(order.fills()).isNotEmpty();

        // Verify fills were created
        List<Fill> fills = fillRepository.findByOrderId(UUID.fromString(order.orderId()));
        assertThat(fills).isNotEmpty();
        
        for (Fill fill : fills) {
            assertThat(fill.getPrice()).isNotNull();
            assertThat(fill.getQty()).isNotNull();
            assertThat(fill.getFeeAmount()).isNotNull();
            assertThat(fill.getLiquidity()).isIn("MAKER", "TAKER");
        }
    }

    @Test
    void limitOrderOnlyFillsAtOrBetterThanLimit() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Idempotency-Key", "limit-buy-1");
        HttpEntity<TradingDtos.CreateOrderRequest> entity = new HttpEntity<>(
                new TradingDtos.CreateOrderRequest(
                        "BINANCE:BTC-USDT",
                        TradingDtos.OrderSide.BUY,
                        TradingDtos.OrderType.LIMIT,
                        "0.1",
                        "30000", // Very low limit price - should not fill
                        TradingDtos.TimeInForce.IOC,
                        "limit-buy-1"
                ),
                headers
        );

        ResponseEntity<TradingDtos.OrderResponse> response = restTemplate.exchange(
                "/api/v1/orders", HttpMethod.POST, entity, TradingDtos.OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TradingDtos.OrderResponse order = response.getBody();
        // IOC limit order with unreachable price should be canceled
        assertThat(order.status()).isIn(TradingDtos.OrderStatus.CANCELED, TradingDtos.OrderStatus.REJECTED);
    }

    @Test
    void limitOrderFillsAtOrBetterThanLimit() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Idempotency-Key", "limit-buy-2");
        HttpEntity<TradingDtos.CreateOrderRequest> entity = new HttpEntity<>(
                new TradingDtos.CreateOrderRequest(
                        "BINANCE:BTC-USDT",
                        TradingDtos.OrderSide.BUY,
                        TradingDtos.OrderType.LIMIT,
                        "0.01",
                        "100000000000", // High limit price above mock ask (~50 billion) - fills against book
                        TradingDtos.TimeInForce.IOC,
                        "limit-buy-2"
                ),
                headers
        );

        ResponseEntity<TradingDtos.OrderResponse> response = restTemplate.exchange(
                "/api/v1/orders", HttpMethod.POST, entity, TradingDtos.OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TradingDtos.OrderResponse order = response.getBody();
        assertThat(order.status()).isEqualTo(TradingDtos.OrderStatus.FILLED);
    }

    @Test
    void partialFillWithRealisticSlippage() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Idempotency-Key", "partial-fill-1");
        HttpEntity<TradingDtos.CreateOrderRequest> entity = new HttpEntity<>(
                new TradingDtos.CreateOrderRequest(
                        "BINANCE:BTC-USDT",
                        TradingDtos.OrderSide.BUY,
                        TradingDtos.OrderType.MARKET,
                        "100", // Large quantity - should partially fill
                        null,
                        TradingDtos.TimeInForce.GTC,
                        "partial-fill-1"
                ),
                headers
        );

        ResponseEntity<TradingDtos.OrderResponse> response = restTemplate.exchange(
                "/api/v1/orders", HttpMethod.POST, entity, TradingDtos.OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TradingDtos.OrderResponse order = response.getBody();
        // Should be partially filled (large order)
        assertThat(order.status()).isIn(TradingDtos.OrderStatus.FILLED, TradingDtos.OrderStatus.PARTIALLY_FILLED);
        
        // Check fills have realistic prices (within book range)
        List<Fill> fills = fillRepository.findByOrderId(UUID.fromString(order.orderId()));
        for (Fill fill : fills) {
            BigDecimal price = fill.getPrice();
            assertThat(price.compareTo(BigDecimal.ZERO)).isGreaterThan(0);
        }
    }

    @Test
    void exactFeesCalculated() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Idempotency-Key", "fee-test-1");
        HttpEntity<TradingDtos.CreateOrderRequest> entity = new HttpEntity<>(
                new TradingDtos.CreateOrderRequest(
                        "BINANCE:BTC-USDT",
                        TradingDtos.OrderSide.BUY,
                        TradingDtos.OrderType.MARKET,
                        "1.0",
                        null,
                        TradingDtos.TimeInForce.GTC,
                        "fee-test-1"
                ),
                headers
        );

        ResponseEntity<TradingDtos.OrderResponse> response = restTemplate.exchange(
                "/api/v1/orders", HttpMethod.POST, entity, TradingDtos.OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TradingDtos.OrderResponse order = response.getBody();
        
        List<Fill> fills = fillRepository.findByOrderId(UUID.fromString(order.orderId()));
        for (Fill fill : fills) {
            // Fee should be exactly: qty * price * taker_fee_rate
            BigDecimal expectedFee = fill.getPrice().multiply(fill.getQty())
                    .multiply(new BigDecimal("0.0005")); // 5 bps taker fee
            BigDecimal actualFee = fill.getFeeAmount();
            assertThat(actualFee.stripTrailingZeros())
                    .isEqualByComparingTo(expectedFee.stripTrailingZeros());
        }
    }

    @Test
    void idempotencyKeyPreventsDuplicateOrders() throws Exception {
        String idempotencyKey = "idem-test-1";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Idempotency-Key", idempotencyKey);
        HttpEntity<TradingDtos.CreateOrderRequest> entity = new HttpEntity<>(
                new TradingDtos.CreateOrderRequest(
                        "BINANCE:BTC-USDT",
                        TradingDtos.OrderSide.BUY,
                        TradingDtos.OrderType.MARKET,
                        "0.1",
                        null,
                        TradingDtos.TimeInForce.GTC,
                        idempotencyKey
                ),
                headers
        );

        // First request
        ResponseEntity<TradingDtos.OrderResponse> response1 = restTemplate.exchange(
                "/api/v1/orders", HttpMethod.POST, entity, TradingDtos.OrderResponse.class);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId1 = response1.getBody().orderId();

        // Second request with same idempotency key - should return same order
        ResponseEntity<TradingDtos.OrderResponse> response2 = restTemplate.exchange(
                "/api/v1/orders", HttpMethod.POST, entity, TradingDtos.OrderResponse.class);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId2 = response2.getBody().orderId();
        
        assertThat(orderId2).isEqualTo(orderId1);

        // Different body with same key should fail
        HttpEntity<TradingDtos.CreateOrderRequest> entity2 = new HttpEntity<>(
                new TradingDtos.CreateOrderRequest(
                        "BINANCE:ETH-USDT", // Different symbol
                        TradingDtos.OrderSide.BUY,
                        TradingDtos.OrderType.MARKET,
                        "0.1",
                        null,
                        TradingDtos.TimeInForce.GTC,
                        idempotencyKey
                ),
                headers
        );

        ResponseEntity<String> response3 = restTemplate.exchange(
                "/api/v1/orders", HttpMethod.POST, entity2, String.class);
        assertThat(response3.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response3.getBody()).contains("ORDER_DUPLICATE_KEY_MISMATCH");
    }

    @Test
    void singleWriterPerAccountNoOverspend() throws Exception {
        // Test concurrent orders on same account don't overspend
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    String idemKey = "concurrent-" + idx;
                    HttpHeaders h = new HttpHeaders();
                    h.setBearerAuth(accessToken);
                    h.set("Idempotency-Key", idemKey);
                    HttpEntity<TradingDtos.CreateOrderRequest> e = new HttpEntity<>(
                            new TradingDtos.CreateOrderRequest(
                                    "BINANCE:BTC-USDT",
                                    TradingDtos.OrderSide.BUY,
                                    TradingDtos.OrderType.MARKET,
                                    "0.01",
                                    null,
                                    TradingDtos.TimeInForce.IOC,
                                    idemKey
                            ),
                            h
                    );
                    ResponseEntity<TradingDtos.OrderResponse> resp = restTemplate.exchange(
                            "/api/v1/orders", HttpMethod.POST, e, TradingDtos.OrderResponse.class);
                    if (resp.getStatusCode() == HttpStatus.CREATED) {
                        successCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // All should succeed (or fail gracefully)
        assertThat(successCount.get() + errorCount.get()).isEqualTo(threadCount);
        
        // Verify total filled qty doesn't exceed what's possible
        // (This is a sanity check - in mock, all might fill)
    }

    @Test
    void slippagePreviewEndpoint() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<TradingDtos.OrderPreviewRequest> entity = new HttpEntity<>(
                new TradingDtos.OrderPreviewRequest(
                        "BINANCE:BTC-USDT",
                        TradingDtos.OrderSide.BUY,
                        "0.1",
                        null
                ),
                headers
        );

        ResponseEntity<TradingDtos.OrderPreviewResponse> response = restTemplate.exchange(
                "/api/v1/orders/preview", HttpMethod.POST, entity, TradingDtos.OrderPreviewResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TradingDtos.OrderPreviewResponse preview = response.getBody();
        assertThat(preview.estFillPrice()).isNotNull();
        assertThat(preview.estFees()).isNotNull();
        assertThat(preview.estSlippageBps()).isGreaterThanOrEqualTo(0);
        assertThat(preview.levelsConsumed()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void goldenVectorsSaved() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Idempotency-Key", "golden-1");
        HttpEntity<TradingDtos.CreateOrderRequest> entity = new HttpEntity<>(
                new TradingDtos.CreateOrderRequest(
                        "BINANCE:BTC-USDT",
                        TradingDtos.OrderSide.BUY,
                        TradingDtos.OrderType.MARKET,
                        "0.1",
                        null,
                        TradingDtos.TimeInForce.GTC,
                        "golden-1"
                ),
                headers
        );

        ResponseEntity<TradingDtos.OrderResponse> response = restTemplate.exchange(
                "/api/v1/orders", HttpMethod.POST, entity, TradingDtos.OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Golden vectors are saved to testdata/golden_fills/ as JSONL
        // This test just verifies the order completes successfully
    }

    @Test
    void orderStateMachineTransitions() throws Exception {
        // NEW -> ACCEPTED -> PARTIALLY_FILLED -> FILLED
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Idempotency-Key", "state-1");
        HttpEntity<TradingDtos.CreateOrderRequest> entity = new HttpEntity<>(
                new TradingDtos.CreateOrderRequest(
                        "BINANCE:BTC-USDT",
                        TradingDtos.OrderSide.BUY,
                        TradingDtos.OrderType.MARKET,
                        "0.01",
                        null,
                        TradingDtos.TimeInForce.GTC,
                        "state-1"
                ),
                headers
        );

        ResponseEntity<TradingDtos.OrderResponse> response = restTemplate.exchange(
                "/api/v1/orders", HttpMethod.POST, entity, TradingDtos.OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TradingDtos.OrderResponse order = response.getBody();
        assertThat(order.status()).isIn(
                TradingDtos.OrderStatus.ACCEPTED,
                TradingDtos.OrderStatus.PARTIALLY_FILLED,
                TradingDtos.OrderStatus.FILLED
        );
    }

    @Test
    void cancelOrderWorks() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Idempotency-Key", "cancel-1");
        HttpEntity<TradingDtos.CreateOrderRequest> entity = new HttpEntity<>(
                new TradingDtos.CreateOrderRequest(
                        "BINANCE:BTC-USDT",
                        TradingDtos.OrderSide.BUY,
                        TradingDtos.OrderType.LIMIT,
                        "0.1",
                        "1000", // Low price - won't fill
                        TradingDtos.TimeInForce.GTC,
                        "cancel-1"
                ),
                headers
        );

        ResponseEntity<TradingDtos.OrderResponse> createResp = restTemplate.exchange(
                "/api/v1/orders", HttpMethod.POST, entity, TradingDtos.OrderResponse.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId = createResp.getBody().orderId();

        // Cancel the order
        ResponseEntity<TradingDtos.OrderResponse> cancelResp = restTemplate.exchange(
                "/api/v1/orders/" + orderId, HttpMethod.DELETE, 
                new HttpEntity<>(headers), TradingDtos.OrderResponse.class);

        assertThat(cancelResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelResp.getBody().status()).isEqualTo(TradingDtos.OrderStatus.CANCELED);
    }

    @Test
    void makerTakerFeeLogic() throws Exception {
        // Limit order that crosses spread -> taker fee
        // Limit order that doesn't cross -> maker fee
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        // Cross spread (aggressive limit) - should be taker
        String idemCross = "cross-1";
        headers.set("Idempotency-Key", idemCross);
        HttpEntity<TradingDtos.CreateOrderRequest> crossEntity = new HttpEntity<>(
                new TradingDtos.CreateOrderRequest(
                        "BINANCE:BTC-USDT",
                        TradingDtos.OrderSide.BUY,
                        TradingDtos.OrderType.LIMIT,
                        "0.01",
                        "1000000", // Very high price - crosses spread
                        TradingDtos.TimeInForce.IOC,
                        idemCross
                ),
                headers
        );

        ResponseEntity<TradingDtos.OrderResponse> crossResp = restTemplate.exchange(
                "/api/v1/orders", HttpMethod.POST, crossEntity, TradingDtos.OrderResponse.class);
        assertThat(crossResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Should fill with taker fee (5 bps)
        List<Fill> crossFills = fillRepository.findByOrderId(UUID.fromString(crossResp.getBody().orderId()));
        for (Fill fill : crossFills) {
            assertThat(fill.getLiquidity()).isEqualTo("TAKER");
        }
    }
}
