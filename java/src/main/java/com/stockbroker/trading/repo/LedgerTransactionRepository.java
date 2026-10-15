package com.stockbroker.trading.repo;

import com.stockbroker.trading.domain.LedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ledger transaction repository.
 */
@Repository
public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    @Query(value = "SELECT * FROM ledger_transactions WHERE account_id = :accountId ORDER BY created_at DESC", nativeQuery = true)
    List<LedgerTransaction> findByAccountId(@Param("accountId") UUID accountId);

    @Query(value = "SELECT * FROM ledger_transactions WHERE user_id = :userId ORDER BY created_at DESC", nativeQuery = true)
    List<LedgerTransaction> findByUserId(@Param("userId") UUID userId);

    @Query(value = "SELECT * FROM ledger_transactions WHERE id = :id", nativeQuery = true)
    Optional<LedgerTransaction> findById(@Param("id") UUID id);

    @Query(value = "SELECT * FROM ledger_transactions WHERE ref_type = :refType AND ref_id = :refId", nativeQuery = true)
    Optional<LedgerTransaction> findByRef(@Param("refType") String refType, @Param("refId") UUID refId);
}