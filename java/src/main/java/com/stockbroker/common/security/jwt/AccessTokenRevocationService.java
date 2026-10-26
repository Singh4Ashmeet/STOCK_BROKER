package com.stockbroker.common.security.jwt;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared, bounded revocation state for access-token sessions.
 *
 * <p>The key is a non-secret refresh-token family UUID, never a bearer token.
 * It is written only on revocation and expires within the access-token lifetime.
 */
@Service
public class AccessTokenRevocationService {

    private static final String REVOKED_SESSION_PREFIX = "auth:revoked-session:";
    private static final String SESSION_ACCESS_EXPIRY_PREFIX = "auth:session-access-expiry:";

    private final StringRedisTemplate redisTemplate;

    public AccessTokenRevocationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Records the latest access-token expiry for a session. This is a
     * non-secret timestamp used solely to bound later revocation state.
     */
    public void recordSessionAccessExpiry(UUID sessionId, Instant expiresAt) {
        try {
            Duration ttl = Duration.between(Instant.now(), expiresAt);
            if (ttl.isZero() || ttl.isNegative()) {
                throw new IllegalArgumentException("Access-token expiry must be in the future");
            }
            redisTemplate.opsForValue().set(expiryKey(sessionId), Long.toString(expiresAt.toEpochMilli()), ttl);
        } catch (RuntimeException exception) {
            throw new SessionRevocationStoreUnavailableException("Unable to record session access expiry", exception);
        }
    }

    /**
     * Marks one refresh-token family as revoked until the latest access token
     * in that session expires. Repeated revocations preserve that expiry rather
     * than extending the record indefinitely.
     */
    public void revokeSession(UUID sessionId) {
        try {
            String expiresAtMillis = redisTemplate.opsForValue().get(expiryKey(sessionId));
            if (expiresAtMillis == null) {
                throw new IllegalStateException("Missing session access expiry");
            }
            Instant expiresAt = Instant.ofEpochMilli(Long.parseLong(expiresAtMillis));
            Duration ttl = Duration.between(Instant.now(), expiresAt);
            if (ttl.isZero() || ttl.isNegative()) {
                redisTemplate.delete(expiryKey(sessionId));
                return;
            }
            redisTemplate.opsForValue().setIfAbsent(key(sessionId), "1", ttl);
        } catch (RuntimeException exception) {
            throw new SessionRevocationStoreUnavailableException("Unable to record session revocation", exception);
        }
    }

    /**
     * A store failure is deliberately surfaced so callers fail closed rather
     * than accepting a token whose revocation status cannot be determined.
     */
    public boolean isSessionRevoked(UUID sessionId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(sessionId)));
        } catch (RuntimeException exception) {
            throw new SessionRevocationStoreUnavailableException("Unable to check session revocation", exception);
        }
    }

    static String key(UUID sessionId) {
        return REVOKED_SESSION_PREFIX + sessionId;
    }

    private static String expiryKey(UUID sessionId) {
        return SESSION_ACCESS_EXPIRY_PREFIX + sessionId;
    }
}
