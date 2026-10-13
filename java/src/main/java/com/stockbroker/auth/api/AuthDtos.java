package com.stockbroker.auth.api;

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

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 12, max = 128) @Pattern(regexp = ".*[A-Z].*") // At least one uppercase
            @Pattern(regexp = ".*[a-z].*") // At least one lowercase
            @Pattern(regexp = ".*\\d.*") // At least one digit
            @Pattern(regexp = ".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*") // At least one special char
            String password
    ) {}

    public record RegisterResponse(
            @JsonProperty("user_id") String userId,
            String email,
            String role,
            @JsonProperty("created_at") String createdAt
    ) {}

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password
    ) {}

    public record LoginResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") int expiresIn,
            @JsonProperty("refresh_token") String refreshToken
    ) {}

    public record RefreshRequest(
            @JsonProperty("refresh_token") @NotBlank String refreshToken
    ) {}

    public record RefreshResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") int expiresIn,
            @JsonProperty("refresh_token") String refreshToken
    ) {}

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