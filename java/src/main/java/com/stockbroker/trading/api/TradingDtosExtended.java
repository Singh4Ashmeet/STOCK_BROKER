package com.stockbroker.trading.api;

import java.util.List;

/**
 * Additional trading DTOs for list responses.
 */
public final class TradingDtosExtended {

    private TradingDtosExtended() {}

    public record OrderListResponse(
            List<TradingDtos.OrderResponse> orders,
            String nextCursor
    ) {}

    public record FillsResponse(
            List<TradingDtos.FillResponse> fills,
            String nextCursor
    ) {}
}