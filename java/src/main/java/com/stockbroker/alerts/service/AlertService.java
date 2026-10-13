package com.stockbroker.alerts.service;

import com.stockbroker.alerts.api.AlertsDtos;
import com.stockbroker.alerts.domain.Alert;
import com.stockbroker.alerts.repo.AlertRepository;
import com.stockbroker.common.error.BusinessProblem;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Alert service.
 */
@Service
@Transactional
public class AlertService {

    // Cap on active alerts per user
    private static final int MAX_ALERTS_PER_USER = 50;

    private final AlertRepository alertRepository;

    public AlertService(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    public AlertsDtos.AlertResponse createAlert(UUID userId, AlertsDtos.CreateAlertRequest request) {
        // Check alert cap per user
        long activeAlerts = alertRepository.countByUserIdAndStatus(userId, "ACTIVE");
        if (activeAlerts >= MAX_ALERTS_PER_USER) {
            throw new BusinessProblem.AlertLimitExceeded(MAX_ALERTS_PER_USER);
        }

        Alert alert = new Alert();
        alert.setUserId(userId);
        alert.setSymbol(request.symbol());
        alert.setMetric(request.metric().name());
        alert.setOp(request.op().name());
        alert.setThreshold(new BigDecimal(request.threshold()));
        alert.setStatus("ACTIVE");
        alert.setCooldownSeconds(request.cooldownSeconds());

        alertRepository.save(alert);

        return toAlertResponse(alert);
    }

    public AlertsDtos.AlertListResponse listAlerts(UUID userId, int limit, String cursor) {
        // TODO: Implement cursor pagination
        List<Alert> alerts = alertRepository.findByUserId(userId, PageRequest.of(0, limit));
        List<AlertsDtos.AlertResponse> responses = alerts.stream()
                .map(this::toAlertResponse)
                .collect(Collectors.toList());
        return new AlertsDtos.AlertListResponse(responses, null);
    }

    public AlertsDtos.AlertResponse getAlert(UUID userId, UUID alertId) {
        Alert alert = alertRepository.findByIdAndUserId(alertId, userId)
                .orElseThrow(() -> new BusinessProblem.ValidationFailed("Alert not found",
                        java.util.Map.of("code", "ALERT_NOT_FOUND")));
        return toAlertResponse(alert);
    }

    public AlertsDtos.AlertResponse updateAlert(UUID userId, UUID alertId, AlertsDtos.UpdateAlertRequest request) {
        Alert alert = alertRepository.findByIdAndUserId(alertId, userId)
                .orElseThrow(() -> new BusinessProblem.ValidationFailed("Alert not found",
                        java.util.Map.of("code", "ALERT_NOT_FOUND")));

        if (request.status() != null) {
            alert.setStatus(request.status().name());
        }
        if (request.cooldownSeconds() > 0) {
            alert.setCooldownSeconds(request.cooldownSeconds());
        }

        alertRepository.save(alert);
        return toAlertResponse(alert);
    }

    public void deleteAlert(UUID userId, UUID alertId) {
        Alert alert = alertRepository.findByIdAndUserId(alertId, userId)
                .orElseThrow(() -> new BusinessProblem.ValidationFailed("Alert not found",
                        java.util.Map.of("code", "ALERT_NOT_FOUND")));
        alertRepository.delete(alert);
    }

    public List<Alert> getActiveAlertsForSymbol(String symbol, String metric) {
        return alertRepository.findActiveBySymbolAndMetric(symbol, metric);
    }

    public void triggerAlert(UUID alertId) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new BusinessProblem.ValidationFailed("Alert not found",
                        java.util.Map.of("code", "ALERT_NOT_FOUND")));
        alert.setStatus("TRIGGERED");
        alert.setLastTriggeredAt(Instant.now());
        alertRepository.save(alert);
    }

    private AlertsDtos.AlertResponse toAlertResponse(Alert alert) {
        return new AlertsDtos.AlertResponse(
                alert.getId().toString(),
                alert.getSymbol(),
                AlertsDtos.Metric.valueOf(alert.getMetric()),
                AlertsDtos.Operator.valueOf(alert.getOp()),
                alert.getThreshold().toPlainString(),
                AlertsDtos.Status.valueOf(alert.getStatus()),
                alert.getCooldownSeconds(),
                alert.getLastTriggeredAt() != null ? alert.getLastTriggeredAt().toString() : null,
                alert.getCreatedAt().toString()
        );
    }
}