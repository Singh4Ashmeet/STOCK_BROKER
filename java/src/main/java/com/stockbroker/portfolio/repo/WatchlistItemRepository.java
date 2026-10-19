package com.stockbroker.portfolio.repo;

import com.stockbroker.portfolio.domain.WatchlistItem;
import com.stockbroker.portfolio.domain.WatchlistItemId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Watchlist item repository.
 */
@Repository
public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, WatchlistItemId> {

    @Query(value = "SELECT * FROM watchlist_items WHERE watchlist_id = :watchlistId ORDER BY position", nativeQuery = true)
    List<WatchlistItem> findByWatchlistId(@Param("watchlistId") UUID watchlistId);

    @Query(value = "SELECT COUNT(*) > 0 FROM watchlist_items WHERE watchlist_id = :watchlistId AND symbol = :symbol", nativeQuery = true)
    boolean existsByWatchlistIdAndSymbol(@Param("watchlistId") UUID watchlistId, @Param("symbol") String symbol);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM watchlist_items WHERE watchlist_id = :watchlistId AND symbol = :symbol", nativeQuery = true)
    void deleteByWatchlistIdAndSymbol(@Param("watchlistId") UUID watchlistId, @Param("symbol") String symbol);
}