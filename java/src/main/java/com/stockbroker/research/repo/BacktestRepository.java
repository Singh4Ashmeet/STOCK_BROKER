package com.stockbroker.research.repo;

import com.stockbroker.research.domain.BacktestRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Backtest run repository.
 */
@Repository
public interface BacktestRepository extends JpaRepository<BacktestRun, UUID> {

    @Query(value = "SELECT * FROM backtest_runs WHERE user_id = :userId ORDER BY created_at DESC", nativeQuery = true)
    List<BacktestRun> findByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query(value = "SELECT * FROM backtest_runs WHERE id = :id AND user_id = :userId", nativeQuery = true)
    Optional<BacktestRun> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);
}