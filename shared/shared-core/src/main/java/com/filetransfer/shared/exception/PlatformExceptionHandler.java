package com.filetransfer.shared.exception;

import com.filetransfer.shared.dto.ApiError;
import com.filetransfer.shared.enums.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Platform-wide exception handler. Active by default for all services.
 * Services that need custom handling can set platform.exception-handler.shared=false
 * and provide their own @RestControllerAdvice.
 *
 * Handles:
 * - PlatformException hierarchy (custom business errors)
 * - Validation errors (@Valid failures)
 * - JPA EntityNotFoundException
 * - IllegalArgumentException
 * - AccessDeniedException
 * - ResourceAccessException (downstream service unreachable)
 * - Generic Exception (500 catch-all)
 */
@RestControllerAdvice
@Slf4j
@ConditionalOnProperty(name = "platform.exception-handler.shared", havingValue = "true", matchIfMissing = true)
public class PlatformExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiError> handleEntityNotFound(EntityNotFoundException ex, HttpServletRequest request) {
        log.warn("Entity not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiError.of(404, "Not Found", ErrorCode.ENTITY_NOT_FOUND.getCode(), ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ApiError> handlePlatformException(PlatformException ex, HttpServletRequest request) {
        HttpStatus status = mapToHttpStatus(ex.getErrorCode());
        log.warn("[{}] {}: {}", MDC.get("correlationId"), ex.getErrorCode().getCode(), ex.getMessage());
        return ResponseEntity.status(status).body(
                ApiError.of(status.value(), status.getReasonPhrase(), ex.getErrorCode().getCode(), ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .toList();
        log.warn("Validation failed: {}", details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiError.builder()
                        .status(400).error("Bad Request").code(ErrorCode.VALIDATION_FAILED.getCode())
                        .message("Validation failed").path(request.getRequestURI())
                        .correlationId(MDC.get("correlationId")).details(details)
                        .build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiError.of(400, "Bad Request", ErrorCode.ILLEGAL_ARGUMENT.getCode(), ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiError.of(403, "Forbidden", ErrorCode.ACCESS_DENIED.getCode(), "Access denied", request.getRequestURI()));
    }

    /**
     * Wrong password / unknown user on the login endpoint. Previously this
     * fell through to the generic 500 handler which logged a stack trace
     * on every bad login — polluting ERROR logs with normal user mistakes
     * and showing "Internal Server Error" to the operator. Now returns a
     * clean 401 with a friendly message.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        log.warn("Bad credentials at {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiError.of(401, "Unauthorized", ErrorCode.UNAUTHORIZED.getCode(),
                        ex.getMessage(), request.getRequestURI()));
    }

    /**
     * A @RequestParam couldn't be coerced to its target type — typically
     * happens when the UI sends an invalid enum value (e.g.
     * ?status=DELIVERED when the enum has MOVED_TO_SENT). Return a
     * helpful 400 with the bad value and the parameter name so the UI
     * can recover without the operator seeing a scary 500 page.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String msg = String.format("Invalid value '%s' for parameter '%s'",
                ex.getValue(), ex.getName());
        log.warn("Type mismatch at {}: {}", request.getRequestURI(), msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiError.of(400, "Bad Request", ErrorCode.VALIDATION_FAILED.getCode(),
                        msg, request.getRequestURI()));
    }

    /** A required request header wasn't sent (e.g. X-Admin-Key). Return 400, not 500. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(
            MissingRequestHeaderException ex, HttpServletRequest request) {
        String msg = String.format("Required header '%s' is missing", ex.getHeaderName());
        log.warn("Missing header at {}: {}", request.getRequestURI(), msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiError.of(400, "Bad Request", ErrorCode.VALIDATION_FAILED.getCode(),
                        msg, request.getRequestURI()));
    }

    /** A required query/form parameter wasn't sent. Return 400, not 500. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        String msg = String.format("Required parameter '%s' is missing", ex.getParameterName());
        log.warn("Missing parameter at {}: {}", request.getRequestURI(), msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiError.of(400, "Bad Request", ErrorCode.VALIDATION_FAILED.getCode(),
                        msg, request.getRequestURI()));
    }

    /** Wrong HTTP method on a known URL — return 405, not 500. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        log.warn("Method not allowed at {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
                ApiError.of(405, "Method Not Allowed", ErrorCode.VALIDATION_FAILED.getCode(),
                        ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMessageNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        String msg = ex.getMostSpecificCause() != null
            ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiError.of(400, "Bad Request", "INVALID_REQUEST_BODY",
                        "Invalid request body: " + msg, request.getRequestURI()));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiError> handleServiceUnavailable(ResourceAccessException ex, HttpServletRequest request) {
        log.error("Downstream service unreachable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                ApiError.of(503, "Service Unavailable", ErrorCode.SERVICE_UNAVAILABLE.getCode(),
                        "A required service is temporarily unavailable", request.getRequestURI()));
    }

    /**
     * Preserve the HTTP status encoded in a ResponseStatusException (which
     * Spring controllers throw via orElseThrow(() -> new ResponseStatusException(
     * HttpStatus.NOT_FOUND, ...))). Before this handler existed, such
     * exceptions were caught by the generic @ExceptionHandler(Exception.class)
     * below and downgraded to 500 — hiding legitimate 404/409/etc. from
     * the UI.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(
            ResponseStatusException ex, HttpServletRequest request) {
        int status = ex.getStatusCode().value();
        String reason = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        ErrorCode code = switch (status) {
            case 400 -> ErrorCode.VALIDATION_FAILED;
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 403 -> ErrorCode.ACCESS_DENIED;
            case 404 -> ErrorCode.ENTITY_NOT_FOUND;
            case 409 -> ErrorCode.ENTITY_CONFLICT;
            default  -> ErrorCode.INTERNAL_ERROR;
        };
        log.warn("[{}] {} {}: {}", MDC.get("correlationId"), status, ex.getStatusCode(), reason);
        return ResponseEntity.status(status).body(
                ApiError.of(status, ex.getStatusCode().toString(), code.getCode(),
                        reason, request.getRequestURI()));
    }

    /**
     * R125: missing endpoint/resource should return 404, not the generic 500.
     * Spring 6.1+ raises NoResourceFoundException for unmapped static paths and
     * NoHandlerFoundException for unmapped request handlers (when the
     * DispatcherServlet is configured to throw on no-handler, which it is by
     * default in Spring Boot 3.x). Without these handlers the generic
     * Exception.class handler below turned every typo'd URL into a scary 500
     * with "An unexpected error occurred" — masking the real cause in the
     * tester's endpoint audits (e.g. POST /api/flow-executions/:id/retry
     * reported as 500 when the real answer is "no such endpoint").
     */
    @ExceptionHandler({
            org.springframework.web.servlet.NoHandlerFoundException.class,
            org.springframework.web.servlet.resource.NoResourceFoundException.class
    })
    public ResponseEntity<ApiError> handleNoHandler(Exception ex, HttpServletRequest request) {
        log.warn("No handler for {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiError.of(404, "Not Found", ErrorCode.ENTITY_NOT_FOUND.getCode(),
                        "No endpoint " + request.getMethod() + " " + request.getRequestURI(),
                        request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiError.of(500, "Internal Server Error", ErrorCode.INTERNAL_ERROR.getCode(),
                        "An unexpected error occurred", request.getRequestURI()));
    }

    private HttpStatus mapToHttpStatus(ErrorCode code) {
        return switch (code) {
            case ENTITY_NOT_FOUND, KEY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VALIDATION_FAILED, ILLEGAL_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case ENTITY_CONFLICT -> HttpStatus.CONFLICT;
            case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case SERVICE_UNAVAILABLE, CIRCUIT_OPEN -> HttpStatus.SERVICE_UNAVAILABLE;
            case LICENSE_EXPIRED -> HttpStatus.PAYMENT_REQUIRED;
            case QUOTA_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
