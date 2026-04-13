package com.filetransfer.shared.entity.security;

import com.filetransfer.shared.entity.core.*;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks failed login attempts per username across all service replicas.
 * DB-backed to ensure lockout is enforced consistently regardless of
 * which replica handles the connection.
 */
@Entity
@Table(name = "login_attempts")
@Getter @Setter
@NoArgsConstructor
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
