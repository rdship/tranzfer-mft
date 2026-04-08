package com.filetransfer.shared.enums;

/**
 * Platform user roles with hierarchical permissions.
 *
 * Hierarchy: ADMIN > OPERATOR > USER > VIEWER
 * SYSTEM is for inter-service communication only.
 * PARTNER is for external partner portal access.
 *
 * Usage with @PreAuthorize:
 *   @PreAuthorize("hasRole('ADMIN')")
 *   @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
 */
public enum UserRole {
    ADMIN,
    OPERATOR,
    USER,
    VIEWER,
    PARTNER,
    SYSTEM
}
