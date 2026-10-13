package com.stockbroker.trading.repo;

import com.stockbroker.trading.domain.Position;
import com.stockbroker.trading.domain.PositionId;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Position repository.
 */
@Repository
public interface PositionRepository extends CrudRepository<Position, PositionId> {

    @Query("SELECT * FROM positions WHERE account_id = :accountId")
    List<Position> findByAccountId(@Param("accountId") UUID accountId);
}