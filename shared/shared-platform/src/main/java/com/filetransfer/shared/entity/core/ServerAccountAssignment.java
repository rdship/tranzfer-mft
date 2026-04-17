package com.filetransfer.shared.entity.core;

import com.filetransfer.shared.entity.core.*;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Many-to-many assignment of a {@link TransferAccount} to a {@link ServerInstance}.
 *
 * <p>A single transfer account can be assigned to multiple server instances
 * (e.g., the same user accessible on sftp-server-1:21111 AND sftp-server-2:21212).
 *
 * <p>Each assignment carries <em>per-server overrides</em>:
 * <ul>
 *   <li>Home folder — the user's root on this particular server (overrides account default)
 *   <li>Permissions — read/write/delete/rename/mkdir flags (overrides account permissions JSON)
 *   <li>QoS limits  — upload/download bandwidth and concurrent session cap
 * </ul>
 *
 * <p>NULL permission/QoS fields mean "inherit from the account-level setting".
 * This allows the account to serve as the default and each server to selectively tighten.
 */
@Entity
@Table(
    name = "server_account_assignments",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_saa_server_account",
        columnNames = {"server_instance_id", "transfer_account_id"}
    ),
    indexes = {
        @Index(name = "idx_saa_server",  columnList = "server_instance_id"),
        @Index(name = "idx_saa_account", columnList = "transfer_account_id"),
        @Index(name = "idx_saa_enabled", columnList = "enabled")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ServerAccountAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_instance_id", nullable = false)
    private ServerInstance serverInstance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_account_id", nullable = false)
    private TransferAccount transferAccount;

    // ── Per-server home folder ────────────────────────────────────────────────

    /**
     * Override the account's default home directory on this server.
     * {@code null} = use {@link TransferAccount#getHomeDir()}.
     * Example: {@code /uploads/partners/acme} — narrows the user to a sub-tree.
     */
    @Column(length = 500)
    private String homeFolderOverride;

    // ── Per-server permissions (null = inherit from account) ──────────────────

    private Boolean canRead;
    private Boolean canWrite;
    private Boolean canDelete;
    private Boolean canRename;
    private Boolean canMkdir;

    // ── Per-server QoS overrides (null = use account-level QoS) ──────────────
    // V44 migration truncated these column names to _sec; Hibernate would otherwise
    // derive _per_second from the field name and emit a column that doesn't exist.

    /** Max concurrent sessions on this server (null = account.qosMaxConcurrentSessions). */
    private Integer maxConcurrentSessions;

    /** Upload speed cap in bytes/second (null = account.qosUploadBytesPerSecond). */
    @Column(name = "max_upload_bytes_per_sec")
    private Long maxUploadBytesPerSecond;

    /** Download speed cap in bytes/second (null = account.qosDownloadBytesPerSecond). */
    @Column(name = "max_download_bytes_per_sec")
    private Long maxDownloadBytesPerSecond;

    // ── State ─────────────────────────────────────────────────────────────────

    /** Soft-disable without removing the assignment — quickly revoke then re-enable access. */
    @Builder.Default
    private boolean enabled = true;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(length = 255)
    private String createdBy;

    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Effective home folder: override if set, otherwise account default. */
    public String effectiveHomeFolder() {
        return (homeFolderOverride != null && !homeFolderOverride.isBlank())
               ? homeFolderOverride
               : (transferAccount != null ? transferAccount.getHomeDir() : "/");
    }

    /** Effective read permission: assignment override if set, otherwise account permissions. */
    public boolean effectiveCanRead() {
        if (canRead != null) return canRead;
        if (transferAccount != null && transferAccount.getPermissions() != null) {
            Boolean r = (Boolean) transferAccount.getPermissions().get("read");
            return r == null || r;
        }
        return true;
    }

    public boolean effectiveCanWrite() {
        if (canWrite != null) return canWrite;
        if (transferAccount != null && transferAccount.getPermissions() != null) {
            Boolean w = (Boolean) transferAccount.getPermissions().get("write");
            return w != null && w;
        }
        return false;
    }

    public boolean effectiveCanDelete() {
        if (canDelete != null) return canDelete;
        if (transferAccount != null && transferAccount.getPermissions() != null) {
            Boolean d = (Boolean) transferAccount.getPermissions().get("delete");
            return d != null && d;
        }
        return false;
    }
}
