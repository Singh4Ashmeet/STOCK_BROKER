package com.stockbroker.trading.repo;

import com.stockbroker.trading.domain.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Order repository.
 */
@Repository
public interface OrderRepository extends CrudRepository<Order, UUID> {

    @Query("SELECT * FROM orders WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    List<Order> findByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT * FROM orders WHERE id = :id AND user_id = :userId")
    Optional<Order> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT * FROM orders WHERE user_id = :userId AND idempotency_key = :key")
    Optional<Order> findByUserIdAndIdempotencyKey(@Param("userId") UUID userId, @Param("key") String key);

    @Query("SELECT COUNT(*) FROM orders WHERE user_id = :userId AND status IN (:statuses)")
    long countByUserIdAndStatusIn(@Param("userId") UUID userId, @Param("statuses") List<String> statuses);
}