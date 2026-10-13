package com.stockbroker.auth.repo;

import com.stockbroker.auth.domain.RefreshToken;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Refresh token repository.
 */
@Repository
public interface RefreshTokenRepository extends CrudRepository<RefreshToken, UUID> {

    @Query("SELECT * FROM refresh_tokens WHERE token_hash = :tokenHash")
    Optional<RefreshToken> findByTokenHash(byte[] tokenHash);

    @Query("SELECT * FROM refresh_tokens WHERE family_id = :familyId")
    List<RefreshToken> findByFamilyId(UUID familyId);

    @Query("DELETE FROM refresh_tokens WHERE expires_at < now()")
    void deleteExpiredTokens();
}