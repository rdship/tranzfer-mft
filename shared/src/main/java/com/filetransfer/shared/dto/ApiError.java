package com.filetransfer.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

/**
 * Standardized error response for all TranzFer API endpoints.
 *
 * Every error response follows this shape:
 * {
 *   "timestamp": "2024-01-15T10:30:00Z",
 *   "status": 404,
 *   "error": "Not Found",
 *   "code": "ENTITY_NOT_FOUND",
 *   "message": "Account with ID abc123 not found",
 *   "path": "/api/accounts/abc123",
 *   "correlationId": "a1b2c3d4",
 *   "details": ["field 'email' must not be blank"]
 * }
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {

    @Builder.Default
    private final Instant timestamp = Instant.now();
    private final int status;
    private final String error;
    private final String code;
    private final String message;
    private final String path;
    private final String correlationId;
    private final List<String> details;

    /** Convenience factory for simple errors. */
    public static ApiError of(int status, String error, String code, String message, String path) {
        return ApiError.builder()
                .status(status).error(error).code(code).message(message).path(path)
                .correlationId(org.slf4j.MDC.get("correlationId"))
                .build();
    }
}
