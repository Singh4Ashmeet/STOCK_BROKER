package com.stockbroker.auth.repo;

import com.stockbroker.auth.domain.User;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * User repository.
 */
@Repository
public interface UserRepository extends PagingAndSortingRepository<User, UUID>, CrudRepository<User, UUID> {

    @Query("SELECT * FROM users WHERE email = :email")
    Optional<User> findByEmail(String email);

    @Query("SELECT COUNT(*) > 0 FROM users WHERE email = :email")
    boolean existsByEmail(String email);
}