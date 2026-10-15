package com.stockbroker.trading.repo;

import com.stockbroker.trading.domain.LedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Ledger entry repository.
 */
@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    @Query(value = "SELECT * FROM ledger_entries WHERE transaction_id = :transactionId", nativeQuery = true)
    List<LedgerEntry> findByTransactionId(@Param("transactionId") UUID transactionId);

    @Query(value = "SELECT * FROM ledger_entries WHERE ledger_account = :ledgerAccount ORDER BY created_at DESC", nativeQuery = true)
    List<LedgerEntry> findByLedgerAccount(@Param("ledgerAccount") UUID ledgerAccount, Pageable pageable);

    @Query(value = "SELECT * FROM ledger_entries WHERE asset = :asset AND created_at >= :from AND created_at <= :to ORDER BY created_at DESC", nativeQuery = true)
    List<LedgerEntry> findByAssetAndDateRange(
            @Param("asset") String asset,
            @Param("from") java.time.Instant from,
            @Param("to") java.time.Instant to);

    // For reconciliation: get all entries for an account
    @Query(value = """
        SELECT le.* FROM ledger_entries le
        JOIN ledger_transactions lt ON le.transaction_id = lt.id
        WHERE lt.account_id = :accountId
        ORDER BY le.created_at
        """, nativeQuery = true)
    List<LedgerEntry> findByAccountId(@Param("accountId") UUID accountId);

    // Sum amounts by account and asset for balance calculation
    @Query(value = """
        SELECT COALESCE(SUM(le.amount), 0) FROM ledger_entries le
        JOIN ledger_transactions lt ON le.transaction_id = lt.id
        WHERE lt.account_id = :accountId AND le.asset = :asset
        """, nativeQuery = true)
    List<Object[]> sumByAccountAndAsset(@Param("accountId") UUID accountId, @Param("asset") String asset);
}