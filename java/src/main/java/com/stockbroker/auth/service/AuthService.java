package com.stockbroker.auth.service;

import com.stockbroker.auth.domain.User;
import com.stockbroker.auth.domain.RefreshToken;
import com.stockbroker.auth.repo.UserRepository;
import com.stockbroker.auth.repo.RefreshTokenRepository;
import com.stockbroker.common.config.JwtProperties;
import com.stockbroker.common.error.BusinessProblem;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Authentication service handling registration, login, token management.
 * Implements login throttling, refresh token rotation with reuse detection.
 */
@Service
@Transactional(noRollbackFor = com.stockbroker.common.error.BusinessProblem.class)
public class AuthService {

    private static final String LOGIN_FAIL_PREFIX = "login:fail:";
    private static final String LOGIN_FAIL_IP_PREFIX = "login:fail:ip:";
    private static final String WS_TICKET_PREFIX = "wsticket:";
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int LOCKOUT_MINUTES = 30;
    private static final int WS_TICKET_TTL_SECONDS = 30;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;
    private final RedisTemplate<String, String> redisTemplate;
    private final RSASSASigner signer;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtProperties jwtProperties,
                       ResourceLoader resourceLoader,
                       RedisTemplate<String, String> redisTemplate) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtProperties = jwtProperties;
        this.redisTemplate = redisTemplate;

        try {
            Resource resource = resourceLoader.getResource(jwtProperties.getPrivateKeyPath());
            try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[1024];
                int len;
                while ((len = reader.read(buf)) != -1) {
                    sb.append(buf, 0, len);
                }
                // Strip PEM headers/footers and decode Base64
                String pem = sb.toString()
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
                byte[] decoded = Base64.getDecoder().decode(pem);
                PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                RSAPrivateKey privateKey = (RSAPrivateKey) kf.generatePrivate(spec);
                this.signer = new RSASSASigner(privateKey);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize JWT signer", e);
        }
    }

    public RegisterResult register(String email, String password) {
        // Check if email exists
        if (userRepository.existsByEmail(email)) {
            throw new BusinessProblem.ValidationFailed("Email already registered",
                    Map.of("field", "email", "code", "EMAIL_EXISTS"));
        }

        // Hash password
        String passwordHash = passwordEncoder.encode(password);

        // Create user
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setRole("USER");
        user.setStatus("ACTIVE");

        userRepository.save(user);

        return new RegisterResult(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }

    public LoginResult login(String email, String password, String clientIp) {
        // Check IP-based throttling first
        checkIpThrottling(clientIp);

        // Check account-based throttling
        checkAccountThrottling(email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    recordLoginFailure(email, clientIp);
                    return new BusinessProblem.AuthInvalidCredentials();
                });

        // Check if account is locked
        if ("LOCKED".equals(user.getStatus())) {
            if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
                recordLoginFailure(email, clientIp);
                throw new BusinessProblem.AuthInvalidCredentials();
            } else {
                // Lockout expired, unlock
                user.setStatus("ACTIVE");
                user.setLockedUntil(null);
                user.setFailedLogins(0);
            }
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            // Increment failed logins
            user.setFailedLogins(user.getFailedLogins() + 1);
            recordLoginFailure(email, clientIp);

            if (user.getFailedLogins() >= MAX_LOGIN_ATTEMPTS) {
                user.setStatus("LOCKED");
                user.setLockedUntil(Instant.now().plusSeconds(LOCKOUT_MINUTES * 60L));
            }
            userRepository.save(user);
            throw new BusinessProblem.AuthInvalidCredentials();
        }

        // Successful login - reset failed logins
        user.setFailedLogins(0);
        user.setLockedUntil(null);
        user.setStatus("ACTIVE");
        userRepository.save(user);

        // Clear login failure counters
        clearLoginFailures(email, clientIp);

        // Generate tokens
        String accessToken = generateAccessToken(user);
        var refreshToken = createRefreshToken(user.getId());

        return new LoginResult(accessToken, refreshToken.getToken());
    }

    public RefreshResult refresh(String refreshTokenValue) {
        // Hash the provided token to look it up
        String tokenHash = hashToken(refreshTokenValue);

        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash.getBytes())
                .orElseThrow(() -> new BusinessProblem.AuthTokenInvalid("Invalid refresh token"));

        // Check if token is expired
        if (storedToken.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessProblem.AuthTokenExpired();
        }

        // Check if token was already used (rotation) - REUSE DETECTION
        if (storedToken.getUsedAt() != null) {
            // Token reuse detected - revoke entire family
            revokeFamily(storedToken.getFamilyId());
            throw new BusinessProblem.AuthRefreshReused();
        }

        // Check if token is revoked
        if (storedToken.getRevokedAt() != null) {
            throw new BusinessProblem.AuthTokenInvalid("Refresh token revoked");
        }

        // Get user
        User user = userRepository.findById(storedToken.getUserId())
                .orElseThrow(() -> new BusinessProblem.AuthTokenInvalid("User not found"));

        // Check if user is still active
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessProblem.AuthTokenInvalid("User account is not active");
        }

        // Mark current token as used
        storedToken.setUsedAt(Instant.now());
        refreshTokenRepository.save(storedToken);

        // Create new refresh token (rotation) - same family
        var newRefreshToken = createRefreshToken(user.getId(), storedToken.getFamilyId());

        // Generate new access token
        String accessToken = generateAccessToken(user);

        return new RefreshResult(accessToken, newRefreshToken.getToken());
    }

    public void logout(String refreshTokenValue) {
        if (refreshTokenValue != null && !refreshTokenValue.isBlank()) {
            String tokenHash = hashToken(refreshTokenValue);
            refreshTokenRepository.findByTokenHash(tokenHash.getBytes()).ifPresent(token -> {
                // Revoke the entire family on explicit logout
                revokeFamily(token.getFamilyId());
            });
        }
    }

    public User getUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessProblem.InternalError("User not found"));
    }

    public WsTicketResult createWsTicket(UUID userId) {
        String ticket = UUID.randomUUID().toString();
        int expiresIn = WS_TICKET_TTL_SECONDS;

        // Store in Redis with TTL
        String key = WS_TICKET_PREFIX + ticket;
        redisTemplate.opsForValue().set(key, userId.toString(), Duration.ofSeconds(expiresIn));

        return new WsTicketResult(ticket, expiresIn);
    }

    public UUID validateAndConsumeWsTicket(String ticket) {
        String key = WS_TICKET_PREFIX + ticket;
        String userIdStr = redisTemplate.opsForValue().getAndDelete(key);
        if (userIdStr == null) {
            return null;
        }
        return UUID.fromString(userIdStr);
    }

    private void checkAccountThrottling(String email) {
        String emailHash = hashEmail(email);
        String key = LOGIN_FAIL_PREFIX + emailHash;
        String attempts = redisTemplate.opsForValue().get(key);
        if (attempts != null && Integer.parseInt(attempts) >= MAX_LOGIN_ATTEMPTS) {
            throw new BusinessProblem.RateLimited(LOCKOUT_MINUTES * 60);
        }
    }

    private void checkIpThrottling(String clientIp) {
        String key = LOGIN_FAIL_IP_PREFIX + clientIp;
        String attempts = redisTemplate.opsForValue().get(key);
        if (attempts != null && Integer.parseInt(attempts) >= (MAX_LOGIN_ATTEMPTS * 2 - 1)) {
            throw new BusinessProblem.RateLimited(LOCKOUT_MINUTES * 60);
        }
    }

    private void recordLoginFailure(String email, String clientIp) {
        String emailHash = hashEmail(email);
        String accountKey = LOGIN_FAIL_PREFIX + emailHash;
        String ipKey = LOGIN_FAIL_IP_PREFIX + clientIp;

        redisTemplate.opsForValue().increment(accountKey);
        redisTemplate.opsForValue().increment(ipKey);
        redisTemplate.expire(accountKey, Duration.ofMinutes(15));
        redisTemplate.expire(ipKey, Duration.ofMinutes(15));
    }

    private void clearLoginFailures(String email, String clientIp) {
        String emailHash = hashEmail(email);
        String accountKey = LOGIN_FAIL_PREFIX + emailHash;
        String ipKey = LOGIN_FAIL_IP_PREFIX + clientIp;

        redisTemplate.delete(accountKey);
        redisTemplate.delete(ipKey);
    }

    private String hashEmail(String email) {
        try {
            return java.util.Base64.getEncoder().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256").digest(email.getBytes())
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash email", e);
        }
    }

    private String generateAccessToken(User user) {
        try {
            Instant now = Instant.now();
            Instant expiresAt = now.plus(jwtProperties.getAccessTokenExpiration());

            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(user.getId().toString())
                    .issuer(jwtProperties.getIssuer())
                    .audience(jwtProperties.getAudience())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(expiresAt))
                    .claim("role", user.getRole())
                    .claim("email", user.getEmail())
                    .jwtID(UUID.randomUUID().toString())
                    .build();

            SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            signedJWT.sign(signer);
            return signedJWT.serialize();

        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate access token", e);
        }
    }

    private RefreshToken createRefreshToken(UUID userId) {
        return createRefreshToken(userId, UUID.randomUUID());
    }

    private RefreshToken createRefreshToken(UUID userId, UUID familyId) {
        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setFamilyId(familyId);
        token.setToken(generateOpaqueToken());
        token.setTokenHash(hashToken(token.getToken()).getBytes());
        token.setExpiresAt(Instant.now().plus(jwtProperties.getRefreshTokenExpiration()));
        refreshTokenRepository.save(token);
        return token;
    }

    private void revokeFamily(UUID familyId) {
        refreshTokenRepository.findByFamilyId(familyId).forEach(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(Instant.now());
                refreshTokenRepository.save(token);
            }
        });
    }

    private String generateOpaqueToken() {
        return UUID.randomUUID().toString() + UUID.randomUUID().toString();
    }

    private String hashToken(String token) {
        try {
            return java.util.Base64.getEncoder().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes())
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash token", e);
        }
    }

    public record RegisterResult(UUID userId, String email, String role, Instant createdAt) {}
    public record LoginResult(String accessToken, String refreshToken) {}
    public record RefreshResult(String accessToken, String refreshToken) {}
    public record WsTicketResult(String ticket, int expiresIn) {}
}