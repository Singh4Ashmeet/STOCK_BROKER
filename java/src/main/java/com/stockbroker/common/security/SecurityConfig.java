package com.stockbroker.common.security;

import com.stockbroker.common.config.JwtProperties;
import com.stockbroker.common.security.jwt.JwtAccessDeniedHandler;
import com.stockbroker.common.security.jwt.JwtAuthenticationConverter;
import com.stockbroker.common.security.jwt.JwtAuthenticationEntryPoint;
import com.stockbroker.common.security.jwt.JwtTokenFilter;
import com.stockbroker.common.security.jwt.JwtTokenValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security configuration for the API.
 *
 * <p>Configures:
 * <ul>
 *   <li>JWT-based resource server (stateless)</li>
 *   <li>Method-level security with @PreAuthorize</li>
 *   <li>CORS for frontend</li>
 *   <li>Public endpoints (auth, health)</li>
 *   <li>Protected endpoints requiring authentication</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
public class SecurityConfig {

    private final JwtProperties jwtProperties;
    private final JwtTokenValidator jwtTokenValidator;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    @Autowired
    public SecurityConfig(JwtProperties jwtProperties,
                          JwtTokenValidator jwtTokenValidator,
                          JwtAuthenticationConverter jwtAuthenticationConverter) {
        this.jwtProperties = jwtProperties;
        this.jwtTokenValidator = jwtTokenValidator;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain publicApiFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/v1/auth/**", "/api/v1/system/health", "/actuator/health", "/actuator/info")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain protectedApiFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/v1/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/instruments").authenticated()
                        .requestMatchers("/api/v1/market/**").authenticated()
                        .requestMatchers("/api/v1/orders/**").authenticated()
                        .requestMatchers("/api/v1/fills/**").authenticated()
                        .requestMatchers("/api/v1/portfolio/**").authenticated()
                        .requestMatchers("/api/v1/ledger/**").authenticated()
                        .requestMatchers("/api/v1/watchlists/**").authenticated()
                        .requestMatchers("/api/v1/alerts/**").authenticated()
                        .requestMatchers("/api/v1/backtests/**").authenticated()
                        .requestMatchers("/api/v1/strategies/**").authenticated()
                        .requestMatchers("/api/v1/me").authenticated()
                        .requestMatchers("/api/v1/ws/ticket").authenticated()
                        .requestMatchers("/api/v1/system/latency").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)
                        )
                        .authenticationEntryPoint(new JwtAuthenticationEntryPoint())
                        .accessDeniedHandler(new JwtAccessDeniedHandler())
                )
                .addFilterBefore(new JwtTokenFilter(jwtTokenValidator, jwtAuthenticationConverter), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/actuator/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus").hasRole("ADMIN")
                        .anyRequest().hasRole("ADMIN")
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000", "http://127.0.0.1:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Request-Id", "RateLimit-Limit", "RateLimit-Remaining", "Retry-After"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Argon2id with OWASP recommended parameters (tuned for ~50-100ms)
        // iterations=3, memory=65536KB (64MB), parallelism=4, hashLength=32, saltLength=16
        return new Argon2PasswordEncoder(32, 16, 3, 65536, 4);
    }
}