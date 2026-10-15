package com.stockbroker.trading.repo;

import com.stockbroker.trading.domain.Fill;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Fill repository.
 */
@Repository
public interface FillRepository extends JpaRepository<Fill, UUID> {

    @Query(value = "SELECT * FROM fills WHERE order_id = :orderId ORDER BY filled_at DESC", nativeQuery = true)
    List<Fill> findByOrderId(@Param("orderId") UUID orderId);

    @Query(value = "SELECT * FROM fills WHERE user_id = :userId ORDER BY filled_at DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<Fill> findByUserId(@Param("userId") UUID userId, Pageable pageable);
}