package com.stockbroker.alerts.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Alert entity.
 */
@Entity
@Table(name = "alerts")
public class Alert {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "metric", nullable = false)
    private String metric;

    @Column(name = "op", nullable = false)
    private String op;

    @Column(name = "threshold", nullable = false, precision = 38, scale = 18)
    private BigDecimal threshold;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "cooldown_s", nullable = false)
    private int cooldownSeconds;

    @Column(name = "last_triggered_at")
    private Instant lastTriggeredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public String getOp() { return op; }
    public void setOp(String op) { this.op = op; }
    public BigDecimal getThreshold() { return threshold; }
    public void setThreshold(BigDecimal threshold) { this.threshold = threshold; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getCooldownSeconds() { return cooldownSeconds; }
    public void setCooldownSeconds(int cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
    public Instant getLastTriggeredAt() { return lastTriggeredAt; }
    public void setLastTriggeredAt(Instant lastTriggeredAt) { this.lastTriggeredAt = lastTriggeredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}