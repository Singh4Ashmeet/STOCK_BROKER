package com.stockbroker.alerts.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Alerts API DTOs.
 */
public final class AlertsDtos {

    private AlertsDtos() {}

    public enum Metric { PRICE, SPREAD, IMBALANCE }
    public enum Operator { GT, LT }
    public enum Status { ACTIVE, TRIGGERED, DISABLED }

    public record CreateAlertRequest(
            @NotBlank String symbol,
            @NotNull Metric metric,
            @NotNull Operator op,
            @NotNull @Positive String threshold,
            @JsonProperty("cooldown_s") @Positive int cooldownSeconds
    ) {}

    public record AlertResponse(
            @JsonProperty("alert_id") String alertId,
            String symbol,
            Metric metric,
            Operator op,
            String threshold,
            Status status,
            @JsonProperty("cooldown_s") int cooldownSeconds,
            @JsonProperty("last_triggered_at") String lastTriggeredAt,
            @JsonProperty("created_at") String createdAt
    ) {}

    public record UpdateAlertRequest(
            Status status,
            @JsonProperty("cooldown_s") @Positive int cooldownSeconds
    ) {}

    public record AlertListResponse(
            java.util.List<AlertResponse> alerts,
            String nextCursor
    ) {}
}