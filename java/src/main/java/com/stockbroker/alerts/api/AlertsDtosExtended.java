package com.stockbroker.alerts.api;

import java.util.List;

/**
 * Additional alerts DTOs for list responses.
 */
public final class AlertsDtosExtended {

    private AlertsDtosExtended() {}

    public record AlertListResponse(
            List<AlertsDtos.AlertResponse> alerts,
            String nextCursor
    ) {}
}