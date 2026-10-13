package com.stockbroker.common.error;

import org.zalando.problem.AbstractThrowableProblem;
import org.zalando.problem.Status;
import org.zalando.problem.ThrowableProblem;

import java.net.URI;
import java.util.Map;

/**
 * Base class for application-specific problems with stable error codes.
 *
 * <p>Error codes must match the API specification (docs/04-API-SPEC.md section 2.6).
 * Never expose stack traces or internal details in problem details.
 */
public class BusinessProblem extends AbstractThrowableProblem {

    private final String code;

    protected BusinessProblem(String code, Status status, String title, String detail) {
        super(URI.create("urn:stockbroker:error:" + code), title, status, detail);
        this.code = code;
    }

    protected BusinessProblem(String code, Status status, String title, String detail, Map<String, Object> parameters) {
        super(URI.create("urn:stockbroker:error:" + code), title, status, detail, null, null, parameters);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * Authentication errors
     */
    public static class AuthInvalidCredentials extends BusinessProblem {
        public AuthInvalidCredentials() {
            super("AUTH_INVALID_CREDENTIALS", Status.UNAUTHORIZED, "Invalid credentials",
                  "The provided email or password is incorrect");
        }
    }

    public static class AuthTokenExpired extends BusinessProblem {
        public AuthTokenExpired() {
            super("AUTH_TOKEN_EXPIRED", Status.UNAUTHORIZED, "Token expired",
                  "The access token has expired");
        }
    }

    public static class AuthRefreshReused extends BusinessProblem {
        public AuthRefreshReused() {
            super("AUTH_REFRESH_REUSED", Status.UNAUTHORIZED, "Refresh token reused",
                  "This refresh token has already been used");
        }
    }

    public static class AuthTokenInvalid extends BusinessProblem {
        public AuthTokenInvalid(String detail) {
            super("AUTH_TOKEN_INVALID", Status.UNAUTHORIZED, "Invalid token", detail);
        }
    }

    public static class AuthInsufficientPermissions extends BusinessProblem {
        public AuthInsufficientPermissions(String requiredRole) {
            super("AUTH_INSUFFICIENT_PERMISSIONS", Status.FORBIDDEN, "Insufficient permissions",
                  "Required role: " + requiredRole,
                  Map.of("requiredRole", requiredRole));
        }
    }

    /**
     * Rate limiting errors
     */
    public static class RateLimited extends BusinessProblem {
        public RateLimited(long retryAfterSeconds) {
            super("RATE_LIMITED", Status.TOO_MANY_REQUESTS, "Rate limited",
                  "Too many requests. Try again later",
                  Map.of("retryAfter", retryAfterSeconds));
        }
    }

    /**
     * Validation errors
     */
    public static class ValidationFailed extends BusinessProblem {
        public ValidationFailed(String detail) {
            super("VALIDATION_FAILED", Status.BAD_REQUEST, "Validation failed", detail);
        }

        public ValidationFailed(String detail, Map<String, Object> parameters) {
            super("VALIDATION_FAILED", Status.BAD_REQUEST, "Validation failed", detail, parameters);
        }
    }

    /**
     * Alert errors
     */
    public static class AlertLimitExceeded extends BusinessProblem {
        public AlertLimitExceeded(int maxAlerts) {
            super("ALERT_LIMIT_EXCEEDED", Status.TOO_MANY_REQUESTS, "Alert limit exceeded",
                  "Maximum number of active alerts (" + maxAlerts + ") reached",
                  Map.of("maxAlerts", maxAlerts));
        }
    }

    /**
     * Order errors
     */
    public static class OrderInsufficientFunds extends BusinessProblem {
        public OrderInsufficientFunds(String asset, String required, String available) {
            super("ORDER_INSUFFICIENT_FUNDS", Status.UNPROCESSABLE_ENTITY, "Insufficient funds",
                  "Not enough " + asset + " to place order. Required: " + required + ", Available: " + available,
                  Map.of("asset", asset, "required", required, "available", available));
        }
    }

    public static class OrderBookInvalid extends BusinessProblem {
        public OrderBookInvalid(String symbol) {
            super("ORDER_BOOK_INVALID", Status.UNPROCESSABLE_ENTITY, "Order book invalid",
                  "Market data for " + symbol + " is not valid; cannot fill order",
                  Map.of("symbol", symbol));
        }
    }

    public static class OrderDuplicateKeyMismatch extends BusinessProblem {
        public OrderDuplicateKeyMismatch() {
            super("ORDER_DUPLICATE_KEY_MISMATCH", Status.CONFLICT, "Duplicate key mismatch",
                  "An order with the same idempotency key but different body already exists");
        }
    }

    public static class OrderNotFound extends BusinessProblem {
        public OrderNotFound(String orderId) {
            super("ORDER_NOT_FOUND", Status.NOT_FOUND, "Order not found",
                  "Order with id " + orderId + " does not exist",
                  Map.of("orderId", orderId));
        }
    }

    public static class OrderCannotCancel extends BusinessProblem {
        public OrderCannotCancel(String orderId, String status) {
            super("ORDER_CANNOT_CANCEL", Status.CONFLICT, "Cannot cancel order",
                  "Order " + orderId + " is in status " + status + " and cannot be canceled",
                  Map.of("orderId", orderId, "status", status));
        }
    }

    public static class OrderLimitExceeded extends BusinessProblem {
        public OrderLimitExceeded(int maxOrders) {
            super("ORDER_LIMIT_EXCEEDED", Status.TOO_MANY_REQUESTS, "Order limit exceeded",
                  "Maximum number of open orders (" + maxOrders + ") reached",
                  Map.of("maxOrders", maxOrders));
        }
    }

    /**
     * Instrument errors
     */
    public static class InstrumentNotFound extends BusinessProblem {
        public InstrumentNotFound(String symbol) {
            super("INSTRUMENT_NOT_FOUND", Status.NOT_FOUND, "Instrument not found",
                  "Instrument " + symbol + " does not exist",
                  Map.of("symbol", symbol));
        }
    }

    /**
     * Backtest errors
     */
    public static class BacktestDataGap extends BusinessProblem {
        public BacktestDataGap(String symbol, String from, String to) {
            super("BACKTEST_DATA_GAP", Status.UNPROCESSABLE_ENTITY, "Backtest data gap",
                  "Missing market data for " + symbol + " between " + from + " and " + to,
                  Map.of("symbol", symbol, "from", from, "to", to));
        }
    }

    /**
     * Account errors
     */
    public static class AccountNotFound extends BusinessProblem {
        public AccountNotFound(String accountId) {
            super("ACCOUNT_NOT_FOUND", Status.NOT_FOUND, "Account not found",
                  "Account " + accountId + " does not exist",
                  Map.of("accountId", accountId));
        }
    }

    /**
     * Generic internal error (should not leak details)
     */
    public static class InternalError extends BusinessProblem {
        public InternalError(String requestId) {
            super("INTERNAL_ERROR", Status.INTERNAL_SERVER_ERROR, "Internal server error",
                  "An unexpected error occurred. Reference: " + requestId,
                  Map.of("requestId", requestId));
        }
    }

    /**
     * Service unavailable (e.g., market data feed down)
     */
    public static class ServiceUnavailable extends BusinessProblem {
        public ServiceUnavailable(String service, String detail) {
            super("SERVICE_UNAVAILABLE", Status.SERVICE_UNAVAILABLE, "Service unavailable",
                  service + " is temporarily unavailable: " + detail,
                  Map.of("service", service));
        }
    }

    /**
     * Conflict error for concurrent modification
     */
    public static class ConcurrentModification extends BusinessProblem {
        public ConcurrentModification(String resource) {
            super("CONCURRENT_MODIFICATION", Status.CONFLICT, "Concurrent modification",
                  resource + " was modified by another request. Please retry",
                  Map.of("resource", resource));
        }
    }

    /**
     * Idempotency key required
     */
    public static class IdempotencyKeyRequired extends BusinessProblem {
        public IdempotencyKeyRequired() {
            super("IDEMPOTENCY_KEY_REQUIRED", Status.BAD_REQUEST, "Idempotency key required",
                  "The Idempotency-Key header is required for this operation");
        }
    }
}