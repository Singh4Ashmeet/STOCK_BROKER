package com.stockbroker.trading.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Ledger account - represents a specific asset within a trading account
 * (e.g., CASH:USDT, ASSET:BTC, FEES:USDT).
 * 
 * Each trading account has multiple ledger accounts, one per asset type.
 */
@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "code", nullable = false)
    private String code; // e.g., "CASH:USDT", "ASSET:BTC", "FEES:USDT"

    @Column(name = "asset", nullable = false)
    private String asset; // e.g., "USDT", "BTC"

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
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getAsset() { return asset; }
    public void setAsset(String asset) { this.asset = asset; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}