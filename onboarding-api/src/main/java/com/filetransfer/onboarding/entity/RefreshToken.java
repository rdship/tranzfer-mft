package com.filetransfer.onboarding.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Refresh token stored in database for secure token rotation.
 *
 * <p>Each login creates a refresh token with 7-day TTL. When the access token
 * expires (15 min), the client sends the refresh token to get a new access token.
 * Each refresh rotates the token — the old one is invalidated.
 *
 * <p>Logout invalidates all refresh tokens for the user. Admin can revoke
 * all sessions for a user via DELETE /api/auth/sessions/{email}.
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
    @Index(name = "idx_refresh_token", columnList = "token", unique = true),
    @Index(name = "idx_refresh_user", columnList = "userEmail"),
    @Index(name = "idx_refresh_expiry", columnList = "expiresAt")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(nullable = false)
    private String userEmail;

    @Column(nullable = false)
    private String userRole;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    /** IP address of the client that created this token. */
    @Column(length = 45)
    private String clientIp;

    /** User agent of the client. */
    @Column(length = 512)
    private String userAgent;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isUsable() {
        return !revoked && !isExpired();
    }
}
