package com.stockbroker.admin.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Admin API DTOs.
 */
public final class AdminDtos {

    private AdminDtos() {}

    public record SystemStats(
            @JsonProperty("total_users") long totalUsers,
            @JsonProperty("active_users_24h") long activeUsers24h,
            @JsonProperty("total_orders") long totalOrders,
            @JsonProperty("total_volume") String totalVolume,
            @JsonProperty("feed_connected") boolean feedConnected,
            @JsonProperty("feed_symbols") int feedSymbols,
            @JsonProperty("uptime_seconds") long uptimeSeconds
    ) {}

    public record UserAdminView(
            String id,
            String email,
            String role,
            String status,
            @JsonProperty("email_verified") boolean emailVerified,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("last_login") String lastLogin
    ) {}

    public record ListUsersResponse(
            List<UserAdminView> users,
            String nextCursor
    ) {}

    public record UpdateUserRoleRequest(
            String role
    ) {}

    public record UpdateUserStatusRequest(
            String status
    ) {}
}