package com.stockbroker.trading.repo;

import com.stockbroker.trading.domain.Fill;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Fill repository.
 */
@Repository
public interface FillRepository extends CrudRepository<Fill, UUID> {

    @Query("SELECT * FROM fills WHERE order_id = :orderId ORDER BY filled_at DESC")
    List<Fill> findByOrderId(@Param("orderId") UUID orderId);

    @Query("SELECT * FROM fills WHERE user_id = :userId ORDER BY filled_at DESC LIMIT :limit OFFSET :offset")
    List<Fill> findByUserId(@Param("userId") UUID userId, Pageable pageable);
}