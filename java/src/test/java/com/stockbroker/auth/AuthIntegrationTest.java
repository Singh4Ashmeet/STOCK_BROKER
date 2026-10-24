package com.stockbroker.auth;

import com.stockbroker.common.LocalTestConfiguration;
import com.stockbroker.auth.api.AuthDtos;
import com.stockbroker.auth.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for authentication endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = LocalTestConfiguration.class)
@org.springframework.test.context.ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    UserRepository userRepository;

    @Autowired(required = false)
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

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
    void registerAndLogin() {
        String email = "test-" + java.util.UUID.randomUUID() + "@example.com";
        // Register
        AuthDtos.RegisterRequest registerRequest = new AuthDtos.RegisterRequest(
                email,
                "SecurePass123!"
        );

        ResponseEntity<AuthDtos.RegisterResponse> registerResponse = restTemplate.postForEntity(
                "/v1/auth/register",
                registerRequest,
                AuthDtos.RegisterResponse.class
        );

        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registerResponse.getBody()).isNotNull();
        assertThat(registerResponse.getBody().email()).isEqualTo(email);
        assertThat(registerResponse.getBody().role()).isEqualTo("USER");

        // Login
        AuthDtos.LoginRequest loginRequest = new AuthDtos.LoginRequest(
                email,
                "SecurePass123!"
        );

        ResponseEntity<AuthDtos.LoginResponse> loginResponse = restTemplate.postForEntity(
                "/v1/auth/login",
                loginRequest,
                AuthDtos.LoginResponse.class
        );

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).isNotNull();
        assertThat(loginResponse.getBody().accessToken()).isNotBlank();
        assertThat(loginResponse.getBody().refreshToken()).isNotBlank();

        // Verify cookie is set
        HttpHeaders headers = loginResponse.getHeaders();
        assertThat(headers.getFirst(HttpHeaders.SET_COOKIE)).contains("refresh_token");
    }

    @Test
    void duplicateRegistrationFails() {
        String email = "dup-" + java.util.UUID.randomUUID() + "@example.com";
        AuthDtos.RegisterRequest request = new AuthDtos.RegisterRequest(
                email,
                "SecurePass123!"
        );

        // First registration
        ResponseEntity<AuthDtos.RegisterResponse> firstResp = restTemplate.postForEntity("/v1/auth/register", request, AuthDtos.RegisterResponse.class);
        assertThat(firstResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Second registration should fail
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/v1/auth/register",
                request,
                String.class
        );

        assertThat(response.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("EMAIL_EXISTS");
    }

    @Test
    void invalidLoginFails() {
        AuthDtos.LoginRequest request = new AuthDtos.LoginRequest(
                "nonexistent-" + java.util.UUID.randomUUID() + "@example.com",
                "WrongPass123!"
        );

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/v1/auth/login",
                request,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("AUTH_INVALID_CREDENTIALS");
    }

    @Test
    void weakPasswordRejected() {
        String email = "weak-" + java.util.UUID.randomUUID() + "@example.com";
        AuthDtos.RegisterRequest request = new AuthDtos.RegisterRequest(
                email,
                "weak"
        );

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/v1/auth/register",
                request,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_FAILED");
    }
}
