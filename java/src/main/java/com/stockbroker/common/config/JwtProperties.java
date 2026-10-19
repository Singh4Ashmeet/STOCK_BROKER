package com.stockbroker.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

/**
 * Configuration properties for JWT authentication.
 */
@Configuration
@ConfigurationProperties(prefix = "sb.auth.jwt")
@Validated
public class JwtProperties {

    /**
     * JWT issuer.
     */
    @NotBlank
    private String issuer = "stockbroker-api";

    /**
     * JWT audience.
     */
    @NotBlank
    private String audience = "stockbroker-client";

    /**
     * Access token expiration.
     */
    @NotNull
    private Duration accessTokenExpiration = Duration.ofMinutes(15);

    /**
     * Refresh token expiration.
     */
    @NotNull
    private Duration refreshTokenExpiration = Duration.ofDays(30);

    /**
     * Path to the private key (PKCS#8 PEM).
     */
    @NotBlank
    private String privateKeyPath = "classpath:keys/jwt_private.key";

    /**
     * Path to the public key (X.509 PEM).
     */
    @NotBlank
    private String publicKeyPath = "classpath:keys/jwt_public.key";

    /**
     * Clock skew tolerance.
     */
    @NotNull
    private Duration clockSkew = Duration.ofSeconds(60);

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public Duration getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    public void setAccessTokenExpiration(Duration accessTokenExpiration) {
        this.accessTokenExpiration = accessTokenExpiration;
    }

    public Duration getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }

    public void setRefreshTokenExpiration(Duration refreshTokenExpiration) {
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String getPublicKeyPath() {
        return publicKeyPath;
    }

    public void setPublicKeyPath(String publicKeyPath) {
        this.publicKeyPath = publicKeyPath;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew;
    }
}