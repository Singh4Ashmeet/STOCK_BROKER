package com.stockbroker.portfolio.api;

import com.stockbroker.common.error.BusinessProblem;
import com.stockbroker.common.security.CurrentUser;
import com.stockbroker.portfolio.service.WatchlistService;
import com.stockbroker.portfolio.api.PortfolioDtos;
import com.stockbroker.portfolio.api.PortfolioDtosExtended;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Watchlist REST endpoints (CRUD for watchlists and items).
 */
@RestController
@RequestMapping("/api/v1/watchlists")
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PortfolioDtosExtended.WatchlistListResponse> listWatchlists() {
        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.ok(watchlistService.listWatchlists(userId));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PortfolioDtos.WatchlistResponse> createWatchlist(
            @Valid @RequestBody PortfolioDtos.WatchlistRequest request) {
        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.status(201).body(watchlistService.createWatchlist(userId, request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PortfolioDtos.WatchlistResponse> getWatchlist(@PathVariable String id) {
        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.ok(watchlistService.getWatchlist(userId, UUID.fromString(id)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteWatchlist(@PathVariable String id) {
        UUID userId = CurrentUser.getUserId();
        watchlistService.deleteWatchlist(userId, UUID.fromString(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/items")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PortfolioDtos.WatchlistItemResponse> addWatchlistItem(
            @PathVariable String id,
            @Valid @RequestBody PortfolioDtos.WatchlistItemRequest request) {
        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.status(201).body(watchlistService.addItem(userId, UUID.fromString(id), request));
    }

    @DeleteMapping("/{id}/items/{symbol}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> removeWatchlistItem(
            @PathVariable String id,
            @PathVariable String symbol) {
        UUID userId = CurrentUser.getUserId();
        watchlistService.removeItem(userId, UUID.fromString(id), symbol);
        return ResponseEntity.noContent().build();
    }
}