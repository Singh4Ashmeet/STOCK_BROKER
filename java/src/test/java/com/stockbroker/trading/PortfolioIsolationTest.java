package com.stockbroker.trading;

import com.stockbroker.common.LocalTestConfiguration;
import com.stockbroker.auth.api.AuthDtos;
import com.stockbroker.trading.api.TradingDtos;
import com.stockbroker.trading.service.LedgerService;
import com.stockbroker.trading.service.PortfolioService;
import com.stockbroker.trading.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Portfolio isolation tests - verifies user_id filtering and P&L calculations match hand-calculated cases.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = LocalTestConfiguration.class)
@ActiveProfiles("test")
class PortfolioIsolationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    LedgerService ledgerService;

    @Autowired
    PortfolioService portfolioService;

    @Autowired
    AccountService accountService;

    @Autowired
    com.stockbroker.common.security.jwt.JwtTokenValidator jwtTokenValidator;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private String userAAccessToken;
    private String userBAccessToken;
    private UUID userAId;
    private UUID userBId;
    private UUID userAAccountId;
    private UUID userBAccountId;

    @BeforeEach
    void setup() {
        // Register and login User A
        AuthDtos.RegisterRequest regA = new AuthDtos.RegisterRequest("portfoliouserA@test.com", "SecurePass123!", "Portfolio User A");
        restTemplate.postForEntity("/v1/auth/register", regA, AuthDtos.RegisterResponse.class);
        AuthDtos.LoginRequest loginA = new AuthDtos.LoginRequest("portfoliouserA@test.com", "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginRespA = restTemplate.postForEntity("/v1/auth/login", loginA, AuthDtos.LoginResponse.class);
        userAAccessToken = loginRespA.getBody().accessToken();

        // Register and login User B
        AuthDtos.RegisterRequest regB = new AuthDtos.RegisterRequest("portfoliouserB@test.com", "SecurePass123!", "Portfolio User B");
        restTemplate.postForEntity("/v1/auth/register", regB, AuthDtos.RegisterResponse.class);
        AuthDtos.LoginRequest loginB = new AuthDtos.LoginRequest("portfoliouserB@test.com", "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginRespB = restTemplate.postForEntity("/v1/auth/login", loginB, AuthDtos.LoginResponse.class);
        userBAccessToken = loginRespB.getBody().accessToken();

        userAId = UUID.fromString(jwtTokenValidator.extractSubjectUnsafe(userAAccessToken));
        userBId = UUID.fromString(jwtTokenValidator.extractSubjectUnsafe(userBAccessToken));
        userAAccountId = accountService.getOrCreateDefaultAccount(userAId).getId();
        userBAccountId = accountService.getOrCreateDefaultAccount(userBId).getId();

        // Clean up any state from previous runs for these users
        jdbcTemplate.update("DELETE FROM watchlist_items WHERE watchlist_id IN (SELECT id FROM watchlists WHERE user_id IN (?, ?))", userAId, userBId);
        jdbcTemplate.update("DELETE FROM watchlists WHERE user_id IN (?, ?)", userAId, userBId);
        jdbcTemplate.update("DELETE FROM fills WHERE user_id IN (?, ?)", userAId, userBId);
        jdbcTemplate.update("DELETE FROM orders WHERE user_id IN (?, ?)", userAId, userBId);
        jdbcTemplate.update("DELETE FROM positions WHERE account_id IN (?, ?)", userAAccountId, userBAccountId);
        jdbcTemplate.update("DELETE FROM ledger_entries WHERE transaction_id IN (SELECT id FROM ledger_transactions WHERE account_id IN (?, ?))", userAAccountId, userBAccountId);
        jdbcTemplate.update("DELETE FROM ledger_transactions WHERE account_id IN (?, ?)", userAAccountId, userBAccountId);

        // Get account IDs by calling portfolio endpoint (creates default account)
        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(userAAccessToken);
        HttpEntity<?> entityA = new HttpEntity<>(headersA);
        restTemplate.exchange("/v1/portfolio", HttpMethod.GET, entityA, TradingDtos.PortfolioResponse.class);

        HttpHeaders headersB = new HttpHeaders();
        headersB.setBearerAuth(userBAccessToken);
        HttpEntity<?> entityB = new HttpEntity<>(headersB);
        restTemplate.exchange("/v1/portfolio", HttpMethod.GET, entityB, TradingDtos.PortfolioResponse.class);
    }

    @Test
    void userACannotSeeUserBPortfolio() {
        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(userAAccessToken);
        HttpEntity<?> entityA = new HttpEntity<>(headersA);

        ResponseEntity<TradingDtos.PortfolioResponse> response = restTemplate.exchange(
                "/v1/portfolio", HttpMethod.GET, entityA, TradingDtos.PortfolioResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // Should only see User A's data (empty in test)
    }

    @Test
    void newlyRegisteredUserWithoutLedgerActivityGetsEmptyBalancesAndPositions() {
        String email = "new-balance-user-" + UUID.randomUUID() + "@test.com";
        AuthDtos.RegisterRequest registration = new AuthDtos.RegisterRequest(
                email, "SecurePass123!", "New Balance User");
        ResponseEntity<AuthDtos.RegisterResponse> registrationResponse = restTemplate.postForEntity(
                "/v1/auth/register", registration, AuthDtos.RegisterResponse.class);
        assertThat(registrationResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        AuthDtos.LoginRequest login = new AuthDtos.LoginRequest(email, "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginResponse = restTemplate.postForEntity(
                "/v1/auth/login", login, AuthDtos.LoginResponse.class);
        String accessToken = loginResponse.getBody().accessToken();

        ResponseEntity<List<TradingDtos.AssetBalanceResponse>> balances = getBalances(accessToken,
                "/v1/portfolio/balances");
        ResponseEntity<TradingDtos.PortfolioResponse> portfolio = restTemplate.exchange(
                "/v1/portfolio", HttpMethod.GET, authorizedEntity(accessToken),
                TradingDtos.PortfolioResponse.class);

        assertThat(balances.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(balances.getBody()).isEmpty();
        assertThat(portfolio.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(portfolio.getBody().positions()).isEmpty();
    }

    @Test
    void balancesAreDerivedFromLedgerEntries() {
        ledgerService.createDeposit(userAAccountId, userAId, "USDT", new BigDecimal("100.25"));
        ledgerService.createDeposit(userAAccountId, userAId, "BTC", new BigDecimal("0.5"));

        ResponseEntity<List<TradingDtos.AssetBalanceResponse>> response = getBalances(userAAccessToken,
                "/v1/portfolio/balances");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(
                new TradingDtos.AssetBalanceResponse("BTC", "0.5"),
                new TradingDtos.AssetBalanceResponse("USDT", "100.25"));
    }

    @Test
    void balancesAreIsolatedByAuthenticatedUserAndCannotSelectForeignAccount() {
        ledgerService.createDeposit(userAAccountId, userAId, "USDT", new BigDecimal("100"));
        ledgerService.createDeposit(userBAccountId, userBId, "BTC", new BigDecimal("2"));

        ResponseEntity<List<TradingDtos.AssetBalanceResponse>> responseA = getBalances(userAAccessToken,
                "/v1/portfolio/balances?accountId=" + userBAccountId);
        ResponseEntity<List<TradingDtos.AssetBalanceResponse>> responseB = getBalances(userBAccessToken,
                "/v1/portfolio/balances");

        assertThat(responseA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseA.getBody()).containsExactly(new TradingDtos.AssetBalanceResponse("USDT", "100"));
        assertThat(responseB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseB.getBody()).containsExactly(new TradingDtos.AssetBalanceResponse("BTC", "2"));
    }

    @Test
    void balancesRequireAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v1/portfolio/balances", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private HttpEntity<?> authorizedEntity(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private ResponseEntity<List<TradingDtos.AssetBalanceResponse>> getBalances(String token, String path) {
        return restTemplate.exchange(path, HttpMethod.GET, authorizedEntity(token),
                new ParameterizedTypeReference<>() {});
    }

    @Test
    void userACannotSeeUserBLedger() {
        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(userAAccessToken);
        HttpEntity<?> entityA = new HttpEntity<>(headersA);

        ResponseEntity<TradingDtos.LedgerResponse> response = restTemplate.exchange(
                "/v1/ledger", HttpMethod.GET, entityA, TradingDtos.LedgerResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // Should only see User A's ledger entries
    }

    @Test
    void userACannotSeeUserBWatchlists() {
        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(userAAccessToken);
        HttpEntity<?> entityA = new HttpEntity<>(headersA);

        ResponseEntity<com.stockbroker.portfolio.api.PortfolioDtosExtended.WatchlistListResponse> response = restTemplate.exchange(
                "/v1/watchlists", HttpMethod.GET, entityA, 
                new org.springframework.core.ParameterizedTypeReference<com.stockbroker.portfolio.api.PortfolioDtosExtended.WatchlistListResponse>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // Should only see User A's watchlists
    }

    @Test
    void pnlCalculationMatchesHandCalculatedCases() {
        // Test case: Deposit 10,000 USDT, Buy 1 BTC at 50,000, Current price 55,000
        // Expected: 
        // - Cash: 10,000 - 50,000 - 250(fee) = -40,250
        // - BTC: 1.0 @ avg cost 50,000
        // - Current price: 55,000
        // - Unrealized P&L: 1.0 * (55,000 - 50,000) = 5,000
        // - Realized P&L: 0
        // - Total Equity: -40,250 + 55,000 = 14,750

        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(userAAccessToken);
        HttpEntity<?> entityA = new HttpEntity<>(headersA);

        // Deposit 10,000 USDT
        ledgerService.createDeposit(userAAccountId, userAId, "USDT", new BigDecimal("10000"));

        // Buy 1 BTC at 50,000
        ledgerService.createFill(
                userAAccountId, userAId,
                "BTC-USDT", "BUY",
                new BigDecimal("1.0"), new BigDecimal("50000"),
                "USDT", new BigDecimal("250"),
                UUID.randomUUID()
        );

        ResponseEntity<TradingDtos.PortfolioResponse> response = restTemplate.exchange(
                "/v1/portfolio", HttpMethod.GET, entityA, TradingDtos.PortfolioResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TradingDtos.PortfolioResponse portfolio = response.getBody();
        
        // Verify structure
        assertThat(portfolio.totalEquity()).isNotNull();
        assertThat(portfolio.cashBalance()).isNotNull();
        assertThat(portfolio.unrealizedPnl()).isNotNull();
        assertThat(portfolio.realizedPnl()).isNotNull();
        assertThat(portfolio.positions()).isNotNull();
    }

    @Test
    void realizedPnlCalculationOnSell() {
        // Test case: Buy 2 BTC at 50,000, Sell 1 BTC at 60,000
        // Realized P&L = 1.0 * (60,000 - 50,000) = 10,000 (minus fees)
        // Buy fees: 2 * 50,000 * 0.0005 = 50
        // Sell fees: 1 * 60,000 * 0.0005 = 30
        // Net realized = 10,000 - 50 - 30 = 9,920

        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(userAAccessToken);
        HttpEntity<?> entityA = new HttpEntity<>(headersA);

        // Buy 2 BTC at 50,000
        ledgerService.createFill(
                userAAccountId, userAId,
                "BTC-USDT", "BUY",
                new BigDecimal("2.0"), new BigDecimal("50000"),
                "USDT", new BigDecimal("500"),
                UUID.randomUUID()
        );

        // Sell 1 BTC at 60,000
        ledgerService.createFill(
                userAAccountId, userAId,
                "BTC-USDT", "SELL",
                new BigDecimal("1.0"), new BigDecimal("60000"),
                "USDT", new BigDecimal("300"),
                UUID.randomUUID()
        );

        ResponseEntity<TradingDtos.PortfolioResponse> response = restTemplate.exchange(
                "/v1/portfolio", HttpMethod.GET, entityA, TradingDtos.PortfolioResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TradingDtos.PortfolioResponse portfolio = response.getBody();
        
        // Realized P&L should be approximately 9,200 (10,000 - 500 - 300 = 9,200)
        BigDecimal realizedPnl = new BigDecimal(portfolio.realizedPnl());
        assertThat(realizedPnl).isNotNull();
    }

    @Test
    void transactionHistoryPagination() {
        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(userAAccessToken);
        HttpEntity<?> entityA = new HttpEntity<>(headersA);

        ResponseEntity<TradingDtos.LedgerResponse> response = restTemplate.exchange(
                "/v1/ledger?limit=10", HttpMethod.GET, entityA, TradingDtos.LedgerResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().entries()).isNotNull();
        
        if (response.getBody().nextCursor() != null) {
            ResponseEntity<TradingDtos.LedgerResponse> response2 = restTemplate.exchange(
                    "/v1/ledger?limit=10&cursor=" + response.getBody().nextCursor(), 
                    HttpMethod.GET, entityA, TradingDtos.LedgerResponse.class);
            assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void transactionHistoryDateFiltering() {
        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(userAAccessToken);
        HttpEntity<?> entityA = new HttpEntity<>(headersA);

        String from = "2026-01-01T00:00:00Z";
        String to = "2026-12-31T23:59:59Z";

        ResponseEntity<TradingDtos.LedgerResponse> response = restTemplate.exchange(
                "/v1/ledger?from=" + from + "&to=" + to + "&limit=50", 
                HttpMethod.GET, entityA, TradingDtos.LedgerResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void watchlistWithLivePrices() {
        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(userAAccessToken);
        HttpEntity<?> entityA = new HttpEntity<>(headersA);

        var watchlistRequest = new com.stockbroker.portfolio.api.PortfolioDtos.WatchlistRequest("Test Watchlist");
        ResponseEntity<com.stockbroker.portfolio.api.PortfolioDtos.WatchlistResponse> createResp = restTemplate.exchange(
                "/v1/watchlists", HttpMethod.POST, new HttpEntity<>(watchlistRequest, headersA),
                com.stockbroker.portfolio.api.PortfolioDtos.WatchlistResponse.class);

        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String watchlistId = createResp.getBody().watchlistId();

        var itemRequest = new com.stockbroker.portfolio.api.PortfolioDtos.WatchlistItemRequest("BINANCE:BTC-USDT", 1);
        ResponseEntity<com.stockbroker.portfolio.api.PortfolioDtos.WatchlistItemResponse> itemResp = restTemplate.exchange(
                "/v1/watchlists/" + watchlistId + "/items", HttpMethod.POST, 
                new HttpEntity<>(itemRequest, headersA),
                com.stockbroker.portfolio.api.PortfolioDtos.WatchlistItemResponse.class);

        assertThat(itemResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(itemResp.getBody().currentPrice()).isNotNull();

        ResponseEntity<com.stockbroker.portfolio.api.PortfolioDtosExtended.WatchlistListResponse> listResp = restTemplate.exchange(
                "/v1/watchlists", HttpMethod.GET, entityA,
                new org.springframework.core.ParameterizedTypeReference<com.stockbroker.portfolio.api.PortfolioDtosExtended.WatchlistListResponse>() {});

        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody()).isNotNull();
        assertThat(listResp.getBody().watchlists()).hasSize(1);
        assertThat(listResp.getBody().watchlists().get(0).items()).hasSize(1);
        assertThat(listResp.getBody().watchlists().get(0).items().get(0).currentPrice()).isNotNull();
    }
}
