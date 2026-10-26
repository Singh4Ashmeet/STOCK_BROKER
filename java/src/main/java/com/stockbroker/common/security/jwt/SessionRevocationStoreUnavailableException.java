package com.stockbroker.common.security.jwt;

/**
 * Signals that shared revocation state is unavailable. Authentication must
 * fail closed when this is raised during token validation.
 */
public class SessionRevocationStoreUnavailableException extends RuntimeException {

    public SessionRevocationStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
