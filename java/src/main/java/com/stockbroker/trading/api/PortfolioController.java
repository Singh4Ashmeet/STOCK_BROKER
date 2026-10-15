package com.stockbroker.trading.api;

import com.stockbroker.common.error.BusinessProblem;
import com.stockbroker.common.security.CurrentUser;
import com.stockbroker.trading.service.PortfolioService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Portfolio REST endpoints (positions, P&L, transaction history).
 */
@RestController
@RequestMapping("/api/v1")
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping("/portfolio")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TradingDtos.PortfolioResponse> getPortfolio() {
        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.ok(portfolioService.getPortfolio(userId));
    }

    @GetMapping("/ledger")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TradingDtos.LedgerResponse> getLedger(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String cursor) {

        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.ok(portfolioService.getLedger(userId, from, to, limit, cursor));
    }
}