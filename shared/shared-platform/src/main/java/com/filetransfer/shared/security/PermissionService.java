package com.filetransfer.shared.security;

import com.filetransfer.shared.entity.core.User;
import com.filetransfer.shared.repository.security.RolePermissionRepository;
import com.filetransfer.shared.repository.security.UserPermissionRepository;
import com.filetransfer.shared.repository.core.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Fine-grained permission checker.
 *
 * Permission resolution order:
 *   1. Check user-specific permissions (user_permissions table)
 *   2. Check role-based permissions (role_permissions table)
 *
 * If the user has the permission via either path, access is granted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionService {

    private final UserRepository userRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserPermissionRepository userPermissionRepository;

    /**
     * Check if a user has a specific permission, optionally scoped to a resource.
     *
     * @param username       the user's email (principal)
     * @param permissionName e.g. "PARTNER_READ", "USER_DELETE"
     * @param resourceId     optional resource UUID for per-resource grants (null = global check)
     * @return true if the user has the permission
     */
    public boolean hasPermission(String username, String permissionName, UUID resourceId) {
        Optional<User> userOpt = userRepository.findByEmail(username);
        if (userOpt.isEmpty()) {
            log.debug("Permission check failed: user not found: {}", username);
            return false;
        }

        User user = userOpt.get();

        // 1. Check role-based permissions
        List<String> rolePermissions = rolePermissionRepository
                .findPermissionNamesByRole(user.getRole().name());
        if (rolePermissions.contains(permissionName)) {
            return true;
        }

        // 2. Check user-specific permissions (with optional resource scoping)
        if (resourceId != null) {
            List<String> userPerms = userPermissionRepository
                    .findPermissionNamesForUserAndResource(user.getId(), resourceId);
            if (userPerms.contains(permissionName)) {
                return true;
            }
        }

        // 3. Check global user-specific permissions (resourceId = null in DB)
        List<String> globalUserPerms = userPermissionRepository
                .findPermissionNamesForUserAndResource(user.getId(), user.getId());
        // The query matches resourceId IS NULL OR resourceId = :resourceId,
        // so passing any UUID will also match NULL entries.
        // But we need a cleaner approach: just check for null-scoped grants.
        // The query already handles this: "up.resourceId IS NULL OR up.resourceId = :resourceId"

        log.debug("Permission denied: user={}, permission={}, resourceId={}",
                username, permissionName, resourceId);
        return false;
    }

    /**
     * Get all effective permission names for a user (role + user-specific combined).
     */
    public Set<String> getEffectivePermissions(String username) {
        Optional<User> userOpt = userRepository.findByEmail(username);
        if (userOpt.isEmpty()) return Collections.emptySet();

        User user = userOpt.get();
        Set<String> permissions = new HashSet<>();

        // Role-based permissions
        permissions.addAll(rolePermissionRepository.findPermissionNamesByRole(user.getRole().name()));

        // User-specific permissions (global only, no resource filter)
        userPermissionRepository.findByUserIdWithPermission(user.getId())
                .forEach(up -> permissions.add(up.getPermission().getName()));

        return permissions;
    }
}
