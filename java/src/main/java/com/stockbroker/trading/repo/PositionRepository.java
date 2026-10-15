package com.stockbroker.trading.repo;

import com.stockbroker.trading.domain.Position;
import com.stockbroker.trading.domain.PositionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Position repository.
 */
@Repository
public interface PositionRepository extends JpaRepository<Position, PositionId> {

    @Query(value = "SELECT * FROM positions WHERE account_id = :accountId", nativeQuery = true)
    List<Position> findByAccountId(@Param("accountId") UUID accountId);
}