package com.stockbroker.portfolio.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Portfolio & Watchlist API DTOs.
 */
public final class PortfolioDtos {

    private PortfolioDtos() {}

    public record WatchlistRequest(
            @NotBlank @Size(max = 64) String name
    ) {}

    public record WatchlistResponse(
            @JsonProperty("watchlist_id") String watchlistId,
            String name,
            @JsonProperty("created_at") String createdAt,
            List<WatchlistItemResponse> items
    ) {}

    public record WatchlistItemRequest(
            @NotBlank String symbol,
            int position
    ) {}

    public record WatchlistItemResponse(
            String symbol,
            int position,
            @JsonProperty("current_price") String currentPrice,
            @JsonProperty("change_24h") String change24h
    ) {}
}