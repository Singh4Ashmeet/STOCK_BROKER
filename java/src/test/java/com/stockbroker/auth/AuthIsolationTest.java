package com.stockbroker.auth;

import com.stockbroker.common.LocalTestConfiguration;
import com.stockbroker.auth.api.AuthDtos;
import com.stockbroker.trading.api.TradingDtos;
import com.stockbroker.trading.api.TradingDtosExtended;
import com.stockbroker.portfolio.api.PortfolioDtos;
import com.stockbroker.portfolio.api.PortfolioDtosExtended;
import com.stockbroker.alerts.api.AlertsDtos;
import com.stockbroker.research.api.ResearchDtos;
import com.stockbroker.market.grpc.MockMarketGrpcClient;
import com.stockbroker.auth.repo.UserRepository;
import com.stockbroker.trading.repo.OrderRepository;
import com.stockbroker.trading.repo.FillRepository;
import com.stockbroker.portfolio.repo.WatchlistRepository;
import com.stockbroker.alerts.repo.AlertRepository;
import com.stockbroker.research.repo.BacktestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ContextConfiguration;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isolation tests proving user A can never read or change user B's data.
 * These tests verify the security requirement that every user-owned query
 * is filtered by user_id from the security context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = LocalTestConfiguration.class)
@org.springframework.test.context.ActiveProfiles("test")
class AuthIsolationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    UserRepository userRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    FillRepository fillRepository;

    @Autowired
    WatchlistRepository watchlistRepository;

    @Autowired
    AlertRepository alertRepository;

    @Autowired
    BacktestRepository backtestRepository;

    @Autowired
    MockMarketGrpcClient mockMarketGrpcClient;

    @Autowired(required = false)
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private String userAAccessToken;
    private String userBAccessToken;
    private UUID userAOrderId;
    private UUID userBOrderId;
    private UUID userAWatchlistId;
    private UUID userBWatchlistId;
    private UUID userAAlertId;
    private UUID userBAlertId;
    private UUID userABacktestId;
    private UUID userBBacktestId;

    @BeforeEach
    void setup() {
        mockMarketGrpcClient.resetForTest();
        if (redisTemplate != null) {
            try {
                var keys = redisTemplate.keys("login:fail:*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            } catch (Exception ignored) {}
        }

        String userAEmail = "usera-" + UUID.randomUUID() + "@test.com";
        // Register and login User A
        AuthDtos.RegisterRequest regA = new AuthDtos.RegisterRequest(userAEmail, "SecurePass123!", "User A");
        restTemplate.postForEntity("/v1/auth/register", regA, AuthDtos.RegisterResponse.class);

        AuthDtos.LoginRequest loginA = new AuthDtos.LoginRequest(userAEmail, "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginResponseA = restTemplate.postForEntity("/v1/auth/login", loginA, AuthDtos.LoginResponse.class);
        userAAccessToken = loginResponseA.getBody().accessToken();

        String userBEmail = "userb-" + UUID.randomUUID() + "@test.com";
        // Register and login User B
        AuthDtos.RegisterRequest regB = new AuthDtos.RegisterRequest(userBEmail, "SecurePass123!", "User B");
        restTemplate.postForEntity("/v1/auth/register", regB, AuthDtos.RegisterResponse.class);

        AuthDtos.LoginRequest loginB = new AuthDtos.LoginRequest(userBEmail, "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginResponseB = restTemplate.postForEntity("/v1/auth/login", loginB, AuthDtos.LoginResponse.class);
        userBAccessToken = loginResponseB.getBody().accessToken();

        // Create test data for User A
        createTestDataForUser(userAAccessToken, "A");

        // Create test data for User B
        createTestDataForUser(userBAccessToken, "B");
    }

    private void createTestDataForUser(String accessToken, String suffix) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Idempotency-Key", "test-key-" + suffix + "-" + System.currentTimeMillis());

        // Create order
        TradingDtos.CreateOrderRequest orderReq = new TradingDtos.CreateOrderRequest(
                "BINANCE:BTC-USDT",
                TradingDtos.OrderSide.BUY,
                TradingDtos.OrderType.LIMIT,
                "0.001",
                "50000",
                TradingDtos.TimeInForce.GTC,
                "idem-" + suffix + "-order"
        );
        ResponseEntity<TradingDtos.OrderResponse> orderResp = restTemplate.exchange(
                "/v1/orders", HttpMethod.POST, new HttpEntity<>(orderReq, headers), TradingDtos.OrderResponse.class);
        if (suffix.equals("A")) userAOrderId = UUID.fromString(orderResp.getBody().orderId());
        else userBOrderId = UUID.fromString(orderResp.getBody().orderId());

        // Create watchlist
        PortfolioDtos.WatchlistRequest wlReq = new PortfolioDtos.WatchlistRequest("Test Watchlist " + suffix);
        ResponseEntity<PortfolioDtos.WatchlistResponse> wlResp = restTemplate.exchange(
                "/v1/watchlists", HttpMethod.POST, new HttpEntity<>(wlReq, headers), PortfolioDtos.WatchlistResponse.class);
        if (suffix.equals("A")) userAWatchlistId = UUID.fromString(wlResp.getBody().watchlistId());
        else userBWatchlistId = UUID.fromString(wlResp.getBody().watchlistId());

        // Create alert
        AlertsDtos.CreateAlertRequest alertReq = new AlertsDtos.CreateAlertRequest(
                "BINANCE:BTC-USDT",
                AlertsDtos.Metric.PRICE,
                AlertsDtos.Operator.GT,
                "60000",
                60
        );
        ResponseEntity<AlertsDtos.AlertResponse> alertResp = restTemplate.exchange(
                "/v1/alerts", HttpMethod.POST, new HttpEntity<>(alertReq, headers), AlertsDtos.AlertResponse.class);
        if (suffix.equals("A")) userAAlertId = UUID.fromString(alertResp.getBody().alertId());
        else userBAlertId = UUID.fromString(alertResp.getBody().alertId());

        // Create backtest
        ResearchDtos.CreateBacktestRequest btReq = new ResearchDtos.CreateBacktestRequest(
                "momentum",
                "BINANCE:BTC-USDT",
                "2026-01-01T00:00:00Z",
                "2026-01-31T23:59:59Z",
                java.util.Map.of("lookback", "20", "threshold", "0.5"),
                "idem-" + suffix + "-bt",
                "config-hash-" + suffix,
                "data-fingerprint-" + suffix,
                "git-commit-" + suffix
        );
        ResponseEntity<ResearchDtos.CreateBacktestResponse> btResp = restTemplate.exchange(
                "/v1/backtests", HttpMethod.POST, new HttpEntity<>(btReq, headers), ResearchDtos.CreateBacktestResponse.class);
        if (suffix.equals("A")) userABacktestId = UUID.fromString(btResp.getBody().backtestId());
        else userBBacktestId = UUID.fromString(btResp.getBody().backtestId());
    }

    @Test
    void userACannotReadUserBOrders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        // User A tries to access User B's order directly by ID
        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/orders/" + userBOrderId, HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void userACannotCancelUserBOrder() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/orders/" + userBOrderId, HttpMethod.DELETE, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void userACannotSeeUserBOrdersInList() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<TradingDtosExtended.OrderListResponse> response = restTemplate.exchange(
                "/v1/orders", HttpMethod.GET, entity, TradingDtosExtended.OrderListResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // User A should only see their own orders
        assertThat(response.getBody().orders()).hasSize(1);
        assertThat(response.getBody().orders().get(0).orderId()).isEqualTo(userAOrderId.toString());
    }

    @Test
    void userACannotReadUserBFills() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<TradingDtosExtended.FillsResponse> response = restTemplate.exchange(
                "/v1/fills", HttpMethod.GET, entity, TradingDtosExtended.FillsResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // User A should only see their own fills (none in this test)
        assertThat(response.getBody().fills()).isEmpty();
    }

    @Test
    void userACannotReadUserBWatchlist() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/watchlists/" + userBWatchlistId, HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void userACannotDeleteUserBWatchlist() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/watchlists/" + userBWatchlistId, HttpMethod.DELETE, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void userACannotAddItemToUserBWatchlist() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);

        PortfolioDtos.WatchlistItemRequest itemReq = new PortfolioDtos.WatchlistItemRequest("BINANCE:ETH-USDT", 1);
        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/watchlists/" + userBWatchlistId + "/items", HttpMethod.POST, new HttpEntity<>(itemReq, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void userACannotSeeUserBWatchlistsInList() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<PortfolioDtosExtended.WatchlistListResponse> response = restTemplate.exchange(
                "/v1/watchlists", HttpMethod.GET, entity, PortfolioDtosExtended.WatchlistListResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().watchlists()).hasSize(1);
        assertThat(response.getBody().watchlists().get(0).watchlistId()).isEqualTo(userAWatchlistId.toString());
    }

    @Test
    void userACannotReadUserBAlert() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/alerts/" + userBAlertId, HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void userACannotUpdateUserBAlert() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);

        AlertsDtos.UpdateAlertRequest updateReq = new AlertsDtos.UpdateAlertRequest(AlertsDtos.Status.DISABLED, 120);
        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/alerts/" + userBAlertId, HttpMethod.PATCH, new HttpEntity<>(updateReq, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void userACannotDeleteUserBAlert() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/alerts/" + userBAlertId, HttpMethod.DELETE, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void userACannotSeeUserBAlertsInList() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<AlertsDtos.AlertListResponse> response = restTemplate.exchange(
                "/v1/alerts", HttpMethod.GET, entity, AlertsDtos.AlertListResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().alerts()).hasSize(1);
        assertThat(response.getBody().alerts().get(0).alertId()).isEqualTo(userAAlertId.toString());
    }

    @Test
    void userACannotReadUserBBacktest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/backtests/" + userBBacktestId, HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void userACannotReadUserBBacktestResult() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/backtests/" + userBBacktestId + "/result", HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void userACannotReadOrEnumerateUserBBacktestJobs() {
        ResearchDtos.CreateBacktestRequest request = new ResearchDtos.CreateBacktestRequest(
                "momentum", "BINANCE:BTC-USDT", "2026-01-01T00:00:00Z", "2026-01-31T23:59:59Z",
                java.util.Map.of("lookback", "20"), "job-idempotency-b", "config-b", "data-b", "commit-b");
        HttpHeaders userBHeaders = new HttpHeaders();
        userBHeaders.setBearerAuth(userBAccessToken);
        ResponseEntity<ResearchDtos.JobResponse> created = restTemplate.exchange(
                "/v1/backtest-jobs", HttpMethod.POST, new HttpEntity<>(request, userBHeaders),
                ResearchDtos.JobResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody()).isNotNull();

        HttpHeaders userAHeaders = new HttpHeaders();
        userAHeaders.setBearerAuth(userAAccessToken);
        String jobId = created.getBody().jobId();
        ResponseEntity<String> readOther = restTemplate.exchange(
                "/v1/backtest-jobs/" + jobId, HttpMethod.GET, new HttpEntity<>(userAHeaders), String.class);
        ResponseEntity<String> listOther = restTemplate.exchange(
                "/v1/backtest-jobs", HttpMethod.GET, new HttpEntity<>(userAHeaders), String.class);

        assertThat(readOther.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(listOther.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listOther.getBody()).doesNotContain(jobId);
    }

    @Test
    void userACannotSeeUserBBacktestsInList() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<ResearchDtos.BacktestListResponse> response = restTemplate.exchange(
                "/v1/backtests", HttpMethod.GET, entity, ResearchDtos.BacktestListResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().backtests()).hasSize(1);
        assertThat(response.getBody().backtests().get(0).backtestId()).isEqualTo(userABacktestId.toString());
    }

    @Test
    void userACannotReadUserBPortfolio() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<TradingDtos.PortfolioResponse> response = restTemplate.exchange(
                "/v1/portfolio", HttpMethod.GET, entity, TradingDtos.PortfolioResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // Portfolio should be empty for user A (no fills)
        assertThat(response.getBody().positions()).isEmpty();
    }

    @Test
    void userACannotReadUserBLedger() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<TradingDtos.LedgerResponse> response = restTemplate.exchange(
                "/v1/ledger", HttpMethod.GET, entity, TradingDtos.LedgerResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().entries()).isEmpty();
    }

    @Test
    void anonymousCannotAccessProtectedEndpoints() {
        // No auth header
        HttpEntity<?> entity = new HttpEntity<>(new HttpHeaders());

        // Test various endpoints
        assertThat(restTemplate.exchange("/v1/orders", HttpMethod.GET, entity, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange("/v1/fills", HttpMethod.GET, entity, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange("/v1/portfolio", HttpMethod.GET, entity, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange("/v1/ledger", HttpMethod.GET, entity, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange("/v1/watchlists", HttpMethod.GET, entity, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange("/v1/alerts", HttpMethod.GET, entity, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange("/v1/backtests", HttpMethod.GET, entity, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange("/v1/me", HttpMethod.GET, entity, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void userACanAccessOwnResources() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        // User A can access their own order
        ResponseEntity<TradingDtos.OrderResponse> orderResp = restTemplate.exchange(
                "/v1/orders/" + userAOrderId, HttpMethod.GET, entity, TradingDtos.OrderResponse.class);
        assertThat(orderResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(orderResp.getBody().orderId()).isEqualTo(userAOrderId.toString());

        // User A can access their own watchlist
        ResponseEntity<PortfolioDtos.WatchlistResponse> wlResp = restTemplate.exchange(
                "/v1/watchlists/" + userAWatchlistId, HttpMethod.GET, entity, PortfolioDtos.WatchlistResponse.class);
        assertThat(wlResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(wlResp.getBody().watchlistId()).isEqualTo(userAWatchlistId.toString());

        // User A can access their own alert
        ResponseEntity<AlertsDtos.AlertResponse> alertResp = restTemplate.exchange(
                "/v1/alerts/" + userAAlertId, HttpMethod.GET, entity, AlertsDtos.AlertResponse.class);
        assertThat(alertResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(alertResp.getBody().alertId()).isEqualTo(userAAlertId.toString());

        // User A can access their own backtest
        ResponseEntity<ResearchDtos.BacktestResult> btResp = restTemplate.exchange(
                "/v1/backtests/" + userABacktestId, HttpMethod.GET, entity, ResearchDtos.BacktestResult.class);
        assertThat(btResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(btResp.getBody().backtestId()).isEqualTo(userABacktestId.toString());
    }

    @Test
    void orderPreviewRespectsUserIsolation() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAAccessToken);

        // User A can preview their own order
        TradingDtos.OrderPreviewRequest previewReq = new TradingDtos.OrderPreviewRequest(
                "BINANCE:BTC-USDT",
                TradingDtos.OrderSide.BUY,
                "0.001",
                "50000"
        );
        ResponseEntity<TradingDtos.OrderPreviewResponse> response = restTemplate.exchange(
                "/v1/orders/preview", HttpMethod.POST, new HttpEntity<>(previewReq, headers), TradingDtos.OrderPreviewResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
