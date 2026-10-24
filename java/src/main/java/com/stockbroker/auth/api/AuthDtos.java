package com.stockbroker.auth.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Authentication API DTOs.
 */
public final class AuthDtos {

    private AuthDtos() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 12, max = 128) @Pattern(regexp = ".*[A-Z].*") // At least one uppercase
            @Pattern(regexp = ".*[a-z].*") // At least one lowercase
            @Pattern(regexp = ".*\\d.*") // At least one digit
            @Pattern(regexp = ".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*") // At least one special char
            String password
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegisterResponse(
            @JsonProperty("user_id") String userId,
            String email,
            String role,
            @JsonProperty("created_at") String createdAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoginResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") int expiresIn,
            @JsonProperty("refresh_token") String refreshToken
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefreshRequest(
            @JsonProperty("refresh_token") @NotBlank String refreshToken
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefreshResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") int expiresIn,
            @JsonProperty("refresh_token") String refreshToken
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LogoutRequest(
            @JsonProperty("refresh_token") String refreshToken
    ) {}

    public record MeResponse(
            @JsonProperty("user_id") String userId,
            String email,
            String role,
            @JsonProperty("email_verified") boolean emailVerified,
            @JsonProperty("created_at") String createdAt
    ) {}

    public record WsTicketResponse(
            String ticket,
            @JsonProperty("expires_in") int expiresIn
    ) {}
}