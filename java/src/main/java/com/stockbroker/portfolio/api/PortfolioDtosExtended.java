package com.stockbroker.portfolio.api;

import java.util.List;

/**
 * Additional portfolio DTOs for list responses.
 */
public final class PortfolioDtosExtended {

    private PortfolioDtosExtended() {}

    public record WatchlistListResponse(
            List<PortfolioDtos.WatchlistResponse> watchlists
    ) {}
}