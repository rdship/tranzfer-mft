package com.filetransfer.shared.exception;

import com.filetransfer.shared.dto.ApiError;
import com.filetransfer.shared.enums.ErrorCode;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * JPA-specific exception handler. Active only when JPA is on the classpath.
 */
@RestControllerAdvice
@Slf4j
@ConditionalOnClass(name = "jakarta.persistence.EntityManager")
public class JpaPlatformExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiError> handleJpaEntityNotFound(EntityNotFoundException ex, HttpServletRequest request) {
        log.warn("JPA entity not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiError.of(404, "Not Found", ErrorCode.ENTITY_NOT_FOUND.getCode(), ex.getMessage(), request.getRequestURI()));
    }
}
