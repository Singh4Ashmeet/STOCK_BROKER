package com.stockbroker.portfolio.domain;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * Watchlist item entity.
 */
@Entity
@Table(name = "watchlist_items")
@IdClass(WatchlistItemId.class)
public class WatchlistItem {

    @Id
    @Column(name = "watchlist_id", nullable = false)
    private UUID watchlistId;

    @Id
    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "position", nullable = false)
    private int position;

    // Getters and setters
    public UUID getWatchlistId() { return watchlistId; }
    public void setWatchlistId(UUID watchlistId) { this.watchlistId = watchlistId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
}