package com.stockbroker.alerts.service;

import com.stockbroker.alerts.domain.Alert;
import com.stockbroker.alerts.repo.AlertRepository;
import com.stockbroker.market.api.MarketDtos;
import com.stockbroker.market.service.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors price, spread, and imbalance alerts.
 * Runs periodically to check alert conditions and trigger notifications.
 */
@Service
public class AlertMonitorService {

    private static final Logger log = LoggerFactory.getLogger(AlertMonitorService.class);

    private final AlertRepository alertRepository;
    private final MarketDataService marketDataService;
    private final AlertService alertService;

    private static final BigDecimal BPS_FACTOR = new BigDecimal("10000");
    private static final long MILLIS_PER_SECOND = 1000L;

    // Track last check time per alert to respect cooldown
    private final ConcurrentHashMap<UUID, Long> lastCheckTime = new ConcurrentHashMap<>();

    public AlertMonitorService(AlertRepository alertRepository,
                               MarketDataService marketDataService,
                               AlertService alertService) {
        this.alertRepository = alertRepository;
        this.marketDataService = marketDataService;
        this.alertService = alertService;
    }

    @PostConstruct
    public void init() {
        // Start monitoring - in production would use @Scheduled
        // For now, this is a placeholder for the scheduled monitoring
    }

    /**
     * Check all active alerts against current market data.
     * This would be called periodically (e.g., every second) via @Scheduled.
     */
    @Scheduled(fixedRate = 1000) // Every second
    @Transactional
    public void checkAlerts() {
        List<Alert> activeAlerts = alertRepository.findAllByStatus("ACTIVE");
        
        for (Alert alert : activeAlerts) {
            checkAlert(alert);
        }
    }

    private void checkAlert(Alert alert) {
        UUID alertId = alert.getId();
        Long lastCheck = lastCheckTime.get(alertId);
        long now = System.currentTimeMillis();

        // Respect cooldown period
        if (lastCheck != null && (now - lastCheck) < alert.getCooldownSeconds() * MILLIS_PER_SECOND) {
            return;
        }

        // Get current metrics for the symbol (has mid, spread, imbalance)
        MarketDtos.Metrics metrics = marketDataService.getMetrics(alert.getSymbol());
        if (metrics == null || !metrics.bookValid()) {
            return;
        }

        boolean triggered = false;
        String message = "";

        switch (alert.getMetric()) {
            case "PRICE" -> {
                // Check mid price against threshold
                BigDecimal midPrice = BigDecimal.valueOf(metrics.mid());
                triggered = checkThreshold(midPrice, alert.getThreshold(), alert.getOp());
                message = String.format("Price %s %s (threshold: %s)",
                        midPrice.toPlainString(), alert.getOp(), alert.getThreshold().toPlainString());
            }
            case "SPREAD" -> {
                // Check spread in basis points: (spread / mid) * 10,000
                if (metrics.spread() != null && metrics.mid() > 0) {
                    BigDecimal spread = new BigDecimal(metrics.spread());
                    BigDecimal mid = BigDecimal.valueOf(metrics.mid());
                    BigDecimal spreadBps = spread.divide(mid, 8, java.math.RoundingMode.HALF_UP)
                            .multiply(BPS_FACTOR);
                    triggered = checkThreshold(spreadBps, alert.getThreshold(), alert.getOp());
                    message = String.format("Spread %s %s (threshold: %s bps)",
                            spreadBps.toPlainString(), alert.getOp(), alert.getThreshold().toPlainString());
                }
            }
            case "IMBALANCE" -> {
                // Check order book imbalance
                BigDecimal imbalance = new BigDecimal(String.valueOf(metrics.imbalanceTopN()));
                triggered = checkThreshold(imbalance, alert.getThreshold(), alert.getOp());
                message = String.format("Imbalance %s %s (threshold: %s)",
                        metrics.imbalanceTopN(), alert.getOp(), alert.getThreshold().toPlainString());
            }
            default -> {
            }
        }

        if (triggered) {
            log.info("Alert {} triggered for user {}: {}", alertId, alert.getUserId(), message);
            alertService.triggerAlert(alertId);
        }

        // Update last check time
        lastCheckTime.put(alertId, System.currentTimeMillis());
    }

    private boolean checkThreshold(BigDecimal value, BigDecimal threshold, String op) {
        if ("GT".equals(op)) {
            return value.compareTo(threshold) > 0;
        } else if ("LT".equals(op)) {
            return value.compareTo(threshold) < 0;
        }
        return false;
    }

    /**
     * Manually trigger alert check for a specific symbol (e.g., on new market data).
     *
     * @param symbol the market symbol to check alerts for
     */
    public void checkAlertsForSymbol(String symbol) {
        for (String metric : List.of("PRICE", "SPREAD", "IMBALANCE")) {
            List<Alert> alerts = alertRepository.findActiveBySymbolAndMetric(symbol, metric);
            for (Alert alert : alerts) {
                checkAlert(alert);
            }
        }
    }
}