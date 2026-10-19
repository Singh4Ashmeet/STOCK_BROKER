package com.stockbroker.alerts.api;

import com.stockbroker.common.security.CurrentUser;
import com.stockbroker.alerts.service.AlertService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Alerts REST endpoints.
 */
@RestController
@RequestMapping("/api/v1")
public class AlertsController {

    private final AlertService alertService;

    public AlertsController(AlertService alertService) {
        this.alertService = alertService;
    }

    @PostMapping("/alerts")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AlertsDtos.AlertResponse> createAlert(
            @Valid @RequestBody AlertsDtos.CreateAlertRequest request) {
        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.status(201).body(alertService.createAlert(userId, request));
    }

    @GetMapping("/alerts")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AlertsDtos.AlertListResponse> listAlerts(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String cursor) {
        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.ok(alertService.listAlerts(userId, limit, cursor));
    }

    @GetMapping("/alerts/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AlertsDtos.AlertResponse> getAlert(@PathVariable String id) {
        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.ok(alertService.getAlert(userId, UUID.fromString(id)));
    }

    @PatchMapping("/alerts/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AlertsDtos.AlertResponse> updateAlert(
            @PathVariable String id,
            @Valid @RequestBody AlertsDtos.UpdateAlertRequest request) {
        UUID userId = CurrentUser.getUserId();
        return ResponseEntity.ok(alertService.updateAlert(userId, UUID.fromString(id), request));
    }

    @DeleteMapping("/alerts/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteAlert(@PathVariable String id) {
        UUID userId = CurrentUser.getUserId();
        alertService.deleteAlert(userId, UUID.fromString(id));
        return ResponseEntity.noContent().build();
    }
}