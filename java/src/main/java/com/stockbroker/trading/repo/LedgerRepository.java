package com.stockbroker.trading.repo;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ledger repository for querying ledger entries.
 */
@Repository
public interface LedgerRepository extends CrudRepository<Object, UUID> {

    @Query("""
        SELECT le.id, le.asset, le.amount, lt.kind as type, lt.ref_type, lt.ref_id, le.created_at
        FROM ledger_entries le
        JOIN ledger_transactions lt ON le.transaction_id = lt.id
        WHERE lt.user_id = :userId
        ORDER BY le.created_at DESC
        LIMIT :limit
        """)
    List<Object[]> findByUserId(@Param("userId") UUID userId, @Param("limit") int limit);

    @Query("""
        SELECT le.id, le.asset, le.amount, lt.kind as type, lt.ref_type, lt.ref_id, le.created_at
        FROM ledger_entries le
        JOIN ledger_transactions lt ON le.transaction_id = lt.id
        WHERE lt.user_id = :userId
          AND le.created_at >= :from
          AND le.created_at <= :to
        ORDER BY le.created_at DESC
        LIMIT :limit
        """)
    List<Object[]> findByUserIdAndTimeRange(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("limit") int limit);
}