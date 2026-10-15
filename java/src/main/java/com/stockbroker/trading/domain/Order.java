package com.stockbroker.trading.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Order entity.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "side", nullable = false)
    private String side;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "tif", nullable = false)
    private String tif;

    @Column(name = "qty", nullable = false, precision = 38, scale = 18)
    private BigDecimal qty;

    @Column(name = "limit_price", precision = 38, scale = 18)
    private BigDecimal limitPrice;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "filled_qty", nullable = false, precision = 38, scale = 18)
    private BigDecimal filledQty;

    @Column(name = "avg_fill_price", precision = 38, scale = 18)
    private BigDecimal avgFillPrice;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private byte[] requestHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTif() { return tif; }
    public void setTif(String tif) { this.tif = tif; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public BigDecimal getLimitPrice() { return limitPrice; }
    public void setLimitPrice(BigDecimal limitPrice) { this.limitPrice = limitPrice; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getFilledQty() { return filledQty; }
    public void setFilledQty(BigDecimal filledQty) { this.filledQty = filledQty; }
    public BigDecimal getAvgFillPrice() { return avgFillPrice; }
    public void setAvgFillPrice(BigDecimal avgFillPrice) { this.avgFillPrice = avgFillPrice; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public byte[] getRequestHash() { return requestHash; }
    public void setRequestHash(byte[] requestHash) { this.requestHash = requestHash; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}