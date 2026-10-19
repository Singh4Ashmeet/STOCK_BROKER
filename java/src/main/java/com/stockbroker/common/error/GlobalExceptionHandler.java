package com.stockbroker.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.zalando.problem.DefaultProblem;
import org.zalando.problem.Problem;
import org.zalando.problem.ProblemBuilder;
import org.zalando.problem.Status;
import org.zalando.problem.violations.ConstraintViolationProblem;
import org.zalando.problem.violations.Violation;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler that produces RFC 9457 (Problem+JSON) responses.
 *
 * <p>All error responses include a stable {@code code} field matching the API specification.
 * The handler is ordered with high precedence to catch all exceptions.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles {@link BusinessProblem} - our application-specific errors.
     */
    @ExceptionHandler(BusinessProblem.class)
    public ResponseEntity<Problem> handleBusinessProblem(BusinessProblem ex, HttpServletRequest request) {
        log.debug("Business problem: code={}, title={}, detail={}", ex.getCode(), ex.getTitle(), ex.getDetail());
        return buildResponse(ex, request);
    }

    /**
     * Handles validation errors from @Valid on request bodies.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Problem> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<Violation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toViolation)
                .collect(Collectors.toList());

        String detail = violations.stream()
                .map(v -> v.getField() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));

        ConstraintViolationProblem problem = new ConstraintViolationProblem(
                URI.create("urn:stockbroker:error:VALIDATION_FAILED"),
                Status.BAD_REQUEST,
                violations
        );

        log.debug("Validation failed: {}", detail);
        return buildResponse(problem, request);
    }

    /**
     * Handles validation errors from @Validated on path variables and request params.
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Problem> handleBindException(BindException ex, HttpServletRequest request) {
        List<Violation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toViolation)
                .collect(Collectors.toList());

        String detail = violations.stream()
                .map(v -> v.getField() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));

        ConstraintViolationProblem problem = new ConstraintViolationProblem(
                URI.create("urn:stockbroker:error:VALIDATION_FAILED"),
                Status.BAD_REQUEST,
                violations
        );

        log.debug("Binding validation failed: {}", detail);
        return buildResponse(problem, request);
    }

    /**
     * Handles missing required headers (e.g., Idempotency-Key).
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Problem> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        if ("Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())) {
            return buildResponse(new BusinessProblem.IdempotencyKeyRequired(), request);
        }
        BusinessProblem problem = new BusinessProblem.ValidationFailed(
                "Required header '" + ex.getHeaderName() + "' is missing");
        return buildResponse(problem, request);
    }

    /**
     * Handles malformed JSON request bodies.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Problem> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        BusinessProblem problem = new BusinessProblem.ValidationFailed(
                "Malformed JSON request body: " + ex.getMostSpecificCause().getMessage());
        log.warn("Malformed JSON: {}", ex.getMessage());
        return buildResponse(problem, request);
    }

    /**
     * Handles type mismatch in path variables and request params.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Problem> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String detail = String.format("Parameter '%s' should be of type %s",
                ex.getName(), ex.getRequiredType().getSimpleName());
        BusinessProblem problem = new BusinessProblem.ValidationFailed(detail);
        return buildResponse(problem, request);
    }

    /**
     * Handles authentication exceptions.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Problem> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        BusinessProblem problem;
        if (ex instanceof BadCredentialsException) {
            problem = new BusinessProblem.AuthInvalidCredentials();
        } else if (ex.getMessage() != null && ex.getMessage().contains("expired")) {
            problem = new BusinessProblem.AuthTokenExpired();
        } else {
            problem = new BusinessProblem.AuthTokenInvalid(ex.getMessage());
        }
        log.debug("Authentication failed: {}", ex.getMessage());
        return buildResponse(problem, request);
    }

    /**
     * Handles access denied (authorization failures).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Problem> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        BusinessProblem problem = new BusinessProblem.AuthInsufficientPermissions("USER");
        log.debug("Access denied: {}", ex.getMessage());
        return buildResponse(problem, request);
    }

    /**
     * Handles database constraint violations (unique constraints, etc.).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Problem> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        String message = ex.getMostSpecificCause().getMessage();
        log.debug("Data integrity violation: {}", message);

        BusinessProblem problem;
        if (message != null && message.contains("duplicate key")) {
            if (message.contains("users_email_key") || message.contains("email")) {
                problem = new BusinessProblem.ValidationFailed("Email already registered",
                        Map.of("field", "email", "code", "EMAIL_EXISTS"));
            } else if (message.contains("orders_user_id_idempotency_key_key")) {
                problem = new BusinessProblem.OrderDuplicateKeyMismatch();
            } else {
                problem = new BusinessProblem.ValidationFailed("Duplicate value violates unique constraint");
            }
        } else if (message != null && message.contains("foreign key")) {
            problem = new BusinessProblem.ValidationFailed("Referenced resource does not exist");
        } else {
            problem = new BusinessProblem.ValidationFailed("Data integrity violation");
        }
        return buildResponse(problem, request);
    }

    /**
     * Handles all other uncaught exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> handleException(Exception ex, HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null) {
            requestId = java.util.UUID.randomUUID().toString();
        }

        log.error("Unhandled exception for request {}: {}", requestId, ex.getMessage(), ex);
        BusinessProblem problem = new BusinessProblem.InternalError(requestId);
        return buildResponse(problem, request);
    }

    private Violation toViolation(FieldError error) {
        return new Violation(error.getField(), error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value");
    }

    private ResponseEntity<Problem> buildResponse(Problem problem, HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null) {
            requestId = java.util.UUID.randomUUID().toString();
        }

        // Add request ID to problem if it's a DefaultProblem
        if (problem instanceof DefaultProblem defaultProblem) {
            Map<String, Object> params = new java.util.HashMap<>(defaultProblem.getParameters());
            params.put("requestId", requestId);
            params.put("timestamp", OffsetDateTime.now().toString());
            params.put("path", request.getRequestURI());

            ProblemBuilder builder = Problem.builder()
                    .withType(defaultProblem.getType())
                    .withTitle(defaultProblem.getTitle())
                    .withStatus(defaultProblem.getStatus())
                    .withDetail(defaultProblem.getDetail())
                    .withInstance(defaultProblem.getInstance());
            params.forEach(builder::with);
            Problem enriched = builder.build();
            return ResponseEntity.status(defaultProblem.getStatus().getStatusCode())
                    .header("X-Request-Id", requestId)
                    .header("Content-Type", "application/problem+json")
                    .body(enriched);
        }

        return ResponseEntity.status(problem.getStatus().getStatusCode())
                .header("X-Request-Id", requestId)
                .header("Content-Type", "application/problem+json")
                .body(problem);
    }
}