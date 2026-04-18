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

    /**
     * R122: S2S-callable write endpoints. Admitted by any of:
     *   - a human ADMIN / OPERATOR (test/ops use cases),
     *   - an inter-service caller holding a SPIFFE JWT-SVID, which
     *     {@code PlatformJwtAuthFilter} (Path 1) maps to {@code ROLE_INTERNAL}.
     *
     * <p>Closes the tester's R120 finding: storage-manager / keystore-manager /
     * screening-service all had class-level {@code @PreAuthorize(Roles.OPERATOR)},
     * which accepted ADMIN + OPERATOR but not INTERNAL. SPIFFE-authenticated S2S
     * calls from sftp-service / ftp-service / flow-engine got a valid
     * {@code ROLE_INTERNAL} token but no role match → 403 AccessDenied, with
     * SPIFFE otherwise working end-to-end. This constant is the single place
     * for that "internal write allowed" intent; apply it at the controller
     * class level on services the flow engine calls.
     */
    public static final String INTERNAL_OR_OPERATOR = "hasAnyRole('ADMIN', 'OPERATOR', 'INTERNAL')";
}
