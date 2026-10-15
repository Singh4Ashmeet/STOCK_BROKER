package com.stockbroker.trading.repo;

import com.stockbroker.trading.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Account repository.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    @Query(value = "SELECT * FROM accounts WHERE user_id = :userId AND name = :name", nativeQuery = true)
    Optional<Account> findByUserIdAndName(@Param("userId") UUID userId, @Param("name") String name);

    @Query(value = "SELECT * FROM accounts WHERE user_id = :userId ORDER BY created_at", nativeQuery = true)
    List<Account> findByUserId(@Param("userId") UUID userId);

    @Query(value = "SELECT * FROM accounts WHERE id = :id AND user_id = :userId", nativeQuery = true)
    Optional<Account> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query(value = "SELECT * FROM accounts WHERE id = :id", nativeQuery = true)
    Optional<Account> findById(@Param("id") UUID id);
}