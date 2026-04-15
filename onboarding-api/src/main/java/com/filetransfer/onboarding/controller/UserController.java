package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.service.UserDeletionService;
import com.filetransfer.shared.entity.core.User;
import com.filetransfer.shared.enums.UserRole;
import com.filetransfer.shared.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * User management endpoints.
 *
 * GET    /api/users           — list all users (ADMIN only)
 * PATCH  /api/users/{id}      — update user role/status
 * DELETE /api/users/{id}      — GDPR Article 17 Right to Erasure
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User management including GDPR right-to-erasure")
public class UserController {

    private final UserDeletionService userDeletionService;
    private final com.filetransfer.shared.repository.core.UserRepository userRepository;

    @GetMapping
    @PreAuthorize(Roles.ADMIN)
    @Operation(summary = "List all users")
    public List<Map<String, Object>> list() {
        return userRepository.findAll().stream().map(this::toDto).toList();
    }

    @PatchMapping("/{id}")
    @PreAuthorize(Roles.ADMIN)
    @Operation(summary = "Update user role or status")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        if (body.containsKey("role")) {
            user.setRole(UserRole.valueOf((String) body.get("role")));
        }
        return toDto(userRepository.save(user));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.ADMIN + " or @userController.isSelf(#email, #id)")
    @Operation(summary = "Delete a user (GDPR Article 17 Right to Erasure)")
    public ResponseEntity<Map<String, Object>> deleteUser(
            @PathVariable UUID id,
            @AuthenticationPrincipal String email) {

        UserDeletionService.DeletionReport report = userDeletionService.deleteUser(id, email);
        return ResponseEntity.ok(report.toMap());
    }

    public boolean isSelf(String email, UUID userId) {
        return userRepository.findByEmail(email)
                .map(user -> user.getId().equals(userId))
                .orElse(false);
    }

    private Map<String, Object> toDto(User u) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", u.getId());
        dto.put("email", u.getEmail());
        dto.put("role", u.getRole());
        dto.put("createdAt", u.getCreatedAt());
        return dto;
    }
}
