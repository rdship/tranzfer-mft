package com.filetransfer.sentinel.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sentinel_findings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SentinelFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 50)
    private String analyzer;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    @Column(nullable = false, length = 20)
    private String severity;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String evidence;

    @Column(name = "affected_service", length = 50)
    private String affectedService;

    @Column(name = "affected_account", length = 100)
    private String affectedAccount;

    @Column(name = "track_id", length = 64)
    private String trackId;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "OPEN";

    @Column(name = "github_issue_url", length = 500)
    private String githubIssueUrl;

    @Column(name = "correlation_group_id")
    private UUID correlationGroupId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
