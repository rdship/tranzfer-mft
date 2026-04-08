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
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

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

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiError> handleServiceUnavailable(ResourceAccessException ex, HttpServletRequest request) {
        log.error("Downstream service unreachable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                ApiError.of(503, "Service Unavailable", ErrorCode.SERVICE_UNAVAILABLE.getCode(),
                        "A required service is temporarily unavailable", request.getRequestURI()));
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
