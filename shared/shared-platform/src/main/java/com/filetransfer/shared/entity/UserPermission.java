package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-user permission grant, optionally scoped to a specific resource.
 * When resource_id is null, the permission applies globally for this user.
 * When resource_id is set, the permission applies only to that specific resource.
 */
@Entity
@Table(name = "user_permissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "permission_id", nullable = false)
    private Permission permission;

    /** Optional resource-level scoping (e.g., a specific partner UUID) */
    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
