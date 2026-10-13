package com.stockbroker.trading.repo;

import com.stockbroker.trading.domain.Account;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Account repository.
 */
@Repository
public interface AccountRepository extends CrudRepository<Account, UUID> {

    @Query("SELECT * FROM accounts WHERE user_id = :userId AND name = :name")
    Optional<Account> findByUserIdAndName(@Param("userId") UUID userId, @Param("name") String name);

    @Query("SELECT * FROM accounts WHERE user_id = :userId ORDER BY created_at")
    List<Account> findByUserId(@Param("userId") UUID userId);

    @Query("SELECT * FROM accounts WHERE id = :id AND user_id = :userId")
    Optional<Account> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT * FROM accounts WHERE id = :id")
    Optional<Account> findById(@Param("id") UUID id);
}