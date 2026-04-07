package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.service.UserDeletionService;
import com.filetransfer.shared.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * User management endpoints.
 *
 * DELETE /api/users/{id} — GDPR Article 17 Right to Erasure.
 *   - ADMIN can delete any user
 *   - Users can request self-deletion (id must match their own)
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User management including GDPR right-to-erasure")
public class UserController {

    private final UserDeletionService userDeletionService;
    private final com.filetransfer.shared.repository.UserRepository userRepository;

    /**
     * GDPR Right-to-Deletion endpoint.
     *
     * Cascading anonymization/deletion:
     *   - User record: email anonymized, password cleared
     *   - Audit logs: principal anonymized (entries kept for compliance)
     *   - Transfer accounts: deactivated, anonymized
     *   - TOTP secrets: deleted
     *   - Login attempts: deleted
     *   - User permissions: deleted
     *
     * Only ADMIN can delete other users. Users can request self-deletion.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.ADMIN + " or @userController.isSelf(#email, #id)")
    @Operation(summary = "Delete a user (GDPR Article 17 Right to Erasure)")
    public ResponseEntity<Map<String, Object>> deleteUser(
            @PathVariable UUID id,
            @AuthenticationPrincipal String email) {

        UserDeletionService.DeletionReport report = userDeletionService.deleteUser(id, email);
        return ResponseEntity.ok(report.toMap());
    }

    /**
     * SpEL helper: checks if the authenticated user is requesting their own deletion.
     */
    public boolean isSelf(String email, UUID userId) {
        return userRepository.findByEmail(email)
                .map(user -> user.getId().equals(userId))
                .orElse(false);
    }
}
