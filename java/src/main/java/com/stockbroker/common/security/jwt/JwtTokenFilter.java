package com.stockbroker.common.security.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that validates JWT tokens from the Authorization header
 * and sets the authentication in the SecurityContext.
 *
 * <p>This filter runs before the Spring Security filter chain for protected endpoints.
 */
public class JwtTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenFilter.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenValidator jwtTokenValidator;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    public JwtTokenFilter(JwtTokenValidator jwtTokenValidator,
                          JwtAuthenticationConverter jwtAuthenticationConverter) {
        this.jwtTokenValidator = jwtTokenValidator;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader(AUTHORIZATION_HEADER);

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();

            try {
                // Validate token
                var claims = jwtTokenValidator.validate(token);

                // Convert to Spring Security Authentication
                var jwt = JwtAuthenticationConverter.createJwt(claims, token);
                var authentication = jwtAuthenticationConverter.convert(jwt);

                // Set in security context
                SecurityContextHolder.getContext().setAuthentication(authentication);

                log.debug("Authenticated user: {}", claims.getSubject());

            } catch (JwtValidationException e) {
                log.debug("JWT validation failed: {}", e.getMessage());
                // Don't set authentication; let the authentication entry point handle it
            } catch (Exception e) {
                log.warn("Unexpected error during JWT validation", e);
            }
        }

        filterChain.doFilter(request, response);
    }
}