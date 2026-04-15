package com.filetransfer.shared.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * AOP aspect that enforces @RequiresPermission annotations.
 *
 * Checks if the current authenticated principal has the required permission
 * either via role-based grants or user-specific grants.
 *
 * Internal service calls (ROLE_INTERNAL) bypass permission checks.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "platform.permissions.enabled", havingValue = "true", matchIfMissing = true)
public class PermissionAspect {

    private final PermissionService permissionService;

    @Around("@annotation(requiresPermission)")
    public Object checkPermission(ProceedingJoinPoint joinPoint,
                                   RequiresPermission requiresPermission) throws Throwable {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("Not authenticated");
        }

        // Internal service calls bypass permission checks
        if (auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_INTERNAL"))) {
            return joinPoint.proceed();
        }

        String username = auth.getName();
        String permName = requiresPermission.value();

        // Check if permission is already in the security context (loaded by filter)
        boolean hasAuthority = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("PERM_" + permName));

        if (hasAuthority) {
            return joinPoint.proceed();
        }

        // Fallback: direct DB check (for cases where filter didn't load permissions)
        if (permissionService.hasPermission(username, permName, null)) {
            return joinPoint.proceed();
        }

        log.warn("Permission denied: user={}, required={}", username, permName);
        throw new AccessDeniedException("Insufficient permission: " + permName);
    }
}
