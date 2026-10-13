package com.stockbroker.trading.domain;

import java.io.Serializable;
import java.util.UUID;

/**
 * Composite key for Position entity.
 */
public class PositionId implements Serializable {

    private UUID accountId;
    private String symbol;

    public PositionId() {}

    public PositionId(UUID accountId, String symbol) {
        this.accountId = accountId;
        this.symbol = symbol;
    }

    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PositionId that = (PositionId) o;
        return accountId.equals(that.accountId) && symbol.equals(that.symbol);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(accountId, symbol);
    }
}