package com.stockbroker.portfolio.repo;

import com.stockbroker.portfolio.domain.Watchlist;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Watchlist repository.
 */
@Repository
public interface WatchlistRepository extends CrudRepository<Watchlist, UUID> {

    @Query("SELECT * FROM watchlists WHERE user_id = :userId ORDER BY created_at DESC")
    List<Watchlist> findByUserId(@Param("userId") UUID userId);

    @Query("SELECT * FROM watchlists WHERE id = :id AND user_id = :userId")
    Optional<Watchlist> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);
}