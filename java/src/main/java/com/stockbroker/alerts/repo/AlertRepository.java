package com.stockbroker.alerts.repo;

import com.stockbroker.alerts.domain.Alert;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Alert repository.
 */
@Repository
public interface AlertRepository extends CrudRepository<Alert, UUID> {

    @Query("SELECT * FROM alerts WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    List<Alert> findByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT * FROM alerts WHERE id = :id AND user_id = :userId")
    Optional<Alert> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT * FROM alerts WHERE symbol = :symbol AND metric = :metric AND status = 'ACTIVE'")
    List<Alert> findActiveBySymbolAndMetric(@Param("symbol") String symbol, @Param("metric") String metric);

    @Query("SELECT * FROM alerts WHERE status = :status ORDER BY created_at DESC")
    List<Alert> findAllByStatus(@Param("status") String status);

    @Query("SELECT COUNT(*) FROM alerts WHERE user_id = :userId AND status = :status")
    long countByUserIdAndStatus(@Param("userId") UUID userId, @Param("status") String status);
}