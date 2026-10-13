package com.stockbroker.market.api;

import java.util.List;

/**
 * Additional DTOs for candles and latency.
 */
public final class MarketDtosExtended {

    private MarketDtosExtended() {}

    public record Candle(
            String symbol,
            String interval,
            String openTime,
            String closeTime,
            String open,
            String high,
            String low,
            String close,
            String volume,
            long trades
    ) {}

    public record LatencyStage(
            String stage,
            double p50Ms,
            double p90Ms,
            double p95Ms,
            double p99Ms,
            long count
    ) {}

    public record LatencyResponse(
            List<LatencyStage> stages,
            long totalSubscriptions,
            long resyncCount,
            long droppedMessages
    ) {}
}