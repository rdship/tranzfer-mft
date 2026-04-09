package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents an admin approval gate created when a flow execution reaches an APPROVE step.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Engine hits APPROVE step → sets execution PAUSED → creates this record (status=PENDING).
 *   <li>Admin reviews in the Flows UI → approves or rejects.
 *   <li>Approve → {@code FlowApprovalService} resumes the engine from {@code stepIndex + 1}.
 *   <li>Reject → execution is cancelled (status=CANCELLED).
 * </ol>
 */
@Entity
@Table(
    name = "flow_approvals",
    uniqueConstraints = @UniqueConstraint(name = "uk_fa_track_step",
                                          columnNames = {"track_id", "step_index"})
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FlowApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The flow execution that is paused. */
    @Column(nullable = false)
    private UUID executionId;

    @Column(nullable = false, length = 12)
    private String trackId;

    /** Display name of the flow — shown in the approvals UI without needing a JOIN. */
    private String flowName;

    @Column(length = 512)
    private String originalFilename;

    /** 0-based index of the APPROVE step within the flow definition. */
    @Column(nullable = false)
    private int stepIndex;

    // ── CAS keys captured at pause (VIRTUAL mode only) ──────────────────────

    @Column(length = 64)
    private String pausedStorageKey;

    @Column(length = 1024)
    private String pausedVirtualPath;

    private Long pausedSizeBytes;

    // ── Approval metadata ─────────────────────────────────────────────────────

    /** Comma-separated usernames/emails shown in the UI as suggested reviewers. Informational only. */
    @Column(columnDefinition = "text")
    private String requiredApprovers;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ApprovalStatus status = ApprovalStatus.PENDING;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant requestedAt = Instant.now();

    private Instant reviewedAt;
    private String reviewedBy;

    @Column(columnDefinition = "text")
    private String reviewNote;

    public enum ApprovalStatus { PENDING, APPROVED, REJECTED }
}
