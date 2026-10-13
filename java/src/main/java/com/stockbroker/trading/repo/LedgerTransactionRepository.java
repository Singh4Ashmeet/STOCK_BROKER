package com.stockbroker.trading.repo;

import com.stockbroker.trading.domain.LedgerTransaction;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ledger transaction repository.
 */
@Repository
public interface LedgerTransactionRepository extends CrudRepository<LedgerTransaction, UUID> {

    @Query("SELECT * FROM ledger_transactions WHERE account_id = :accountId ORDER BY created_at DESC")
    List<LedgerTransaction> findByAccountId(@Param("accountId") UUID accountId);

    @Query("SELECT * FROM ledger_transactions WHERE user_id = :userId ORDER BY created_at DESC")
    List<LedgerTransaction> findByUserId(@Param("userId") UUID userId);

    @Query("SELECT * FROM ledger_transactions WHERE id = :id")
    Optional<LedgerTransaction> findById(@Param("id") UUID id);

    @Query("SELECT * FROM ledger_transactions WHERE ref_type = :refType AND ref_id = :refId")
    Optional<LedgerTransaction> findByRef(@Param("refType") String refType, @Param("refId") UUID refId);
}