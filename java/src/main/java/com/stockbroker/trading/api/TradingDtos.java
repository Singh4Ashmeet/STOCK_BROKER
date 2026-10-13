package com.stockbroker.trading.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * Trading API DTOs.
 */
public final class TradingDtos {

    private TradingDtos() {}

    public enum OrderSide { BUY, SELL }
    public enum OrderType { MARKET, LIMIT }
    public enum TimeInForce { GTC, IOC }
    public enum OrderStatus { NEW, ACCEPTED, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED }

    public record CreateOrderRequest(
            @NotBlank String symbol,
            @NotNull OrderSide side,
            @NotNull OrderType type,
            @NotNull @DecimalMin(value = "0.00000001", message = "Quantity must be positive") String qty,
            @DecimalMin(value = "0", message = "Limit price must be non-negative") String limitPrice,
            @NotNull TimeInForce tif,
            @NotBlank @Size(max = 64) @JsonProperty("idempotency_key") String idempotencyKey
    ) {}

    public record OrderResponse(
            @JsonProperty("order_id") String orderId,
            String symbol,
            OrderSide side,
            OrderType type,
            OrderStatus status,
            String qty,
            @JsonProperty("filled_qty") String filledQty,
            @JsonProperty("avg_price") String avgPrice,
            @JsonProperty("limit_price") String limitPrice,
            TimeInForce tif,
            @JsonProperty("reject_reason") String rejectReason,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt,
            List<FillResponse> fills
    ) {}

    public record FillResponse(
            @JsonProperty("fill_id") String fillId,
            String price,
            String qty,
            @JsonProperty("fee_asset") String feeAsset,
            @JsonProperty("fee_amount") String feeAmount,
            String liquidity,
            @JsonProperty("book_epoch") Long bookEpoch,
            @JsonProperty("book_seq") Long bookSeq,
            @JsonProperty("filled_at") String filledAt
    ) {}

    public record OrderPreviewRequest(
            @NotBlank String symbol,
            @NotNull OrderSide side,
            @NotNull @DecimalMin(value = "0.00000001") String qty,
            @DecimalMin(value = "0") String limitPrice
    ) {}

    public record OrderPreviewResponse(
            @JsonProperty("est_fill_price") String estFillPrice,
            @JsonProperty("est_fees") String estFees,
            @JsonProperty("est_slippage_bps") double estSlippageBps,
            @JsonProperty("book_valid") boolean bookValid,
            @JsonProperty("levels_consumed") int levelsConsumed
    ) {}

    public record PortfolioResponse(
            @JsonProperty("total_equity") String totalEquity,
            @JsonProperty("cash_balance") String cashBalance,
            @JsonProperty("unrealized_pnl") String unrealizedPnl,
            @JsonProperty("realized_pnl") String realizedPnl,
            List<PositionResponse> positions
    ) {}

    public record PositionResponse(
            String symbol,
            String qty,
            @JsonProperty("avg_cost") String avgCost,
            @JsonProperty("current_price") String currentPrice,
            @JsonProperty("unrealized_pnl") String unrealizedPnl,
            @JsonProperty("realized_pnl") String realizedPnl,
            @JsonProperty("market_value") String marketValue
    ) {}

    public record LedgerEntryResponse(
            String id,
            String asset,
            String amount,
            String type,
            @JsonProperty("ref_type") String refType,
            @JsonProperty("ref_id") String refId,
            @JsonProperty("created_at") String createdAt
    ) {}

    public record LedgerResponse(
            List<LedgerEntryResponse> entries,
            String nextCursor
    ) {}
}