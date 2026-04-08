package com.filetransfer.shared.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Standardized error codes across all TranzFer services.
 * Clients can switch on these codes for programmatic error handling.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // General
    INTERNAL_ERROR("INTERNAL_ERROR", "An unexpected error occurred"),
    VALIDATION_FAILED("VALIDATION_FAILED", "Request validation failed"),
    ENTITY_NOT_FOUND("ENTITY_NOT_FOUND", "Requested entity not found"),
    ENTITY_CONFLICT("ENTITY_CONFLICT", "Entity already exists or conflicts"),
    ILLEGAL_ARGUMENT("ILLEGAL_ARGUMENT", "Invalid argument provided"),
    ACCESS_DENIED("ACCESS_DENIED", "Access denied"),
    UNAUTHORIZED("UNAUTHORIZED", "Authentication required"),

    // Service communication
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", "Downstream service is unreachable"),
    CIRCUIT_OPEN("CIRCUIT_OPEN", "Circuit breaker is open for downstream service"),

    // Transfer-specific
    TRANSFER_FAILED("TRANSFER_FAILED", "File transfer failed"),
    ENCRYPTION_FAILED("ENCRYPTION_FAILED", "Encryption or decryption failed"),
    KEY_NOT_FOUND("KEY_NOT_FOUND", "Cryptographic key not found"),
    LICENSE_EXPIRED("LICENSE_EXPIRED", "License has expired"),
    QUOTA_EXCEEDED("QUOTA_EXCEEDED", "Transfer quota exceeded");

    private final String code;
    private final String description;
}
