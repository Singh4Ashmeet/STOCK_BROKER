package com.stockbroker.research.api;

import com.stockbroker.common.error.BusinessProblem;
import com.stockbroker.common.security.CurrentUser;
import com.stockbroker.research.service.BacktestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Research & Backtesting REST endpoints.
 */
@RestController
@RequestMapping("/v1")
public class ResearchController {

    private final BacktestService backtestService;

    public ResearchController(BacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @GetMapping("/strategies")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ResearchDtos.ListStrategiesResponse> listStrategies() {
        return ResponseEntity.ok(backtestService.listStrategies());
    }

    @PostMapping("/backtests")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ResearchDtos.CreateBacktestResponse> createBacktest(
            @Valid @RequestBody ResearchDtos.CreateBacktestRequest request,
            HttpServletRequest httpRequest) {

        // Extract idempotency key from header or body
        String idempotencyKey = httpRequest.getHeader("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = request.idempotencyKey();
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessProblem.IdempotencyKeyRequired();
        }

        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.status(202).body(backtestService.createBacktest(userId, request, idempotencyKey));
    }

    @GetMapping("/backtests")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ResearchDtos.BacktestListResponse> listBacktests(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String cursor) {
        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.ok(backtestService.listBacktests(userId, limit, cursor));
    }

    @GetMapping("/backtests/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ResearchDtos.BacktestResult> getBacktest(@PathVariable String id) {
        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.ok(backtestService.getBacktest(userId, UUID.fromString(id)));
    }

    @GetMapping("/backtests/{id}/result")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ResearchDtos.BacktestResult> getBacktestResult(@PathVariable String id) {
        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.ok(backtestService.getBacktestResult(userId, UUID.fromString(id)));
    }

    @GetMapping("/backtests/{id}/trades")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ResearchDtos.BacktestTradeLogResponse> getBacktestTrades(
            @PathVariable String id,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String cursor) {
        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.ok(backtestService.getTradeLog(userId, UUID.fromString(id), limit, cursor));
    }
}