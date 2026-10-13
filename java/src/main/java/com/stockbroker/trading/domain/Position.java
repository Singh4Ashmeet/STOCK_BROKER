package com.stockbroker.trading.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Position entity (derived cache).
 */
@Entity
@Table(name = "positions")
@IdClass(PositionId.class)
public class Position {

    @Id
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Id
    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "qty", nullable = false, precision = 38, scale = 18)
    private BigDecimal qty;

    @Column(name = "avg_cost", nullable = false, precision = 38, scale = 18)
    private BigDecimal avgCost;

    @Column(name = "realized_pnl", nullable = false, precision = 38, scale = 18)
    private BigDecimal realizedPnl;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public BigDecimal getAvgCost() { return avgCost; }
    public void setAvgCost(BigDecimal avgCost) { this.avgCost = avgCost; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public void setRealizedPnl(BigDecimal realizedPnl) { this.realizedPnl = realizedPnl; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}