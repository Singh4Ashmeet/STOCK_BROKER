package com.stockbroker.trading.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Fill entity.
 */
@Entity
@Table(name = "fills")
public class Fill {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "side", nullable = false)
    private String side;

    @Column(name = "price", nullable = false, precision = 38, scale = 18)
    private BigDecimal price;

    @Column(name = "qty", nullable = false, precision = 38, scale = 18)
    private BigDecimal qty;

    @Column(name = "fee_asset", nullable = false)
    private String feeAsset;

    @Column(name = "fee_amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal feeAmount;

    @Column(name = "liquidity", nullable = false)
    private String liquidity;

    @Column(name = "book_epoch")
    private Long bookEpoch;

    @Column(name = "book_seq")
    private Long bookSeq;

    @Column(name = "filled_at", nullable = false)
    private Instant filledAt;

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public String getFeeAsset() { return feeAsset; }
    public void setFeeAsset(String feeAsset) { this.feeAsset = feeAsset; }
    public BigDecimal getFeeAmount() { return feeAmount; }
    public void setFeeAmount(BigDecimal feeAmount) { this.feeAmount = feeAmount; }
    public String getLiquidity() { return liquidity; }
    public void setLiquidity(String liquidity) { this.liquidity = liquidity; }
    public Long getBookEpoch() { return bookEpoch; }
    public void setBookEpoch(Long bookEpoch) { this.bookEpoch = bookEpoch; }
    public Long getBookSeq() { return bookSeq; }
    public void setBookSeq(Long bookSeq) { this.bookSeq = bookSeq; }
    public Instant getFilledAt() { return filledAt; }
    public void setFilledAt(Instant filledAt) { this.filledAt = filledAt; }
}