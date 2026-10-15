package com.stockbroker.alerts.repo;

import com.stockbroker.alerts.domain.Alert;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Alert repository.
 */
@Repository
public interface AlertRepository extends JpaRepository<Alert, UUID> {

    @Query(value = "SELECT * FROM alerts WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<Alert> findByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query(value = "SELECT * FROM alerts WHERE id = :id AND user_id = :userId", nativeQuery = true)
    Optional<Alert> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query(value = "SELECT * FROM alerts WHERE symbol = :symbol AND metric = :metric AND status = 'ACTIVE'", nativeQuery = true)
    List<Alert> findActiveBySymbolAndMetric(@Param("symbol") String symbol, @Param("metric") String metric);

    @Query(value = "SELECT * FROM alerts WHERE status = :status ORDER BY created_at DESC", nativeQuery = true)
    List<Alert> findAllByStatus(@Param("status") String status);

    @Query(value = "SELECT COUNT(*) FROM alerts WHERE user_id = :userId AND status = :status", nativeQuery = true)
    long countByUserIdAndStatus(@Param("userId") UUID userId, @Param("status") String status);
}