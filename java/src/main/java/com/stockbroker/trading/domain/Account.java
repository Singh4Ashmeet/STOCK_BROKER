package com.stockbroker.trading.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Account entity - paper trading account for a user.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "base_ccy", nullable = false)
    private String baseCcy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseCcy() { return baseCcy; }
    public void setBaseCcy(String baseCcy) { this.baseCcy = baseCcy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}