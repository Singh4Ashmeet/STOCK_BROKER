package com.stockbroker.portfolio.repo;

import com.stockbroker.portfolio.domain.Watchlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Watchlist repository.
 */
@Repository
public interface WatchlistRepository extends JpaRepository<Watchlist, UUID> {

    @Query(value = "SELECT * FROM watchlists WHERE user_id = :userId ORDER BY created_at DESC", nativeQuery = true)
    List<Watchlist> findByUserId(@Param("userId") UUID userId);

    @Query(value = "SELECT * FROM watchlists WHERE id = :id AND user_id = :userId", nativeQuery = true)
    Optional<Watchlist> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);
}