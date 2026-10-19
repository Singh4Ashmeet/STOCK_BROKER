package com.stockbroker.market.api;

import com.stockbroker.common.error.BusinessProblem;
import com.stockbroker.market.service.MarketDataService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Market data REST endpoints.
 */
@RestController
@RequestMapping("/api/v1")
public class MarketController {

    private final MarketDataService marketDataService;

    public MarketController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping("/instruments")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MarketDtos.ListInstrumentsResponse> listInstruments() {
        List<MarketDtos.InstrumentSpec> instruments = marketDataService.getAllInstruments();
        return ResponseEntity.ok(new MarketDtos.ListInstrumentsResponse(instruments));
    }

    @GetMapping("/market/{symbol}/snapshot")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MarketDtos.BookUpdate> getSnapshot(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int depth) {
        var snapshot = marketDataService.getSnapshot(symbol, depth);
        if (snapshot == null) {
            throw new BusinessProblem.InstrumentNotFound(symbol);
        }
        return ResponseEntity.ok(snapshot);
    }

    @GetMapping("/market/{symbol}/metrics")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MarketDtos.Metrics> getMetrics(@PathVariable String symbol) {
        var metrics = marketDataService.getMetrics(symbol);
        if (metrics == null) {
            throw new BusinessProblem.InstrumentNotFound(symbol);
        }
        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/market/{symbol}/trades")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<MarketDtos.Trade>> getTrades(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        List<MarketDtos.Trade> trades = marketDataService.getRecentTrades(symbol, limit);
        return ResponseEntity.ok(trades);
    }

    @GetMapping("/market/{symbol}/candles")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<MarketDtosExtended.Candle>> getCandles(
            @PathVariable String symbol,
            @RequestParam String interval,
            @RequestParam String from,
            @RequestParam String to) {
        // TODO: Implement ClickHouse query
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/system/health")
    public ResponseEntity<MarketDtos.HealthResponse> health() {
        // Public minimal health check
        return ResponseEntity.ok(new MarketDtos.HealthResponse(
                true,
                List.of(),
                "0.1.0"
        ));
    }

    @GetMapping("/system/latency")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MarketDtosExtended.LatencyResponse> latency() {
        // Detailed latency metrics for ADMIN
        return ResponseEntity.ok(marketDataService.getLatencyMetrics());
    }

    // WebSocket ticket is in AuthController
}