package com.stockbroker.auth.repo;

import com.stockbroker.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Refresh token repository.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Query(value = "SELECT * FROM refresh_tokens WHERE token_hash = :tokenHash", nativeQuery = true)
    Optional<RefreshToken> findByTokenHash(@Param("tokenHash") byte[] tokenHash);

    @Query(value = "SELECT * FROM refresh_tokens WHERE family_id = :familyId", nativeQuery = true)
    List<RefreshToken> findByFamilyId(@Param("familyId") UUID familyId);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM refresh_tokens WHERE expires_at < now()", nativeQuery = true)
    void deleteExpiredTokens();
}