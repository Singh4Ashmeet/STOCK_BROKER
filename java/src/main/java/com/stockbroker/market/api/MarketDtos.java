package com.stockbroker.market.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Market data API DTOs.
 */
public final class MarketDtos {

    private MarketDtos() {}

    public record InstrumentSpec(
            @JsonProperty("symbol") @NotBlank String symbol,
            @JsonProperty("venue") @NotBlank String venue,
            @JsonProperty("base_asset") @NotBlank String baseAsset,
            @JsonProperty("quote_asset") @NotBlank String quoteAsset,
            @JsonProperty("price_scale") @NotNull @Positive int priceScale,
            @JsonProperty("qty_scale") @NotNull @Positive int qtyScale,
            @JsonProperty("tick_size") @NotNull String tickSize,
            @JsonProperty("lot_size") @NotNull String lotSize,
            @JsonProperty("min_notional") String minNotional,
            @JsonProperty("ws_numeric_safe") boolean wsNumericSafe
    ) {}

    public record ListInstrumentsResponse(
            @JsonProperty("instruments") List<InstrumentSpec> instruments
    ) {}

    public record Level(
            @JsonProperty("price") @NotNull String price,
            @JsonProperty("qty") @NotNull String qty
    ) {}

    public record BookSnapshot(
            @JsonProperty("bids") List<Level> bids,
            @JsonProperty("asks") List<Level> asks
    ) {}

    public record BookDelta(
            @JsonProperty("bids") List<Level> bids,
            @JsonProperty("asks") List<Level> asks
    ) {}

    public record Timing(
            @JsonProperty("exchange_ts_ns") @NotNull String exchangeTsNs,
            @JsonProperty("recv_ts_ns") @NotNull String recvTsNs,
            @JsonProperty("publish_ts_ns") @NotNull String publishTsNs,
            @JsonProperty("gateway_send_ts_ns") String gatewaySendTsNs
    ) {}

    public record BookUpdate(
            @JsonProperty("symbol") @NotBlank String symbol,
            @JsonProperty("epoch") @NotNull long epoch,
            @JsonProperty("seq") @NotNull long seq,
            @JsonProperty("timing") @NotNull Timing timing,
            @JsonProperty("snapshot") BookSnapshot snapshot,
            @JsonProperty("delta") BookDelta delta,
            @JsonProperty("book_valid") boolean bookValid
    ) {}

    public record Trade(
            @JsonProperty("symbol") @NotBlank String symbol,
            @JsonProperty("epoch") @NotNull long epoch,
            @JsonProperty("seq") @NotNull long seq,
            @JsonProperty("timing") @NotNull Timing timing,
            @JsonProperty("price") @NotNull String price,
            @JsonProperty("qty") @NotNull String qty,
            @JsonProperty("side") @NotBlank String side,
            @JsonProperty("venue_trade_id") @NotBlank String venueTradeId
    ) {}

    public record DepthPoint(
            @JsonProperty("levels") int levels,
            @JsonProperty("bid_qty") String bidQty,
            @JsonProperty("ask_qty") String askQty
    ) {}

    public record Metrics(
            @JsonProperty("symbol") @NotBlank String symbol,
            @JsonProperty("epoch") @NotNull long epoch,
            @JsonProperty("seq") @NotNull long seq,
            @JsonProperty("timing") @NotNull Timing timing,
            @JsonProperty("best_bid") String bestBid,
            @JsonProperty("best_ask") String bestAsk,
            @JsonProperty("best_bid_qty") String bestBidQty,
            @JsonProperty("best_ask_qty") String bestAskQty,
            @JsonProperty("spread") String spread,
            @JsonProperty("mid") double mid,
            @JsonProperty("microprice") double microprice,
            @JsonProperty("imbalance_top_n") double imbalanceTopN,
            @JsonProperty("rolling_vwap") double rollingVwap,
            @JsonProperty("trade_flow_imbalance") double tradeFlowImbalance,
            @JsonProperty("depth") List<DepthPoint> depth,
            @JsonProperty("book_valid") boolean bookValid
    ) {}

    public record SnapshotRequest(
            @JsonProperty("symbol") @NotBlank String symbol,
            @JsonProperty("depth_levels") @Size(max = 100) int depthLevels
    ) {}

    public record SymbolHealth(
            @JsonProperty("symbol") String symbol,
            @JsonProperty("book_valid") boolean bookValid,
            @JsonProperty("epoch") long epoch,
            @JsonProperty("resync_count") long resyncCount,
            @JsonProperty("last_event_age_ms") long lastEventAgeMs
    ) {}

    public record HealthResponse(
            boolean connected,
            List<SymbolHealth> symbols,
            String buildVersion
    ) {}

    public record SubscribeRequest(
            @JsonProperty("symbols") @NotNull @Size(min = 1, max = 50) List<String> symbols,
            @JsonProperty("depth_levels") @Size(max = 100) int depthLevels,
            @JsonProperty("metrics_levels") @Size(max = 100) int metricsLevels
    ) {}
}