package com.stockbroker.alerts;

import com.stockbroker.alerts.api.AlertsDtos;
import com.stockbroker.alerts.domain.Alert;
import com.stockbroker.alerts.repo.AlertRepository;
import com.stockbroker.alerts.service.AlertService;
import com.stockbroker.common.error.BusinessProblem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertServiceLimitTest {

    @Mock
    private AlertRepository alertRepository;

    private AlertService alertService;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        alertService = new AlertService(alertRepository);
    }

    @Test
    void shouldRejectAlertWhenLimitExceeded() {
        // Given
        when(alertRepository.countByUserIdAndStatus(userId, "ACTIVE"))
                .thenReturn(50L); // MAX_ALERTS_PER_USER = 50

        AlertsDtos.CreateAlertRequest request = new AlertsDtos.CreateAlertRequest(
                "AAPL", AlertsDtos.Metric.PRICE, AlertsDtos.Operator.GT,
                "150.00", 60
        );

        // When/Then
        assertThatThrownBy(() -> alertService.createAlert(userId, request))
                .isInstanceOf(BusinessProblem.AlertLimitExceeded.class)
                .hasMessageContaining("Maximum number of active alerts (50) reached");
    }

    @Test
    void shouldAllowAlertWhenUnderLimit() {
        // Given
        when(alertRepository.countByUserIdAndStatus(userId, "ACTIVE"))
                .thenReturn(10L); // Under limit

        Alert savedAlert = new Alert();
        savedAlert.setId(UUID.randomUUID());
        savedAlert.setUserId(userId);
        savedAlert.setSymbol("AAPL");
        savedAlert.setMetric("PRICE");
        savedAlert.setOp("GT");
        savedAlert.setThreshold(new BigDecimal("150.00"));
        savedAlert.setStatus("ACTIVE");
        savedAlert.setCooldownSeconds(60);
        savedAlert.setCreatedAt(Instant.now());

        when(alertRepository.save(any(Alert.class))).thenReturn(savedAlert);

        AlertsDtos.CreateAlertRequest request = new AlertsDtos.CreateAlertRequest(
                "AAPL", AlertsDtos.Metric.PRICE, AlertsDtos.Operator.GT,
                "150.00", 60
        );

        // When
        AlertsDtos.AlertResponse response = alertService.createAlert(userId, request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.symbol()).isEqualTo("AAPL");
        assertThat(response.metric()).isEqualTo(AlertsDtos.Metric.PRICE);
    }

    @Test
    void shouldTriggerAlertAndUpdateStatus() {
        // Given
        UUID alertId = UUID.randomUUID();
        Alert alert = new Alert();
        alert.setId(alertId);
        alert.setUserId(userId);
        alert.setSymbol("AAPL");
        alert.setMetric("PRICE");
        alert.setOp("GT");
        alert.setThreshold(new BigDecimal("150.00"));
        alert.setStatus("ACTIVE");
        alert.setCooldownSeconds(60);

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(alert));
        when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        alertService.triggerAlert(alertId);

        // Then
        verify(alertRepository).save(argThat(a -> 
            a.getStatus().equals("TRIGGERED") && a.getLastTriggeredAt() != null
        ));
    }

    @Test
    void shouldThrowWhenTriggeringNonExistentAlert() {
        // Given
        UUID alertId = UUID.randomUUID();
        when(alertRepository.findById(alertId)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> alertService.triggerAlert(alertId))
                .isInstanceOf(BusinessProblem.AlertNotFound.class)
                .hasMessageContaining("Alert not found");
    }
}