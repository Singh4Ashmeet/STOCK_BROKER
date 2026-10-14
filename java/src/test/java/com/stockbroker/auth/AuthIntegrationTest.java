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
class AuthIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    UserRepository userRepository;

    @Test
    void registerAndLogin() {
        // Register
        AuthDtos.RegisterRequest registerRequest = new AuthDtos.RegisterRequest(
                "test@example.com",
                "SecurePass123!"
        );

        ResponseEntity<AuthDtos.RegisterResponse> registerResponse = restTemplate.postForEntity(
                "/api/v1/auth/register",
                registerRequest,
                AuthDtos.RegisterResponse.class
        );

        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registerResponse.getBody()).isNotNull();
        assertThat(registerResponse.getBody().email()).isEqualTo("test@example.com");
        assertThat(registerResponse.getBody().role()).isEqualTo("USER");

        // Login
        AuthDtos.LoginRequest loginRequest = new AuthDtos.LoginRequest(
                "test@example.com",
                "SecurePass123!"
        );

        ResponseEntity<AuthDtos.LoginResponse> loginResponse = restTemplate.postForEntity(
                "/api/v1/auth/login",
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
        AuthDtos.RegisterRequest request = new AuthDtos.RegisterRequest(
                "duplicate@example.com",
                "SecurePass123!"
        );

        // First registration
        restTemplate.postForEntity("/api/v1/auth/register", request, AuthDtos.RegisterResponse.class);

        // Second registration should fail
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register",
                request,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("EMAIL_EXISTS");
    }

    @Test
    void invalidLoginFails() {
        AuthDtos.LoginRequest request = new AuthDtos.LoginRequest(
                "nonexistent@example.com",
                "WrongPass123!"
        );

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                request,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("AUTH_INVALID_CREDENTIALS");
    }

    @Test
    void weakPasswordRejected() {
        AuthDtos.RegisterRequest request = new AuthDtos.RegisterRequest(
                "weak@example.com",
                "weak"
        );

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register",
                request,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_FAILED");
    }
}
