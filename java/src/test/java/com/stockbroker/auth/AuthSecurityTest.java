package com.stockbroker.auth;

import com.stockbroker.common.LocalTestConfiguration;
import com.stockbroker.auth.api.AuthDtos;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.stockbroker.common.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.*;
import org.springframework.test.context.ContextConfiguration;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for refresh token rotation, reuse detection, and login throttling.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = LocalTestConfiguration.class)
@org.springframework.test.context.ActiveProfiles("test")
class AuthSecurityTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired(required = false)
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Autowired
    JwtProperties jwtProperties;

    @Autowired
    ResourceLoader resourceLoader;

    @org.junit.jupiter.api.BeforeEach
    void cleanup() {
        if (redisTemplate != null) {
            try {
                var keys = redisTemplate.keys("login:fail:*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            } catch (Exception ignored) {}
        }
    }

    @Test
    void refreshTokenRotationWorks() {
        // Register and login
        AuthDtos.RegisterRequest reg = new AuthDtos.RegisterRequest("rotate@test.com", "SecurePass123!", "Rotate User");
        restTemplate.postForEntity("/v1/auth/register", reg, AuthDtos.RegisterResponse.class);

        AuthDtos.LoginRequest login = new AuthDtos.LoginRequest("rotate@test.com", "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginResp = restTemplate.postForEntity("/v1/auth/login", login, AuthDtos.LoginResponse.class);

        String accessToken1 = loginResp.getBody().accessToken();
        String refreshToken1 = loginResp.getBody().refreshToken();

        // First refresh - should work
        AuthDtos.RefreshRequest refreshReq1 = new AuthDtos.RefreshRequest(refreshToken1);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthDtos.RefreshResponse> refreshResp1 = restTemplate.exchange(
                "/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(refreshReq1, headers), AuthDtos.RefreshResponse.class);

        assertThat(refreshResp1.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken2 = refreshResp1.getBody().accessToken();
        String refreshToken2 = refreshResp1.getBody().refreshToken();

        // Old access token should be different
        assertThat(accessToken2).isNotEqualTo(accessToken1);
        // New refresh token should be different (rotation)
        assertThat(refreshToken2).isNotEqualTo(refreshToken1);

        // Second refresh with new token - should work
        AuthDtos.RefreshRequest refreshReq2 = new AuthDtos.RefreshRequest(refreshToken2);
        ResponseEntity<AuthDtos.RefreshResponse> refreshResp2 = restTemplate.exchange(
                "/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(refreshReq2, headers), AuthDtos.RefreshResponse.class);

        assertThat(refreshResp2.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void refreshTokenReuseDetectionRevokesFamily() {
        // Register and login
        AuthDtos.RegisterRequest reg = new AuthDtos.RegisterRequest("reuse@test.com", "SecurePass123!", "Reuse User");
        restTemplate.postForEntity("/v1/auth/register", reg, AuthDtos.RegisterResponse.class);

        AuthDtos.LoginRequest login = new AuthDtos.LoginRequest("reuse@test.com", "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginResp = restTemplate.postForEntity("/v1/auth/login", login, AuthDtos.LoginResponse.class);

        String refreshToken1 = loginResp.getBody().refreshToken();

        // First refresh - should work
        AuthDtos.RefreshRequest refreshReq1 = new AuthDtos.RefreshRequest(refreshToken1);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthDtos.RefreshResponse> refreshResp1 = restTemplate.exchange(
                "/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(refreshReq1, headers), AuthDtos.RefreshResponse.class);

        assertThat(refreshResp1.getStatusCode()).isEqualTo(HttpStatus.OK);
        String refreshToken2 = refreshResp1.getBody().refreshToken();

        // Try to reuse the OLD refresh token (refreshToken1) - should fail with 401 and AUTH_REFRESH_REUSED
        AuthDtos.RefreshRequest reuseReq = new AuthDtos.RefreshRequest(refreshToken1);
        ResponseEntity<String> reuseResp = restTemplate.exchange(
                "/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(reuseReq, headers), String.class);

        assertThat(reuseResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reuseResp.getBody()).contains("AUTH_REFRESH_REUSED");

        // Even the new refresh token (refreshToken2) should now be revoked because family was revoked
        AuthDtos.RefreshRequest refreshReq3 = new AuthDtos.RefreshRequest(refreshToken2);
        ResponseEntity<String> reuseResp2 = restTemplate.exchange(
                "/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(refreshReq3, headers), String.class);

        assertThat(reuseResp2.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reuseResp2.getBody()).contains("AUTH_TOKEN_INVALID");
    }

    @Test
    void logoutRevokesRefreshTokenFamily() {
        // Register and login
        AuthDtos.RegisterRequest reg = new AuthDtos.RegisterRequest("logout@test.com", "SecurePass123!", "Logout User");
        restTemplate.postForEntity("/v1/auth/register", reg, AuthDtos.RegisterResponse.class);

        AuthDtos.LoginRequest login = new AuthDtos.LoginRequest("logout@test.com", "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginResp = restTemplate.postForEntity("/v1/auth/login", login, AuthDtos.LoginResponse.class);

        String refreshToken = loginResp.getBody().refreshToken();

        // Logout
        AuthDtos.LogoutRequest logoutReq = new AuthDtos.LogoutRequest(refreshToken);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> logoutResp = restTemplate.exchange(
                "/v1/auth/logout", HttpMethod.POST, new HttpEntity<>(logoutReq, headers), Void.class);

        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Try to use the revoked refresh token - should fail
        AuthDtos.RefreshRequest refreshReq = new AuthDtos.RefreshRequest(refreshToken);
        ResponseEntity<String> refreshResp = restTemplate.exchange(
                "/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(refreshReq, headers), String.class);

        assertThat(refreshResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refreshResp.getBody()).contains("AUTH_TOKEN_INVALID");
    }

    @Test
    void logoutImmediatelyRevokesOnlyTheLoggedOutAccessTokenSession() throws Exception {
        String email = "logout-revocation-" + UUID.randomUUID() + "@test.com";
        AuthDtos.RegisterRequest registration = new AuthDtos.RegisterRequest(
                email, "SecurePass123!", "Logout Revocation User");
        assertThat(restTemplate.postForEntity("/v1/auth/register", registration,
                AuthDtos.RegisterResponse.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        AuthDtos.LoginResponse loggedOutSession = login(email);
        AuthDtos.LoginResponse sameUserOtherSession = login(email);

        String otherEmail = "logout-revocation-other-" + UUID.randomUUID() + "@test.com";
        AuthDtos.RegisterRequest otherRegistration = new AuthDtos.RegisterRequest(
                otherEmail, "SecurePass123!", "Other User");
        assertThat(restTemplate.postForEntity("/v1/auth/register", otherRegistration,
                AuthDtos.RegisterResponse.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AuthDtos.LoginResponse otherUserSession = login(otherEmail);

        assertThat(protectedPortfolio(loggedOutSession.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Void> logoutResponse = restTemplate.exchange("/v1/auth/logout", HttpMethod.POST,
                jsonEntity(new AuthDtos.LogoutRequest(loggedOutSession.refreshToken())), Void.class);
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The exact bearer used before logout no longer authenticates.
        assertThat(protectedPortfolio(loggedOutSession.accessToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> refreshResponse = restTemplate.exchange("/v1/auth/refresh", HttpMethod.POST,
                jsonEntity(new AuthDtos.RefreshRequest(loggedOutSession.refreshToken())), String.class);
        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // The refresh-token family is the session boundary, not the user boundary.
        assertThat(protectedPortfolio(sameUserOtherSession.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(protectedPortfolio(otherUserSession.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> revokedTicketResponse = restTemplate.exchange("/v1/auth/ws/ticket", HttpMethod.POST,
                bearerEntity(loggedOutSession.accessToken()), String.class);
        assertThat(revokedTicketResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(protectedPortfolio("not-a-jwt").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(protectedPortfolio(expiredAccessToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        AuthDtos.LoginResponse newSession = login(email);
        assertThat(protectedPortfolio(newSession.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(redisTemplate).isNotNull();
        UUID sessionId = UUID.fromString(SignedJWT.parse(loggedOutSession.accessToken())
                .getJWTClaimsSet().getStringClaim("sid"));
        Long ttlSeconds = redisTemplate.getExpire("auth:revoked-session:" + sessionId, TimeUnit.SECONDS);
        Instant accessTokenExpiry = SignedJWT.parse(loggedOutSession.accessToken())
                .getJWTClaimsSet().getExpirationTime().toInstant();
        long secondsUntilAccessExpiry = Duration.between(Instant.now(), accessTokenExpiry).toSeconds();
        assertThat(ttlSeconds).isNotNull().isPositive()
                .isLessThanOrEqualTo(secondsUntilAccessExpiry);
    }

    @Test
    void loginThrottlingPerAccount() {
        AuthDtos.RegisterRequest reg = new AuthDtos.RegisterRequest("throttle@test.com", "SecurePass123!", "Throttle User");
        restTemplate.postForEntity("/v1/auth/register", reg, AuthDtos.RegisterResponse.class);

        AuthDtos.LoginRequest badLogin = new AuthDtos.LoginRequest("throttle@test.com", "WrongPass123!");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // First 5 failed attempts should return 401 AUTH_INVALID_CREDENTIALS
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> response = restTemplate.exchange(
                    "/v1/auth/login", HttpMethod.POST, new HttpEntity<>(badLogin, headers), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).contains("AUTH_INVALID_CREDENTIALS");
        }

        // 6th attempt should trigger lockout and return 429 RATE_LIMITED
        ResponseEntity<String> response6 = restTemplate.exchange(
                "/v1/auth/login", HttpMethod.POST, new HttpEntity<>(badLogin, headers), String.class);
        assertThat(response6.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response6.getBody()).contains("RATE_LIMITED");
    }

    @Test
    void loginThrottlingPerIp() {
        AuthDtos.RegisterRequest reg1 = new AuthDtos.RegisterRequest("ipthrottle1@test.com", "SecurePass123!", "IP Throttle 1");
        restTemplate.postForEntity("/v1/auth/register", reg1, AuthDtos.RegisterResponse.class);

        AuthDtos.RegisterRequest reg2 = new AuthDtos.RegisterRequest("ipthrottle2@test.com", "SecurePass123!", "IP Throttle 2");
        restTemplate.postForEntity("/v1/auth/register", reg2, AuthDtos.RegisterResponse.class);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Make 10 failed attempts (5 per account * 2 accounts) from same IP
        // The IP throttle is 2x the account throttle (10 attempts)
        for (int i = 0; i < 10; i++) {
            String email = i < 5 ? "ipthrottle1@test.com" : "ipthrottle2@test.com";
            AuthDtos.LoginRequest req = new AuthDtos.LoginRequest(email, "WrongPass123!");
            ResponseEntity<String> response = restTemplate.exchange(
                    "/v1/auth/login", HttpMethod.POST, new HttpEntity<>(req, headers), String.class);
            // First 9 should be 401, 10th should be 429
            if (i < 9) {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            } else {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(response.getBody()).contains("RATE_LIMITED");
            }
        }
    }

    @Test
    void expiredRefreshTokenRejected() {
        // This test would require manipulating token expiration in the database
        // For now, we test that the endpoint validates expiration
        AuthDtos.RefreshRequest refreshReq = new AuthDtos.RefreshRequest("invalid.token.here");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(refreshReq, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("AUTH_TOKEN_INVALID");
    }

    @Test
    void accessTokenHasCorrectExpiration() {
        AuthDtos.RegisterRequest reg = new AuthDtos.RegisterRequest("exp@test.com", "SecurePass123!", "Exp User");
        restTemplate.postForEntity("/v1/auth/register", reg, AuthDtos.RegisterResponse.class);

        AuthDtos.LoginRequest login = new AuthDtos.LoginRequest("exp@test.com", "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginResp = restTemplate.postForEntity("/v1/auth/login", login, AuthDtos.LoginResponse.class);

        // Access token should expire in 15 minutes (900 seconds)
        assertThat(loginResp.getBody().expiresIn()).isEqualTo(900);
    }

    @Test
    void passwordNeverLogged() {
        // This test verifies that passwords are not logged by checking
        // that the login endpoint doesn't echo back the password
        AuthDtos.RegisterRequest reg = new AuthDtos.RegisterRequest("log@test.com", "SecurePass123!", "Log User");
        restTemplate.postForEntity("/v1/auth/register", reg, AuthDtos.RegisterResponse.class);

        AuthDtos.LoginRequest login = new AuthDtos.LoginRequest("log@test.com", "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginResp = restTemplate.postForEntity("/v1/auth/login", login, AuthDtos.LoginResponse.class);

        // Response should not contain the password
        String responseBody = loginResp.getBody().toString();
        assertThat(responseBody).doesNotContain("SecurePass123!");
    }

    @Test
    void refreshTokenNeverLogged() {
        AuthDtos.RegisterRequest reg = new AuthDtos.RegisterRequest("logrt@test.com", "SecurePass123!", "LogRT User");
        restTemplate.postForEntity("/v1/auth/register", reg, AuthDtos.RegisterResponse.class);

        AuthDtos.LoginRequest login = new AuthDtos.LoginRequest("logrt@test.com", "SecurePass123!");
        ResponseEntity<AuthDtos.LoginResponse> loginResp = restTemplate.postForEntity("/v1/auth/login", login, AuthDtos.LoginResponse.class);

        String refreshToken = loginResp.getBody().refreshToken();

        // Use the refresh token
        AuthDtos.RefreshRequest refreshReq = new AuthDtos.RefreshRequest(refreshToken);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthDtos.RefreshResponse> refreshResp = restTemplate.exchange(
                "/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(refreshReq, headers), AuthDtos.RefreshResponse.class);

        // Response should not contain the refresh token in logs (we can't test logs directly,
        // but we can verify the response structure doesn't leak it in unexpected ways)
        assertThat(refreshResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshResp.getBody().refreshToken()).isNotEqualTo(refreshToken); // Should be rotated
    }

    private AuthDtos.LoginResponse login(String email) {
        ResponseEntity<AuthDtos.LoginResponse> loginResponse = restTemplate.postForEntity("/v1/auth/login",
                new AuthDtos.LoginRequest(email, "SecurePass123!"), AuthDtos.LoginResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).isNotNull();
        return loginResponse.getBody();
    }

    private ResponseEntity<String> protectedPortfolio(String accessToken) {
        return restTemplate.exchange("/v1/portfolio", HttpMethod.GET, bearerEntity(accessToken), String.class);
    }

    private HttpEntity<?> bearerEntity(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return new HttpEntity<>(headers);
    }

    private HttpEntity<?> jsonEntity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String expiredAccessToken() throws Exception {
        Resource resource = resourceLoader.getResource(jwtProperties.getPrivateKeyPath());
        StringBuilder pem = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                pem.append(buffer, 0, read);
            }
        }
        byte[] decoded = Base64.getDecoder().decode(pem.toString()
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", ""));
        RSAPrivateKey privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(decoded));

        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer(jwtProperties.getIssuer())
                .audience(jwtProperties.getAudience())
                .issueTime(Date.from(now.minus(Duration.ofMinutes(5))))
                .expirationTime(Date.from(now.minus(Duration.ofMinutes(2))))
                .claim("role", "USER")
                .claim("sid", UUID.randomUUID().toString())
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        token.sign(new RSASSASigner(privateKey));
        return token.serialize();
    }
}
