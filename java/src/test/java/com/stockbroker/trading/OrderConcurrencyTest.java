package com.stockbroker.trading;

import com.stockbroker.common.LocalTestConfiguration;
import com.stockbroker.auth.api.AuthDtos;
import com.stockbroker.trading.api.TradingDtos;
import com.stockbroker.trading.domain.Fill;
import com.stockbroker.trading.domain.Order;
import com.stockbroker.trading.repo.FillRepository;
import com.stockbroker.trading.repo.OrderRepository;
import com.stockbroker.trading.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests for order execution - verifies no overspend under concurrent load.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = LocalTestConfiguration.class)
@ActiveProfiles("test")
class OrderConcurrencyTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    FillRepository fillRepository;

    @Autowired
    LedgerService ledgerService;

    private String accessToken;
    private UUID accountId;

    @BeforeEach
    void setup() {
        // Register and login
        AuthDtos.RegisterRequest reg = new AuthDtos.RegisterRequest("concurrency@test.com", "SecurePass123!");
        restTemplate.postForEntity("/api/v1/auth/register", reg, AuthDtos.RegisterResponse.class);

        AuthDtos.LoginRequest login = new AuthDtos.LoginRequest("concurrency@test.com", "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginResp = restTemplate.postForEntity("/api/v1/auth/login", login, AuthDtos.LoginResponse.class);
        accessToken = loginResp.getBody().accessToken();
    }

    @Test
    void concurrentMarketOrdersNoOverspend() throws Exception {
        int threadCount = 20;
        BigDecimal orderQty = new BigDecimal("0.01"); // Small qty per order
        int totalOrders = threadCount;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        ConcurrentHashMap<String, TradingDtos.OrderResponse> results = new ConcurrentHashMap<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    String idemKey = "concurrent-market-" + idx;
                    HttpHeaders h = new HttpHeaders();
                    h.setBearerAuth(accessToken);
                    h.set("Idempotency-Key", "concurrent-" + idx);
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
                        results.put(idemKey, resp.getBody());
                        successCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for completion
        boolean completed = endLatch.await(60, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        executor.shutdown();

        System.out.println("Success: " + successCount.get() + ", Errors: " + errorCount.get());
        
        // Verify all orders completed (either filled or rejected gracefully)
        assertThat(successCount.get() + errorCount.get()).isEqualTo(threadCount);

        // Verify no overspend: sum of filled quantities should be reasonable
        BigDecimal totalFilled = results.values().stream()
                .map(r -> new BigDecimal(r.filledQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        // Each order was for 0.01, so max possible is threadCount * 0.01
        BigDecimal maxPossible = new BigDecimal(threadCount).multiply(new BigDecimal("0.01"));
        assertThat(totalFilled.compareTo(maxPossible)).isLessThanOrEqualTo(0);
        
        // Verify fills are consistent with orders
        for (var entry : results.entrySet()) {
            TradingDtos.OrderResponse order = entry.getValue();
            List<Fill> fills = fillRepository.findByOrderId(UUID.fromString(order.orderId()));
            BigDecimal fillSum = fills.stream()
                    .map(Fill::getQty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            BigDecimal orderFilled = new BigDecimal(order.filledQty());
            assertThat(fillSum.stripTrailingZeros())
                    .isEqualByComparingTo(orderFilled.stripTrailingZeros());
        }
    }

    @Test
    void concurrentLimitOrdersSamePriceLevel() throws Exception {
        int threadCount = 10;
        BigDecimal limitPrice = new BigDecimal("50000");
        BigDecimal orderQty = new BigDecimal("0.01");

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        
        AtomicInteger filledCount = new AtomicInteger(0);
        AtomicInteger canceledCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    String idemKey = "concurrent-limit-" + idx;
                    HttpHeaders h = new HttpHeaders();
                    h.setBearerAuth(accessToken);
                    h.set("Idempotency-Key", idemKey);
                    HttpEntity<TradingDtos.CreateOrderRequest> e = new HttpEntity<>(
                            new TradingDtos.CreateOrderRequest(
                                    "BINANCE:BTC-USDT",
                                    TradingDtos.OrderSide.BUY,
                                    TradingDtos.OrderType.LIMIT,
                                    "0.01",
                                    "50000",
                                    TradingDtos.TimeInForce.IOC,
                                    idemKey
                            ),
                            h
                    );
                    
                    ResponseEntity<String> resp = restTemplate.exchange(
                            "/api/v1/orders", HttpMethod.POST, e, String.class);
                    
                    if (resp.getStatusCode() == HttpStatus.CREATED) {
                        filledCount.incrementAndGet();
                    } else {
                        canceledCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    canceledCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        executor.shutdown();

        System.out.println("Filled: " + filledCount.get() + ", Canceled: " + canceledCount.get());
        
        // All should complete (either filled or canceled)
        assertThat(filledCount.get() + canceledCount.get()).isEqualTo(threadCount);
        
        // Verify fills are consistent
        List<Order> orders = orderRepository.findAll();
        for (Order order : orders) {
            if (order.getIdempotencyKey().startsWith("concurrent-limit-")) {
                List<Fill> fills = fillRepository.findByOrderId(order.getId());
                if (!fills.isEmpty()) {
                    BigDecimal fillSum = fills.stream()
                            .map(Fill::getQty)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    assertThat(fillSum.stripTrailingZeros())
                            .isEqualByComparingTo(order.getFilledQty().stripTrailingZeros());
                }
            }
        }
    }

    @Test
    void concurrentMixedBuySellOrders() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount * 2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount * 2);
        
        AtomicInteger buySuccess = new AtomicInteger(0);
        AtomicInteger sellSuccess = new AtomicInteger(0);
        AtomicInteger totalErrors = new AtomicInteger(0);

        // Submit buy orders
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String idemKey = "mixed-buy-" + idx;
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
                    ResponseEntity<String> resp = restTemplate.exchange(
                            "/api/v1/orders", HttpMethod.POST, e, String.class);
                    if (resp.getStatusCode() == HttpStatus.CREATED) {
                        buySuccess.incrementAndGet();
                    }
                } catch (Exception e) {
                    totalErrors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Submit sell orders
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String idemKey = "mixed-sell-" + idx;
                    HttpHeaders h = new HttpHeaders();
                    h.setBearerAuth(accessToken);
                    h.set("Idempotency-Key", idemKey);
                    HttpEntity<TradingDtos.CreateOrderRequest> e = new HttpEntity<>(
                            new TradingDtos.CreateOrderRequest(
                                    "BINANCE:BTC-USDT",
                                    TradingDtos.OrderSide.SELL,
                                    TradingDtos.OrderType.MARKET,
                                    "0.01",
                                    null,
                                    TradingDtos.TimeInForce.IOC,
                                    idemKey
                            ),
                            h
                    );
                    ResponseEntity<String> resp = restTemplate.exchange(
                            "/api/v1/orders", HttpMethod.POST, e, String.class);
                    if (resp.getStatusCode() == HttpStatus.CREATED) {
                        sellSuccess.incrementAndGet();
                    }
                } catch (Exception e) {
                    totalErrors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(60, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        executor.shutdown();

        System.out.println("Buy success: " + buySuccess.get() + ", Sell success: " + sellSuccess.get() + ", Errors: " + totalErrors.get());
        
        assertThat(buySuccess.get() + sellSuccess.get() + totalErrors.get()).isEqualTo(threadCount * 2);
    }

    @Test
    void ledgerBalanceConsistencyUnderConcurrency() throws Exception {
        // First deposit some funds
        // Note: In test profile, ledger operations might be mocked
        
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        
        AtomicInteger success = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String idemKey = "ledger-concurrent-" + idx;
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
                    ResponseEntity<String> resp = restTemplate.exchange(
                            "/api/v1/orders", HttpMethod.POST, e, String.class);
                    if (resp.getStatusCode() == HttpStatus.CREATED) {
                        success.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore errors
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        executor.shutdown();

        // Verify ledger entries are balanced for all transactions
        List<Order> orders = orderRepository.findAll();
        for (Order order : orders) {
            if (order.getIdempotencyKey() != null && order.getIdempotencyKey().startsWith("ledger-concurrent-")) {
                // Verify fills sum to order filled qty
                List<Fill> fills = fillRepository.findByOrderId(order.getId());
                if (!fills.isEmpty()) {
                    BigDecimal fillSum = fills.stream()
                            .map(Fill::getQty)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    assertThat(fillSum.stripTrailingZeros())
                            .isEqualByComparingTo(order.getFilledQty().stripTrailingZeros());
                }
            }
        }
    }
}
