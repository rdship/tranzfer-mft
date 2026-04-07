package com.filetransfer.shared.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method-level annotation for fine-grained permission checks.
 *
 * Usage:
 *   @RequiresPermission("PARTNER_READ")
 *   public PartnerDetail getPartner(UUID id) { ... }
 *
 * This is evaluated by {@link PermissionAspect}.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {
    /** The permission name to check, e.g. "PARTNER_READ" */
    String value();
}
