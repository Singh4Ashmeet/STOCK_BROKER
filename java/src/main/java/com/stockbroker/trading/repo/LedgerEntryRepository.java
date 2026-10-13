package com.stockbroker.trading.repo;

import com.stockbroker.trading.domain.LedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Ledger entry repository.
 */
@Repository
public interface LedgerEntryRepository extends CrudRepository<LedgerEntry, Long> {

    @Query("SELECT * FROM ledger_entries WHERE transaction_id = :transactionId")
    List<LedgerEntry> findByTransactionId(@Param("transactionId") UUID transactionId);

    @Query("SELECT * FROM ledger_entries WHERE ledger_account = :ledgerAccount ORDER BY created_at DESC")
    List<LedgerEntry> findByLedgerAccount(@Param("ledgerAccount") UUID ledgerAccount, Pageable pageable);

    @Query("SELECT * FROM ledger_entries WHERE asset = :asset AND created_at >= :from AND created_at <= :to ORDER BY created_at DESC")
    List<LedgerEntry> findByAssetAndDateRange(
            @Param("asset") String asset,
            @Param("from") java.time.Instant from,
            @Param("to") java.time.Instant to);

    // For reconciliation: get all entries for an account
    @Query("""
        SELECT le.* FROM ledger_entries le
        JOIN ledger_transactions lt ON le.transaction_id = lt.id
        WHERE lt.account_id = :accountId
        ORDER BY le.created_at
        """)
    List<LedgerEntry> findByAccountId(@Param("accountId") UUID accountId);

    // Sum amounts by account and asset for balance calculation
    @Query("""
        SELECT COALESCE(SUM(le.amount), 0) FROM ledger_entries le
        JOIN ledger_transactions lt ON le.transaction_id = lt.id
        WHERE lt.account_id = :accountId AND le.asset = :asset
        """)
    List<Object[]> sumByAccountAndAsset(@Param("accountId") UUID accountId, @Param("asset") String asset);
}