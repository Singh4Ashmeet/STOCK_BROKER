package com.stockbroker.common.security.jwt;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.stockbroker.common.config.JwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Validates JWT tokens using the configured public key.
 *
 * <p>Checks:
 * <ul>
 *   <li>Signature validity (RS256)</li>
 *   <li>Expiration (exp)</li>
 *   <li>Not before (nbf)</li>
 *   <li>Issuer (iss)</li>
 *   <li>Audience (aud)</li>
 * </ul>
 */
@Component
public class JwtTokenValidator {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenValidator.class);

    private final JwtProperties jwtProperties;
    private final ResourceLoader resourceLoader;
    private RSASSAVerifier verifier;
    private RSAPublicKey publicKey;

    public JwtTokenValidator(JwtProperties jwtProperties, ResourceLoader resourceLoader) {
        this.jwtProperties = jwtProperties;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void init() {
        try {
            Resource resource = resourceLoader.getResource(jwtProperties.getPublicKeyPath());
            try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[1024];
                int len;
                while ((len = reader.read(buf)) != -1) {
                    sb.append(buf, 0, len);
                }
                // Strip PEM headers/footers and decode Base64
                String pem = sb.toString()
                        .replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "")
                        .replaceAll("\\s", "");
                byte[] decoded = Base64.getDecoder().decode(pem);
                X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                this.publicKey = (RSAPublicKey) kf.generatePublic(spec);
                this.verifier = new RSASSAVerifier(this.publicKey);
                log.info("JWT public key loaded successfully from {}", jwtProperties.getPublicKeyPath());
            }
        } catch (Exception e) {
            log.error("Failed to load JWT public key from {}", jwtProperties.getPublicKeyPath(), e);
            throw new IllegalStateException("Cannot initialize JWT validator", e);
        }
    }

    /**
     * Validates a JWT token and returns the claims if valid.
     *
     * @param token the JWT token string (without "Bearer " prefix)
     * @return the validated claims set
     * @throws JwtValidationException if the token is invalid
     */
    public JWTClaimsSet validate(String token) throws JwtValidationException {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);

            // Verify algorithm
            JWSHeader header = signedJWT.getHeader();
            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm())) {
                throw new JwtValidationException("Invalid algorithm: " + header.getAlgorithm());
            }

            // Verify signature
            if (!signedJWT.verify(verifier)) {
                throw new JwtValidationException("Invalid signature");
            }

            // Get claims
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                throw new JwtValidationException("Missing subject claim");
            }
            try {
                UUID.fromString(subject);
            } catch (IllegalArgumentException e) {
                throw new JwtValidationException("Invalid subject claim");
            }

            Date issuedAt = claims.getIssueTime();
            if (issuedAt == null) {
                throw new JwtValidationException("Missing issued-at claim");
            }
            if (issuedAt.toInstant().isAfter(Instant.now().plus(jwtProperties.getClockSkew()))) {
                throw new JwtValidationException("Token issued in the future");
            }

            if (claims.getJWTID() == null || claims.getJWTID().isBlank()) {
                throw new JwtValidationException("Missing JWT ID claim");
            }

            String role = claims.getStringClaim("role");
            if (!"USER".equals(role) && !"ADMIN".equals(role)) {
                throw new JwtValidationException("Invalid role claim");
            }

            // Verify expiration with clock skew
            Date expiration = claims.getExpirationTime();
            if (expiration == null) {
                throw new JwtValidationException("Missing expiration claim");
            }
            Instant now = Instant.now().plus(jwtProperties.getClockSkew());
            if (expiration.toInstant().isBefore(now)) {
                throw new JwtValidationException("Token expired");
            }

            // Verify not-before
            Date notBefore = claims.getNotBeforeTime();
            if (notBefore != null && notBefore.toInstant().isAfter(Instant.now().minus(jwtProperties.getClockSkew()))) {
                throw new JwtValidationException("Token not yet valid");
            }

            // Verify issuer
            String issuer = claims.getIssuer();
            if (!jwtProperties.getIssuer().equals(issuer)) {
                throw new JwtValidationException("Invalid issuer: " + issuer);
            }

            // Verify audience
            if (claims.getAudience() == null || !claims.getAudience().contains(jwtProperties.getAudience())) {
                throw new JwtValidationException("Invalid audience");
            }

            return claims;

        } catch (JwtValidationException e) {
            throw e;
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            throw new JwtValidationException("Invalid token: " + e.getMessage());
        }
    }

    /**
     * Extracts the subject (user ID) from a token without full validation.
     * Used for logging/metrics only.
     */
    public String extractSubjectUnsafe(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            return signedJWT.getJWTClaimsSet().getSubject();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }
}
