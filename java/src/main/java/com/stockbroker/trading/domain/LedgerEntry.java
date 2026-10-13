package com.stockbroker.trading.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Ledger entry - a single line in a double-entry transaction.
 * Positive amount = debit, negative amount = credit.
 * 
 * Invariant: For each transaction and asset, sum(amount) = 0.
 * This is enforced by a deferred constraint trigger in the database.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "ledger_account", nullable = false)
    private UUID ledgerAccount;

    @Column(name = "asset", nullable = false)
    private String asset;

    @Column(name = "amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }
    public UUID getLedgerAccount() { return ledgerAccount; }
    public void setLedgerAccount(UUID ledgerAccount) { this.ledgerAccount = ledgerAccount; }
    public String getAsset() { return asset; }
    public void setAsset(String asset) { this.asset = asset; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}