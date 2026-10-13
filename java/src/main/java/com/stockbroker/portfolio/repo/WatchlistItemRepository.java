package com.stockbroker.portfolio.repo;

import com.stockbroker.portfolio.domain.WatchlistItem;
import com.stockbroker.portfolio.domain.WatchlistItemId;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Watchlist item repository.
 */
@Repository
public interface WatchlistItemRepository extends CrudRepository<WatchlistItem, WatchlistItemId> {

    @Query("SELECT * FROM watchlist_items WHERE watchlist_id = :watchlistId ORDER BY position")
    List<WatchlistItem> findByWatchlistId(@Param("watchlistId") UUID watchlistId);

    @Query("SELECT COUNT(*) > 0 FROM watchlist_items WHERE watchlist_id = :watchlistId AND symbol = :symbol")
    boolean existsByWatchlistIdAndSymbol(@Param("watchlistId") UUID watchlistId, @Param("symbol") String symbol);

    @Query("DELETE FROM watchlist_items WHERE watchlist_id = :watchlistId AND symbol = :symbol")
    void deleteByWatchlistIdAndSymbol(@Param("watchlistId") UUID watchlistId, @Param("symbol") String symbol);
}