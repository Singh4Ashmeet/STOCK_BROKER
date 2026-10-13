package com.stockbroker.auth.api;

import com.stockbroker.auth.service.AuthService;
import com.stockbroker.common.error.BusinessProblem;
import com.stockbroker.common.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.UUID;

/**
 * Authentication REST endpoints.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthDtos.RegisterResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        var result = authService.register(request.email(), request.password());
        return ResponseEntity.status(201).body(new AuthDtos.RegisterResponse(
                result.userId().toString(),
                result.email(),
                result.role(),
                result.createdAt().toString()
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                                         HttpServletRequest httpRequest,
                                                         HttpServletResponse response) {
        String clientIp = getClientIp(httpRequest);
        var result = authService.login(request.email(), request.password(), clientIp);

        // Set refresh token as HttpOnly cookie
        ResponseCookie refreshCookie = ResponseCookie.from("refresh_token", result.refreshToken())
                .httpOnly(true)
                .secure(false) // Set to true in production with HTTPS
                .path("/api/v1/auth/refresh")
                .maxAge(Duration.ofDays(30))
                .sameSite("Strict")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());

        return ResponseEntity.ok(new AuthDtos.LoginResponse(
                result.accessToken(),
                (int) Duration.ofMinutes(15).getSeconds(),
                result.refreshToken()
        ));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthDtos.RefreshResponse> refresh(@Valid @RequestBody AuthDtos.RefreshRequest request,
                                                             HttpServletResponse response) {
        var result = authService.refresh(request.refreshToken());

        // Set new refresh token as HttpOnly cookie (rotation)
        ResponseCookie refreshCookie = ResponseCookie.from("refresh_token", result.refreshToken())
                .httpOnly(true)
                .secure(false)
                .path("/api/v1/auth/refresh")
                .maxAge(Duration.ofDays(30))
                .sameSite("Strict")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());

        return ResponseEntity.ok(new AuthDtos.RefreshResponse(
                result.accessToken(),
                (int) Duration.ofMinutes(15).getSeconds(),
                result.refreshToken()
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) AuthDtos.LogoutRequest request,
                                        HttpServletResponse response) {
        String refreshToken = request != null ? request.refreshToken() : null;
        authService.logout(refreshToken);

        // Clear refresh cookie
        ResponseCookie clearCookie = ResponseCookie.from("refresh_token", "")
                .httpOnly(true)
                .secure(false)
                .path("/api/v1/auth/refresh")
                .maxAge(0)
                .sameSite("Strict")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, clearCookie.toString());

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AuthDtos.MeResponse> me() {
        UUID userId = CurrentUser.getUserId();
        var user = authService.getUser(userId);
        return ResponseEntity.ok(new AuthDtos.MeResponse(
                user.getId().toString(),
                user.getEmail(),
                user.getRole(),
                user.isEmailVerified(),
                user.getCreatedAt().toString()
        ));
    }

    @PostMapping("/ws/ticket")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AuthDtos.WsTicketResponse> wsTicket() {
        UUID userId = CurrentUser.getUserId();
        var ticket = authService.createWsTicket(userId);
        return ResponseEntity.ok(new AuthDtos.WsTicketResponse(ticket.ticket(), ticket.expiresIn()));
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader != null && !xfHeader.isBlank()) {
            return xfHeader.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }
}