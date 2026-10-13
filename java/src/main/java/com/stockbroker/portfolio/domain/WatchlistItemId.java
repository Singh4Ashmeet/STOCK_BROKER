package com.stockbroker.portfolio.domain;

import java.io.Serializable;
import java.util.UUID;

/**
 * Composite key for WatchlistItem entity.
 */
public class WatchlistItemId implements Serializable {

    private UUID watchlistId;
    private String symbol;

    public WatchlistItemId() {}

    public WatchlistItemId(UUID watchlistId, String symbol) {
        this.watchlistId = watchlistId;
        this.symbol = symbol;
    }

    public UUID getWatchlistId() { return watchlistId; }
    public void setWatchlistId(UUID watchlistId) { this.watchlistId = watchlistId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WatchlistItemId that = (WatchlistItemId) o;
        return watchlistId.equals(that.watchlistId) && symbol.equals(that.symbol);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(watchlistId, symbol);
    }
}