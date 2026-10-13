package com.stockbroker.common.security.jwt;

import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts a validated JWT to a Spring Security Authentication token.
 *
 * <p>Extracts:
 * <ul>
 *   <li>Subject (user ID) as the principal</li>
 *   <li>Role claim as authorities (ROLE_USER, ROLE_ADMIN)</li>
 *   <li>All claims for downstream use</li>
 * </ul>
 */
@Component
public class JwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = extractAuthorities(jwt);
        return new JwtAuthenticationToken(jwt, authorities);
    }

    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        // Get role from claims (custom claim "role" or "realm_access.roles")
        String role = jwt.getClaimAsString("role");
        if (role == null) {
            // Check Keycloak-style realm_access
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess != null) {
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) realmAccess.get("roles");
                if (roles != null && !roles.isEmpty()) {
                    role = roles.get(0); // Take first role
                }
            }
        }

        // Default to USER if no role found
        if (role == null) {
            role = "USER";
        }

        // Normalize role
        String normalizedRole = role.toUpperCase();
        if (!normalizedRole.startsWith("ROLE_")) {
            normalizedRole = "ROLE_" + normalizedRole;
        }

        return List.of(new SimpleGrantedAuthority(normalizedRole));
    }

    /**
     * Creates a Jwt object from validated claims (for use in filter).
     */
    public static Jwt createJwt(JWTClaimsSet claims, String tokenValue) {
        Map<String, Object> headers = Map.of("alg", "RS256", "typ", "JWT");
        return new Jwt(
                tokenValue,
                claims.getIssueTime() != null ? claims.getIssueTime().toInstant() : Instant.now(),
                claims.getExpirationTime() != null ? claims.getExpirationTime().toInstant() : Instant.now(),
                headers,
                claims.getClaims()
        );
    }
}