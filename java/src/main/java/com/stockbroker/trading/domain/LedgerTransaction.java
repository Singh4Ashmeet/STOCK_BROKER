package com.stockbroker.trading.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Ledger transaction - a single atomic business event that creates balanced ledger entries.
 * Every transaction must balance: sum of amounts per asset = 0.
 * 
 * Kinds: DEPOSIT, FILL, FEE, ADJUSTMENT
 */
@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "kind", nullable = false)
    private String kind; // DEPOSIT, FILL, FEE, ADJUSTMENT

    @Column(name = "ref_type")
    private String refType; // e.g., "FILL", "ORDER"

    @Column(name = "ref_id")
    private UUID refId; // e.g., fill_id, order_id

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getRefType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }
    public UUID getRefId() { return refId; }
    public void setRefId(UUID refId) { this.refId = refId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}