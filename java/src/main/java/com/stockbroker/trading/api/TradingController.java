package com.stockbroker.trading.api;

import com.stockbroker.common.error.BusinessProblem;
import com.stockbroker.common.security.CurrentUser;
import com.stockbroker.trading.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Trading REST endpoints - orders, fills, preview.
 */
@RestController
@RequestMapping("/api/v1")
public class TradingController {

    private final OrderService orderService;

    public TradingController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TradingDtos.OrderResponse> createOrder(
            @Valid @RequestBody TradingDtos.CreateOrderRequest request,
            HttpServletRequest httpRequest) {

        // Extract idempotency key from header or body
        String idempotencyKey = httpRequest.getHeader("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = request.idempotencyKey();
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessProblem.IdempotencyKeyRequired();
        }

        UUID userId = CurrentUser.getUserId();
        var order = orderService.createOrder(userId, request, idempotencyKey);
        return ResponseEntity.status(201).body(order);
    }

    @GetMapping("/orders")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TradingDtosExtended.OrderListResponse> listOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String cursor) {

        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.ok(orderService.listOrders(userId, status, symbol, limit, cursor));
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TradingDtos.OrderResponse> getOrder(@PathVariable String id) {
        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.ok(orderService.getOrder(userId, UUID.fromString(id)));
    }

    @DeleteMapping("/orders/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TradingDtos.OrderResponse> cancelOrder(@PathVariable String id) {
        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.ok(orderService.cancelOrder(userId, UUID.fromString(id)));
    }

    @GetMapping("/fills")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TradingDtosExtended.FillsResponse> listFills(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String cursor) {

        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.ok(orderService.listFills(userId, limit, cursor));
    }

    @PostMapping("/orders/preview")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TradingDtos.OrderPreviewResponse> previewOrder(
            @Valid @RequestBody TradingDtos.OrderPreviewRequest request) {

        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.ok(orderService.previewOrder(userId, request));
    }
}