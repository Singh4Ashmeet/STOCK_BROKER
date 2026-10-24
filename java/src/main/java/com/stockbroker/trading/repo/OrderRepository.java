package com.stockbroker.trading.repo;

import com.stockbroker.trading.domain.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Order repository.
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Query(value = "SELECT * FROM orders WHERE user_id = :userId ORDER BY created_at DESC", nativeQuery = true)
    List<Order> findByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query(value = "SELECT * FROM orders WHERE id = :id AND user_id = :userId", nativeQuery = true)
    Optional<Order> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query(value = "SELECT * FROM orders WHERE user_id = :userId AND idempotency_key = :key", nativeQuery = true)
    Optional<Order> findByUserIdAndIdempotencyKey(@Param("userId") UUID userId, @Param("key") String key);

    @Query(value = "SELECT COUNT(*) FROM orders WHERE user_id = :userId AND status IN (:statuses)", nativeQuery = true)
    long countByUserIdAndStatusIn(@Param("userId") UUID userId, @Param("statuses") List<String> statuses);
}