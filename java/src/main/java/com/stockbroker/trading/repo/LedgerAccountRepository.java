package com.stockbroker.trading.repo;

import com.stockbroker.trading.domain.LedgerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ledger account repository.
 */
@Repository
public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    @Query(value = "SELECT * FROM ledger_accounts WHERE account_id = :accountId", nativeQuery = true)
    List<LedgerAccount> findByAccountId(@Param("accountId") UUID accountId);

    @Query(value = "SELECT * FROM ledger_accounts WHERE account_id = :accountId AND asset = :asset", nativeQuery = true)
    Optional<LedgerAccount> findByAccountIdAndAsset(@Param("accountId") UUID accountId, @Param("asset") String asset);

    @Query(value = "SELECT * FROM ledger_accounts WHERE account_id = :accountId AND code = :code", nativeQuery = true)
    Optional<LedgerAccount> findByAccountIdAndCode(@Param("accountId") UUID accountId, @Param("code") String code);

    @Query(value = "SELECT * FROM ledger_accounts WHERE account_id IS NULL AND code = :code", nativeQuery = true)
    Optional<LedgerAccount> findSystemAccountByCode(@Param("code") String code);
}