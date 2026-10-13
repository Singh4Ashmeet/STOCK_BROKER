package com.stockbroker.trading.repo;

import com.stockbroker.trading.domain.LedgerAccount;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ledger account repository.
 */
@Repository
public interface LedgerAccountRepository extends CrudRepository<LedgerAccount, UUID> {

    @Query("SELECT * FROM ledger_accounts WHERE account_id = :accountId")
    List<LedgerAccount> findByAccountId(@Param("accountId") UUID accountId);

    @Query("SELECT * FROM ledger_accounts WHERE account_id = :accountId AND asset = :asset")
    Optional<LedgerAccount> findByAccountIdAndAsset(@Param("accountId") UUID accountId, @Param("asset") String asset);

    @Query("SELECT * FROM ledger_accounts WHERE account_id = :accountId AND code = :code")
    Optional<LedgerAccount> findByAccountIdAndCode(@Param("accountId") UUID accountId, @Param("code") String code);
}