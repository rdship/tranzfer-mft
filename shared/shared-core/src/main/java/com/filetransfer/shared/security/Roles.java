package com.filetransfer.shared.security;

/**
 * Constants for @PreAuthorize expressions.
 * Avoids magic strings scattered across controllers.
 *
 * Usage:
 *   @PreAuthorize(Roles.ADMIN)
 *   @PreAuthorize(Roles.ADMIN_OR_OPERATOR)
 */
public final class Roles {

    private Roles() {}

    public static final String ADMIN = "hasRole('ADMIN')";
    public static final String OPERATOR = "hasAnyRole('ADMIN', 'OPERATOR')";
    public static final String USER = "hasAnyRole('ADMIN', 'OPERATOR', 'USER')";
    public static final String VIEWER = "hasAnyRole('ADMIN', 'OPERATOR', 'USER', 'VIEWER')";
    public static final String PARTNER = "hasAnyRole('ADMIN', 'PARTNER')";
    public static final String INTERNAL = "hasRole('INTERNAL')";
    public static final String ADMIN_OR_OPERATOR = OPERATOR;
    public static final String ANY_AUTHENTICATED = "isAuthenticated()";
}
