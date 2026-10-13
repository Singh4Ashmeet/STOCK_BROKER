package com.stockbroker.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.Assert;

import java.util.UUID;

/**
 * Utility for accessing the current authenticated user.
 *
 * <p>All repository methods should use this to get the user ID
 * rather than accepting it as a parameter.
 */
public final class CurrentUser {

    private CurrentUser() {}

    /**
     * Gets the current user's ID from the security context.
     *
     * @return the user ID (UUID from JWT subject claim)
     * @throws IllegalStateException if no authenticated user is found
     */
    public static UUID getUserId() {
        Authentication authentication = getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            String subject = jwt.getSubject();
            Assert.hasText(subject, "JWT subject claim is missing");
            return UUID.fromString(subject);
        }
        throw new IllegalStateException("No authenticated user found in security context");
    }

    /**
     * Gets the current user's ID if authenticated, otherwise returns null.
     */
    public static UUID getUserIdOrNull() {
        try {
            return getUserId();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /**
     * Checks if the current user has a specific role.
     */
    public static boolean hasRole(String role) {
        Authentication authentication = getAuthentication();
        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role.toUpperCase()));
    }

    /**
     * Checks if the current user is an admin.
     */
    public static boolean isAdmin() {
        return hasRole("ADMIN");
    }

    /**
     * Gets the raw JWT token for the current user.
     */
    public static Jwt getJwt() {
        Authentication authentication = getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken();
        }
        return null;
    }

    /**
     * Gets a custom claim from the current user's JWT.
     */
    @SuppressWarnings("unchecked")
    public static <T> T getClaim(String claimName) {
        Jwt jwt = getJwt();
        return jwt != null ? (T) jwt.getClaim(claimName) : null;
    }

    private static Authentication getAuthentication() {
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication authentication = context.getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("No authenticated user in security context");
        }
        return authentication;
    }
}