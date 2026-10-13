package com.stockbroker.alerts;

import com.stockbroker.alerts.api.AlertsDtos;
import com.stockbroker.alerts.domain.Alert;
import com.stockbroker.alerts.repo.AlertRepository;
import com.stockbroker.alerts.service.AlertMonitorService;
import com.stockbroker.alerts.service.AlertService;
import com.stockbroker.common.error.BusinessProblem;
import com.stockbroker.market.api.MarketDtos;
import com.stockbroker.market.service.MarketDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertMonitorServiceTest {

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private MarketDataService marketDataService;

    @Mock
    private AlertService alertService;

    private AlertMonitorService alertMonitorService;

    @BeforeEach
    void setUp() {
        alertMonitorService = new AlertMonitorService(alertRepository, marketDataService, alertService);
    }

    @Test
    void shouldNotCheckAlertsWhenNoActiveAlerts() {
        when(alertRepository.findAllByStatus("ACTIVE")).thenReturn(List.of());

        alertMonitorService.checkAlerts();

        verify(alertRepository).findAllByStatus("ACTIVE");
        verifyNoInteractions(marketDataService, alertService);
    }

    @Test
    void shouldTriggerPriceAlertWhenThresholdExceeded() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        String symbol = "AAPL";

        Alert alert = new Alert();
        alert.setId(alertId);
        alert.setUserId(userId);
        alert.setSymbol(symbol);
        alert.setMetric("PRICE");
        alert.setOp("GT");
        alert.setThreshold(new BigDecimal("150.00"));
        alert.setStatus("ACTIVE");
        alert.setCooldownSeconds(60);

        MarketDtos.Metrics metrics = new MarketDtos.Metrics(
                symbol, 1, 1, null,
                "151.00", "151.05", "100", "100",
                "0.05", 151.025, 151.02, 0.1, 151.00, 0.0,
                List.of(), true
        );

        when(alertRepository.findAllByStatus("ACTIVE")).thenReturn(List.of(alert));
        when(marketDataService.getMetrics(symbol)).thenReturn(metrics);

        // When
        alertMonitorService.checkAlerts();

        // Then
        verify(alertService).triggerAlert(alertId);
    }

    @Test
    void shouldNotTriggerPriceAlertWhenThresholdNotMet() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        String symbol = "AAPL";

        Alert alert = new Alert();
        alert.setId(alertId);
        alert.setUserId(userId);
        alert.setSymbol(symbol);
        alert.setMetric("PRICE");
        alert.setOp("GT");
        alert.setThreshold(new BigDecimal("150.00"));
        alert.setStatus("ACTIVE");
        alert.setCooldownSeconds(60);

        MarketDtos.Metrics metrics = new MarketDtos.Metrics(
                symbol, 1, 1, null,
                "149.00", "149.05", "100", "100",
                "0.05", 149.025, 149.02, 0.1, 149.00, 0.0,
                List.of(), true
        );

        when(alertRepository.findAllByStatus("ACTIVE")).thenReturn(List.of(alert));
        when(marketDataService.getMetrics(symbol)).thenReturn(metrics);

        // When
        alertMonitorService.checkAlerts();

        // Then
        verify(alertService, never()).triggerAlert(any());
    }

    @Test
    void shouldTriggerSpreadAlertWhenThresholdExceeded() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        String symbol = "AAPL";

        Alert alert = new Alert();
        alert.setId(alertId);
        alert.setUserId(userId);
        alert.setSymbol(symbol);
        alert.setMetric("SPREAD");
        alert.setOp("GT");
        alert.setThreshold(new BigDecimal("10")); // 10 bps
        alert.setStatus("ACTIVE");
        alert.setCooldownSeconds(60);

        MarketDtos.Metrics metrics = new MarketDtos.Metrics(
                symbol, 1, 1, null,
                "150.00", "150.20", "100", "100",
                "0.20", 150.10, 150.10, 0.1, 150.10, 0.0,
                List.of(), true
        );

        when(alertRepository.findAllByStatus("ACTIVE")).thenReturn(List.of(alert));
        when(marketDataService.getMetrics(symbol)).thenReturn(metrics);

        // When
        alertMonitorService.checkAlerts();

        // Then
        verify(alertService).triggerAlert(alertId);
    }

    @Test
    void shouldTriggerImbalanceAlertWhenThresholdExceeded() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        String symbol = "AAPL";

        Alert alert = new Alert();
        alert.setId(alertId);
        alert.setUserId(userId);
        alert.setSymbol(symbol);
        alert.setMetric("IMBALANCE");
        alert.setOp("GT");
        alert.setThreshold(new BigDecimal("0.5"));
        alert.setStatus("ACTIVE");
        alert.setCooldownSeconds(60);

        MarketDtos.Metrics metrics = new MarketDtos.Metrics(
                symbol, 1, 1, null,
                "150.00", "150.05", "100", "100",
                "0.05", 150.025, 150.02, 0.6, 150.00, 0.0,
                List.of(), true
        );

        when(alertRepository.findAllByStatus("ACTIVE")).thenReturn(List.of(alert));
        when(marketDataService.getMetrics(symbol)).thenReturn(metrics);

        // When
        alertMonitorService.checkAlerts();

        // Then
        verify(alertService).triggerAlert(alertId);
    }

    @Test
    void shouldRespectCooldownPeriod() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        String symbol = "AAPL";

        Alert alert = new Alert();
        alert.setId(alertId);
        alert.setUserId(userId);
        alert.setSymbol(symbol);
        alert.setMetric("PRICE");
        alert.setOp("GT");
        alert.setThreshold(new BigDecimal("150.00"));
        alert.setStatus("ACTIVE");
        alert.setCooldownSeconds(60);

        MarketDtos.Metrics metrics = new MarketDtos.Metrics(
                symbol, 1, 1, null,
                "151.00", "151.05", "100", "100",
                "0.05", 151.025, 151.02, 0.1, 151.00, 0.0,
                List.of(), true
        );

        when(alertRepository.findAllByStatus("ACTIVE")).thenReturn(List.of(alert));
        when(marketDataService.getMetrics(symbol)).thenReturn(metrics);

        // When - first check triggers alert
        alertMonitorService.checkAlerts();
        // Second check should not trigger due to cooldown
        alertMonitorService.checkAlerts();

        // Then - triggerAlert should only be called once
        verify(alertService, times(1)).triggerAlert(alertId);
    }

    @Test
    void shouldNotCheckInvalidMetrics() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        String symbol = "AAPL";

        Alert alert = new Alert();
        alert.setId(alertId);
        alert.setUserId(userId);
        alert.setSymbol(symbol);
        alert.setMetric("PRICE");
        alert.setOp("GT");
        alert.setThreshold(new BigDecimal("150.00"));
        alert.setStatus("ACTIVE");
        alert.setCooldownSeconds(60);

        when(alertRepository.findAllByStatus("ACTIVE")).thenReturn(List.of(alert));
        when(marketDataService.getMetrics(symbol)).thenReturn(null);

        // When
        alertMonitorService.checkAlerts();

        // Then - no trigger because metrics is null
        verify(alertService, never()).triggerAlert(any());
    }

    @Test
    void shouldNotCheckWhenBookNotValid() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        String symbol = "AAPL";

        Alert alert = new Alert();
        alert.setId(alertId);
        alert.setUserId(userId);
        alert.setSymbol(symbol);
        alert.setMetric("PRICE");
        alert.setOp("GT");
        alert.setThreshold(new BigDecimal("150.00"));
        alert.setStatus("ACTIVE");
        alert.setCooldownSeconds(60);

        MarketDtos.Metrics metrics = new MarketDtos.Metrics(
                symbol, 1, 1, null,
                "151.00", "151.05", "100", "100",
                "0.05", 151.025, 151.02, 0.1, 151.00, 0.0,
                List.of(), false // book not valid
        );

        when(alertRepository.findAllByStatus("ACTIVE")).thenReturn(List.of(alert));
        when(marketDataService.getMetrics(symbol)).thenReturn(metrics);

        // When
        alertMonitorService.checkAlerts();

        // Then
        verify(alertService, never()).triggerAlert(any());
    }
}
